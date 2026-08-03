package cn.edu.sztu.elevatormonitor.entity.repository;

import cn.edu.sztu.elevatormonitor.entity.ElevatorMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Repository;

import java.text.DecimalFormat;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 电梯消息仓库 — 原始实现通过 RestTemplate 直接推送到 Go 前端。
 * 重构后改为向 Redis Pub/Sub 发布消息，实现解耦与削峰。
 */
@Repository
public class ElevatorMessageRepository {

    private static final Logger LOGGER = LoggerFactory.getLogger(ElevatorMessageRepository.class);

    /** Redis Pub/Sub 频道名 */
    public static final String CHANNEL_ELEVATOR_STATUS = "elevator:status";

    private final StringRedisTemplate stringRedisTemplate;

    public ElevatorMessageRepository(StringRedisTemplate stringRedisTemplate) {
        this.stringRedisTemplate = stringRedisTemplate;
    }

    /**
     * 将解析后的电梯数据双写到 Redis（HSET + Pub/Sub）。
     *
     * @param elevatorMessage 已解析的电梯消息对象
     * @param apiUrl          (已废弃) 原 RestTemplate 推送地址，保留以保持调用签名兼容
     * @return true 发布成功，false 发布失败
     */
    public boolean sendToFrontEnd(ElevatorMessage elevatorMessage, String apiUrl) {
        String json = buildJsonPayload(elevatorMessage);
        String deviceId = elevatorMessage.getDeviceId();
        try {
            // 1. HSET — 持久化最新状态，支持按 deviceId 查询
            stringRedisTemplate.opsForHash().put("elevator:status", deviceId, json);

            // 1b. 更新最后消息时间戳，供 ScheduledAlarmChecker 巡检设备离线
            //     （V1 协议路径必需：否则离线检测读不到 lastMessageTime）
            try {
                stringRedisTemplate.opsForHash().put(
                        "elevator:timestamps:" + deviceId, "lastMessageTime",
                        String.valueOf(java.time.Instant.now().getEpochSecond()));
            } catch (Exception tsEx) {
                LOGGER.debug("[Redis] 时间戳更新失败(Redis不可用?): {}", tsEx.getMessage());
            }

            // 2. PUBLISH — 实时推送给 Go WebSocket 订阅者
            stringRedisTemplate.convertAndSend(CHANNEL_ELEVATOR_STATUS, json);
            LOGGER.debug("[Redis] 消息已发布到频道 {}, deviceId={}",
                    CHANNEL_ELEVATOR_STATUS, deviceId);
            return true;
        } catch (Exception e) {
            LOGGER.error("[Redis] 消息发布失败, channel={}, deviceId={}",
                    CHANNEL_ELEVATOR_STATUS, deviceId, e);
            return false;
        }
    }

    /**
     * 构造 JSON 负载 — 字段命名与 Go 端 infopack 结构体保持一致，
     * 确保前端 WebSocket 接收的 JSON 格式不变。
     */
    private String buildJsonPayload(ElevatorMessage msg) {
        DecimalFormat df = new DecimalFormat("0.00");
        // 使用 LinkedHashMap 保证字段顺序与现有 infopack 一致
        Map<String, String> payload = new LinkedHashMap<>();
        payload.put("Device",    nvl(msg.getDeviceId()));
        payload.put("Status",    nvl(msg.getElevatorStatus()));
        payload.put("Floor",     nvl(msg.getCurrentFloor()));
        payload.put("ToFloor",   nvl(msg.getTargetFloor()));
        payload.put("Direction", nvl(msg.getDirection()));
        payload.put("Door",      nvl(msg.getDoorStatus()));
        payload.put("Passenger", nvl(msg.getPassenger()));
        payload.put("Speed",     formatSpeed(msg.getSpeed()));
        payload.put("Alarm",     nvl(msg.getAlarm()));
        payload.put("Runtime",   nvl(msg.getRuntime()));
        payload.put("Distance",  df.format(msg.getDistance() * 2.8) + "米");
        payload.put("Times",     nvl(msg.getTimes()) + "次");

        // 手工拼接 JSON，避免引入额外序列化依赖
        StringBuilder sb = new StringBuilder("{");
        boolean first = true;
        for (Map.Entry<String, String> e : payload.entrySet()) {
            if (!first) sb.append(",");
            sb.append("\"").append(e.getKey()).append("\":\"")
              .append(escapeJson(e.getValue())).append("\"");
            first = false;
        }
        sb.append("}");
        return sb.toString();
    }

    private static String nvl(Object v) {
        return v == null ? "" : v.toString();
    }

    private static String formatSpeed(double speed) {
        if (speed < 0) return "";
        return String.format("%.2f", speed) + "m/s";
    }

    private static String escapeJson(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}
