package cn.edu.sztu.elevatormonitor.ai;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;

import java.util.List;
import java.util.Map;

/**
 * AI 推理 HTTP 客户端 — 调用 Python AI 模型服务。
 *
 * <h3>职责</h3>
 * <ul>
 *   <li>封装 POST /predict 请求的构造与发送</li>
 *   <li>解析返回的异常分数、标签</li>
 *   <li>处理网络超时、服务不可用等异常</li>
 * </ul>
 *
 * <h3>调用方</h3>
 * <p>{@link AiAlarmListener} 在攒满时序窗口后调用本客户端。</p>
 *
 * @author ai-integration
 * @since 0.3.0
 */
@Component
public class AiPredictClient {

    private static final Logger LOGGER = LoggerFactory.getLogger(AiPredictClient.class);
    public static final String FEATURE_SCHEMA = "mnk-v2";

    private final RestTemplate restTemplate;

    /** AI 服务地址（默认: http://ai_service:8000） */
    @Value("${ai.service.url:http://127.0.0.1:8000}")
    private String aiServiceUrl;

    public AiPredictClient() {
        this.restTemplate = new RestTemplate();
    }

    /**
     * 调用 AI 推理服务进行异常检测。
     *
     * @param deviceId 设备编号
     * @param sequence 时序特征序列 [时间步数, 5]
     * @return 推理结果；网络异常时返回 null（调用方应降级处理）
     */
    @SuppressWarnings("unchecked")
    public PredictResult predict(String deviceId, List<List<Double>> sequence) {
        if (sequence == null || sequence.isEmpty()) {
            LOGGER.warn("[AI-Client] 空序列, 跳过预测 deviceId={}", deviceId);
            return null;
        }

        String url = aiServiceUrl + "/predict";

        try {
            // 构造请求体
            Map<String, Object> requestBody = new java.util.HashMap<>();
            requestBody.put("deviceId", deviceId);
            requestBody.put("sequence", sequence);
            requestBody.put("schemaVersion", FEATURE_SCHEMA);

            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            HttpEntity<Map<String, Object>> request = new HttpEntity<>(requestBody, headers);

            ResponseEntity<Map> response = restTemplate.postForEntity(url, request, Map.class);

            if (response.getBody() == null) {
                LOGGER.warn("[AI-Client] 响应体为空 deviceId={}", deviceId);
                return null;
            }

            Map<String, Object> body = response.getBody();

            double score = ((Number) body.getOrDefault("score", 0)).doubleValue();
            double threshold = ((Number) body.getOrDefault("threshold", 90.0)).doubleValue();
            boolean isAbnormal = (boolean) body.getOrDefault("is_abnormal", false);
            String label = (String) body.getOrDefault("label", "unknown");

            PredictResult result = new PredictResult(deviceId, score, threshold, isAbnormal, label);

            LOGGER.info("[AI-Client] 推理完成 deviceId={} score={} threshold={} label={}",
                    deviceId, score, threshold, label);

            return result;

        } catch (RestClientException e) {
            LOGGER.error("[AI-Client] AI 服务调用失败 deviceId={} url={}: {}",
                    deviceId, url, e.getMessage());
            return null;
        } catch (Exception e) {
            LOGGER.error("[AI-Client] 未知异常 deviceId={}: {}", deviceId, e.getMessage(), e);
            return null;
        }
    }

    /**
     * 健康检查 — 检测 AI 服务是否可达。
     *
     * @return true=服务正常
     */
    public boolean healthCheck() {
        try {
            ResponseEntity<String> resp = restTemplate.getForEntity(
                    aiServiceUrl + "/health", String.class);
            return resp.getStatusCode().is2xxSuccessful();
        } catch (Exception e) {
            LOGGER.debug("[AI-Client] 健康检查失败: {}", e.getMessage());
            return false;
        }
    }

    // ============================================================
    // 内嵌 DTO
    // ============================================================

    /**
     * AI 推理结果。
     */
    public static class PredictResult {
        private final String deviceId;
        private final double score;
        private final double threshold;
        private final boolean isAbnormal;
        private final String label;

        public PredictResult(String deviceId, double score, double threshold,
                             boolean isAbnormal, String label) {
            this.deviceId = deviceId;
            this.score = score;
            this.threshold = threshold;
            this.isAbnormal = isAbnormal;
            this.label = label;
        }

        public String getDeviceId() { return deviceId; }
        public double getScore() { return score; }
        public double getThreshold() { return threshold; }
        public boolean isAbnormal() { return isAbnormal; }
        public String getLabel() { return label; }
    }
}
