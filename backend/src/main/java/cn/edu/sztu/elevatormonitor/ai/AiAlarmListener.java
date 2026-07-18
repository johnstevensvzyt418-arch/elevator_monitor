package cn.edu.sztu.elevatormonitor.ai;

import cn.edu.sztu.elevatormonitor.domain.event.ElevatorEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.EventListener;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;

import javax.annotation.PostConstruct;
import java.time.Instant;
import java.util.List;
import java.util.Locale;

/**
 * 监听电梯事件，维护推理窗口，并把每一次 AI 状态持续发布给监控前端。
 * 规则告警仍由 AlarmHandler 独立处理；本监听器只负责 AI 异常检测。
 */
@Component
public class AiAlarmListener {

    private static final Logger LOGGER = LoggerFactory.getLogger(AiAlarmListener.class);

    /** 只保存当前处于异常状态的 AI 告警，兼容已有告警流程。 */
    private static final String HASH_AI_ALARM = "elevator:ai_alarm";
    private static final String CHANNEL_AI_ALARM = "elevator:alarm";

    /** 保存并发布所有 AI 状态，包括采集中、正常、异常和服务不可用。 */
    private static final String HASH_AI_RESULT = "elevator:ai_result";
    private static final String CHANNEL_AI_RESULT = "elevator:ai_result";

    private static final int WINDOW_MIN_SIZE = 10;
    private static final String FEATURE_SCHEMA = AiPredictClient.FEATURE_SCHEMA;

    private final TimeSeriesBuffer timeSeriesBuffer;
    private final AiPredictClient aiPredictClient;
    private final StringRedisTemplate redis;

    public AiAlarmListener(TimeSeriesBuffer timeSeriesBuffer,
                           AiPredictClient aiPredictClient,
                           StringRedisTemplate redis) {
        this.timeSeriesBuffer = timeSeriesBuffer;
        this.aiPredictClient = aiPredictClient;
        this.redis = redis;
    }

    @PostConstruct
    public void init() {
        LOGGER.info("[AI-Listener] initialized: buffer={}, client={}, redis={}",
                timeSeriesBuffer != null, aiPredictClient != null, redis != null);
    }

    @EventListener
    @Async("aiExecutor")
    public void onElevatorEvent(ElevatorEvent event) {
        try {
            String deviceId = event.getDeviceId();
            double[] features = extractFeatures(event);
            timeSeriesBuffer.append(deviceId, features, WINDOW_MIN_SIZE * 2);

            long currentSize = timeSeriesBuffer.size(deviceId);
            LOGGER.debug("[AI-Listener] buffer deviceId={} size={}/{}",
                    deviceId, currentSize, WINDOW_MIN_SIZE);

            if (currentSize >= WINDOW_MIN_SIZE) {
                performInference(deviceId);
            } else {
                publishCollectingState(deviceId, currentSize);
            }
        } catch (Exception e) {
            LOGGER.error("[AI-Listener] event processing failed: eventId={}, deviceId={}",
                    event.getEventId(), event.getDeviceId(), e);
        }
    }

    private double[] extractFeatures(ElevatorEvent event) {
        double[] features = new double[5];
        features[0] = TimeSeriesBuffer.parseDoorStatus(event.getDoorStatus());
        features[1] = TimeSeriesBuffer.parseFloor(event.getCurrentFloor());
        features[2] = TimeSeriesBuffer.parseFloor(event.getTargetFloor());
        if (features[2] <= 0) {
            features[2] = features[1];
        }
        features[3] = TimeSeriesBuffer.parseDirection(event.getDirection());
        double speed = event.getSpeed();
        features[4] = Double.isFinite(speed) ? Math.max(0.0, speed) : 0.0;
        return features;
    }

    private void performInference(String deviceId) {
        List<List<Double>> sequence = timeSeriesBuffer.readWindow(deviceId, WINDOW_MIN_SIZE);
        if (sequence.isEmpty()) {
            LOGGER.debug("[AI-Listener] incomplete inference window deviceId={}", deviceId);
            return;
        }

        AiPredictClient.PredictResult result = aiPredictClient.predict(deviceId, sequence);
        if (result == null) {
            LOGGER.warn("[AI-Listener] inference unavailable deviceId={}", deviceId);
            publishUnavailableState(deviceId);
            return;
        }

        publishInferenceResult(deviceId, result);
        if (result.isAbnormal()) {
            writeAiAlarm(deviceId, result);
        } else {
            clearAiAlarm(deviceId);
        }
    }

    private void writeAiAlarm(String deviceId, AiPredictClient.PredictResult result) {
        try {
            String alarmJson = String.format(Locale.US,
                    "{\"type\":\"AI\",\"deviceId\":\"%s\",\"schemaVersion\":\"%s\",\"score\":%.4f,\"threshold\":%.4f,\"isAbnormal\":true,\"label\":\"%s\",\"updatedAt\":\"%s\"}",
                    jsonEscape(deviceId), FEATURE_SCHEMA, result.getScore(), result.getThreshold(),
                    jsonEscape(result.getLabel()), Instant.now().toString());
            redis.opsForHash().put(HASH_AI_ALARM, deviceId, alarmJson);
            redis.convertAndSend(CHANNEL_AI_ALARM, alarmJson);
            LOGGER.info("[AI-Listener] abnormal deviceId={} score={} threshold={}",
                    deviceId, result.getScore(), result.getThreshold());
        } catch (Exception e) {
            LOGGER.error("[AI-Listener] alarm write failed deviceId={}: {}", deviceId, e.getMessage());
        }
    }

    private void clearAiAlarm(String deviceId) {
        try {
            if (Boolean.TRUE.equals(redis.opsForHash().hasKey(HASH_AI_ALARM, deviceId))) {
                redis.opsForHash().delete(HASH_AI_ALARM, deviceId);
                LOGGER.info("[AI-Listener] alarm cleared deviceId={}", deviceId);
            }
        } catch (Exception e) {
            LOGGER.error("[AI-Listener] alarm clear failed deviceId={}: {}", deviceId, e.getMessage());
        }
    }

    private void publishCollectingState(String deviceId, long sampleCount) {
        String json = String.format(Locale.US,
                "{\"type\":\"AI_RESULT\",\"deviceId\":\"%s\",\"schemaVersion\":\"%s\",\"state\":\"COLLECTING\",\"ready\":false,\"sampleCount\":%d,\"requiredSamples\":%d,\"updatedAt\":\"%s\"}",
                jsonEscape(deviceId), FEATURE_SCHEMA, sampleCount, WINDOW_MIN_SIZE, Instant.now().toString());
        publishAiResult(deviceId, json);
    }

    private void publishInferenceResult(String deviceId, AiPredictClient.PredictResult result) {
        String state = result.isAbnormal() ? "ABNORMAL" : "NORMAL";
        String json = String.format(Locale.US,
                "{\"type\":\"AI_RESULT\",\"deviceId\":\"%s\",\"schemaVersion\":\"%s\",\"state\":\"%s\",\"ready\":true,\"score\":%.4f,\"threshold\":%.4f,\"isAbnormal\":%s,\"label\":\"%s\",\"sampleCount\":%d,\"requiredSamples\":%d,\"updatedAt\":\"%s\"}",
                jsonEscape(deviceId), FEATURE_SCHEMA, state, result.getScore(), result.getThreshold(),
                result.isAbnormal(), jsonEscape(result.getLabel()), WINDOW_MIN_SIZE,
                WINDOW_MIN_SIZE, Instant.now().toString());
        publishAiResult(deviceId, json);
    }

    private void publishUnavailableState(String deviceId) {
        String json = String.format(Locale.US,
                "{\"type\":\"AI_RESULT\",\"deviceId\":\"%s\",\"schemaVersion\":\"%s\",\"state\":\"UNAVAILABLE\",\"ready\":false,\"sampleCount\":%d,\"requiredSamples\":%d,\"updatedAt\":\"%s\"}",
                jsonEscape(deviceId), FEATURE_SCHEMA, WINDOW_MIN_SIZE, WINDOW_MIN_SIZE, Instant.now().toString());
        publishAiResult(deviceId, json);
    }

    private void publishAiResult(String deviceId, String json) {
        try {
            redis.opsForHash().put(HASH_AI_RESULT, deviceId, json);
            redis.convertAndSend(CHANNEL_AI_RESULT, json);
        } catch (Exception e) {
            LOGGER.error("[AI-Listener] AI result publish failed deviceId={}: {}", deviceId, e.getMessage());
        }
    }

    private String jsonEscape(String value) {
        if (value == null) {
            return "";
        }
        return value.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}
