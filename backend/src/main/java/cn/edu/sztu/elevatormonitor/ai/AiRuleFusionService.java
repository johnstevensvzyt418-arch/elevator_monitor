package cn.edu.sztu.elevatormonitor.ai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.Locale;
import java.util.Set;
import java.util.TreeSet;

/**
 * Fuses deterministic device alarms into the existing AI result stream.
 *
 * <p>The model score is preserved as {@code modelScore}; {@code score} is the
 * externally displayed composite score. Only alarm transitions publish a
 * ready result, so a persistent alarm does not create duplicate history
 * points. The AI input window is paused and cleared while a rule alarm is
 * active.</p>
 */
@Service
public class AiRuleFusionService {

    private static final Logger LOGGER = LoggerFactory.getLogger(AiRuleFusionService.class);

    private static final String FEATURE_SCHEMA = AiPredictClient.FEATURE_SCHEMA;
    private static final String ACTIVE_KEY_PREFIX = "ai:rule-fusion:active:";
    private static final String HASH_AI_RESULT = "elevator:ai_result";
    private static final String CHANNEL_AI_RESULT = "elevator:ai_result";
    private static final String HASH_AI_ALARM = "elevator:ai_alarm";
    private static final int REQUIRED_SAMPLES = 5;

    private final TimeSeriesBuffer timeSeriesBuffer;
    private final StringRedisTemplate redis;
    private final ObjectMapper objectMapper;
    private final boolean enabled;
    private final double threshold;
    private final double scoreMargin;

    public AiRuleFusionService(
            TimeSeriesBuffer timeSeriesBuffer,
            StringRedisTemplate redis,
            @Value("${ai.rule-fusion.enabled:true}") boolean enabled,
            @Value("${ai.rule-fusion.threshold:90}") double threshold,
            @Value("${ai.rule-fusion.score-margin:10}") double scoreMargin) {
        this.timeSeriesBuffer = timeSeriesBuffer;
        this.redis = redis;
        this.objectMapper = new ObjectMapper();
        this.enabled = enabled;
        this.threshold = threshold;
        this.scoreMargin = scoreMargin;
    }

    /** Return whether model collection must remain paused for this device. */
    public boolean isActive(String deviceId) {
        if (!enabled || deviceId == null || deviceId.trim().isEmpty()) {
            return false;
        }
        try {
            String active = redis.opsForValue().get(activeKey(deviceId));
            return active != null && !active.isEmpty();
        } catch (Exception e) {
            LOGGER.warn("[AI-Fusion] active-state read failed deviceId={}: {}",
                    deviceId, e.getMessage());
            return false;
        }
    }

    /**
     * Apply the latest canonical value shown in the device Alarm column.
     * This method is synchronized because event and patrol paths use separate
     * executors but must emit exactly one history point per transition.
     */
    public synchronized void updateAlarmState(String deviceId, String rawAlarm) {
        if (!enabled || deviceId == null || deviceId.trim().isEmpty()) {
            return;
        }

        String alarmCode = normalizeAlarmCodes(rawAlarm);
        String key = activeKey(deviceId);
        try {
            String previous = redis.opsForValue().get(key);
            previous = previous == null ? "" : previous;
            if (alarmCode.equals(previous)) {
                return;
            }

            if (alarmCode.isEmpty()) {
                if (previous.isEmpty()) {
                    return;
                }
                // Keep the active marker until the old window has been erased,
                // so a concurrent clean event cannot append too early.
                timeSeriesBuffer.clear(deviceId);
                redis.delete(key);
                redis.opsForHash().delete(HASH_AI_ALARM, deviceId);
                publishCollecting(deviceId);
                LOGGER.info("[AI-Fusion] cleared deviceId={} previousAlarm={}",
                        deviceId, previous);
                return;
            }

            Double modelScore = readModelScore(deviceId);
            double fusedScore = Math.max(
                    modelScore == null ? Double.NEGATIVE_INFINITY : modelScore,
                    threshold + scoreMargin);

            // Mark active before clearing/publishing so AiAlarmListener stops
            // accepting samples immediately.
            redis.opsForValue().set(key, alarmCode);
            timeSeriesBuffer.clear(deviceId);
            publishAbnormal(deviceId, alarmCode, modelScore, fusedScore);
            LOGGER.warn("[AI-Fusion] rule alarm promoted deviceId={} alarm={} modelScore={} score={} threshold={}",
                    deviceId, alarmCode, modelScore, fusedScore, threshold);
        } catch (Exception e) {
            LOGGER.error("[AI-Fusion] state update failed deviceId={} alarm={}: {}",
                    deviceId, alarmCode, e.getMessage(), e);
        }
    }

    static String normalizeAlarmCodes(String rawAlarm) {
        if (rawAlarm == null || rawAlarm.trim().isEmpty()) {
            return "";
        }
        Set<String> codes = new TreeSet<>();
        for (String value : rawAlarm.split(",")) {
            String code = value == null ? "" : value.trim();
            if (code.isEmpty()) {
                continue;
            }
            String upper = code.toUpperCase(Locale.ROOT);
            if ("00".equals(upper) || "NORMAL".equals(upper)
                    || "NONE".equals(upper) || "正常".equals(code)) {
                continue;
            }
            // Never feed an AI-originated alarm back into AI fusion.
            if ("AI".equals(upper) || upper.startsWith("AI_")) {
                continue;
            }
            codes.add(code);
        }
        return String.join(",", codes);
    }

    private Double readModelScore(String deviceId) {
        try {
            Object raw = redis.opsForHash().get(HASH_AI_RESULT, deviceId);
            if (raw == null) {
                return null;
            }
            JsonNode result = objectMapper.readTree(raw.toString());
            JsonNode scoreNode;
            if ("RULE_FUSION".equals(result.path("source").asText())) {
                scoreNode = result.get("modelScore");
            } else {
                scoreNode = result.get("score");
            }
            return scoreNode != null && scoreNode.isNumber() ? scoreNode.asDouble() : null;
        } catch (Exception e) {
            LOGGER.debug("[AI-Fusion] previous model score unavailable deviceId={}: {}",
                    deviceId, e.getMessage());
            return null;
        }
    }

    private void publishAbnormal(String deviceId, String alarmCode,
                                 Double modelScore, double fusedScore) {
        String modelScoreJson = modelScore == null
                ? "null" : String.format(Locale.US, "%.4f", modelScore);
        String json = String.format(Locale.US,
                "{\"type\":\"AI_RESULT\",\"deviceId\":\"%s\",\"schemaVersion\":\"%s\","
                        + "\"state\":\"ABNORMAL\",\"ready\":true,\"score\":%.4f,\"modelScore\":%s,"
                        + "\"threshold\":%.4f,\"isAbnormal\":true,\"label\":\"rule_fusion\","
                        + "\"source\":\"RULE_FUSION\",\"alarmCode\":\"%s\","
                        + "\"sampleCount\":0,\"requiredSamples\":%d,\"updatedAt\":\"%s\"}",
                jsonEscape(deviceId), FEATURE_SCHEMA, fusedScore, modelScoreJson,
                threshold, jsonEscape(alarmCode), REQUIRED_SAMPLES, Instant.now().toString());
        publish(deviceId, json);
    }

    private void publishCollecting(String deviceId) {
        String json = String.format(Locale.US,
                "{\"type\":\"AI_RESULT\",\"deviceId\":\"%s\",\"schemaVersion\":\"%s\","
                        + "\"state\":\"COLLECTING\",\"ready\":false,\"source\":\"RULE_FUSION_CLEAR\","
                        + "\"sampleCount\":0,\"requiredSamples\":%d,\"updatedAt\":\"%s\"}",
                jsonEscape(deviceId), FEATURE_SCHEMA, REQUIRED_SAMPLES, Instant.now().toString());
        publish(deviceId, json);
    }

    private void publish(String deviceId, String json) {
        redis.opsForHash().put(HASH_AI_RESULT, deviceId, json);
        redis.convertAndSend(CHANNEL_AI_RESULT, json);
    }

    private String activeKey(String deviceId) {
        return ACTIVE_KEY_PREFIX + deviceId;
    }

    private static String jsonEscape(String value) {
        if (value == null) {
            return "";
        }
        return value.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}
