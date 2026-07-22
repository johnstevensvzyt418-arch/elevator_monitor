package cn.edu.sztu.elevatormonitor.event;

import cn.edu.sztu.elevatormonitor.domain.event.ElevatorEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.EventListener;
import org.springframework.core.annotation.Order;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import javax.annotation.PostConstruct;
import java.text.DecimalFormat;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Redis 状态发布处理器 — 监听 {@link ElevatorEvent}，将电梯状态推送到 Redis。
 *
 * <h3>双写策略</h3>
 * <ul>
 *   <li><b>HSET elevator:status {deviceId} {json}</b> — 持久化最新状态，支持 HTTP 查询</li>
 *   <li><b>PUBLISH elevator:status {json}</b> — 实时推送，Go 前端通过 WebSocket 消费</li>
 *   <li>JSON 格式与现有 infopack 结构体保持一致，确保前端兼容</li>
 *   <li>@Async 确保 Redis 写入不阻塞事件总线</li>
 * </ul>
 *
 * @author architecture-v2
 * @since 0.2.0
 */
@Component
public class RedisHandler {

    private static final Logger LOGGER = LoggerFactory.getLogger(RedisHandler.class);

    /** Redis Hash 键名 — 存储所有设备最新状态 */
    private static final String HASH_KEY_ELEVATOR_STATUS = "elevator:status";

    /** Redis Pub/Sub 频道名 */
    private static final String CHANNEL_ELEVATOR_STATUS = "elevator:status";

    /** 设备时间戳 Hash 前缀，供 ScheduledAlarmChecker 巡检使用 */
    private static final String HASH_TIMESTAMPS_PREFIX = "elevator:timestamps:";

    private final StringRedisTemplate stringRedisTemplate;

    public RedisHandler(StringRedisTemplate stringRedisTemplate) {
        this.stringRedisTemplate = stringRedisTemplate;
    }

    /**
     * 启动时验证 Bean 是否被 Spring 容器正确加载。
     */
    @PostConstruct
    public void init() {
        LOGGER.info("[RedisHandler] Bean 初始化完成, StringRedisTemplate={}", 
                stringRedisTemplate != null ? "已注入" : "NULL");
    }

    /**
     * 监听电梯事件，同步双写到 Redis（HSET + Pub/Sub）。
     * {@code @Order(2)} 确保 AlarmHandler 先完成规则评估，本处理器再合并告警并发布。
     */
    @EventListener
    @Order(2)
    public void onElevatorEvent(ElevatorEvent event) {
        LOGGER.info("[RedisHandler] 收到事件: eventId={}, deviceId={}", 
                event.getEventId(), event.getDeviceId());
        try {
            String json = buildJsonPayload(event);
            String deviceId = event.getDeviceId();

            // 1. HSET — 持久化最新状态，支持按 deviceId 查询
            stringRedisTemplate.opsForHash().put(HASH_KEY_ELEVATOR_STATUS, deviceId, json);
            LOGGER.debug("[RedisHandler] HSET elevator:status {} => OK", deviceId);

            // 1b. 更新最后消息时间戳，供 ScheduledAlarmChecker 巡检设备离线
            String tsKey = HASH_TIMESTAMPS_PREFIX + deviceId;
            stringRedisTemplate.opsForHash().put(tsKey, "lastMessageTime",
                    String.valueOf(Instant.now().getEpochSecond()));

            // 2. PUBLISH — 实时推送给 Go WebSocket 订阅者
            stringRedisTemplate.convertAndSend(CHANNEL_ELEVATOR_STATUS, json);
            LOGGER.debug("[RedisHandler] PUBLISH elevator:status => OK, deviceId={}", deviceId);
        } catch (Exception e) {
            LOGGER.error("[RedisHandler] Redis 写入异常: eventId={}, deviceId={}",
                    event.getEventId(), event.getDeviceId(), e);
        }
    }

    /**
     * 构造 JSON 负载 — 与 Go 端 infopack 结构体保持一致。
     */
    private String buildJsonPayload(ElevatorEvent e) {
        DecimalFormat df = new DecimalFormat("0.00");
        Map<String, String> payload = new LinkedHashMap<>();
        payload.put("Device",    nvl(e.getDeviceId()));
        payload.put("Status",    nvl(e.getElevatorStatus()));
        payload.put("Floor",     nvl(e.getCurrentFloor()));
        payload.put("ToFloor",   nvl(e.getTargetFloor()));
        payload.put("Direction", nvl(e.getDirection()));
        payload.put("Door",      nvl(e.getDoorStatus()));
        payload.put("Passenger", nvl(e.getPassenger()));
        payload.put("Speed",     formatSpeed(e.getSpeed()));
        payload.put("Alarm",     mergeAlarms(e.getDeviceId(), nvl(e.getAlarm())));
        payload.put("Runtime",   nvl(e.getRuntime()));
        payload.put("Distance",  formatDistance(e.getDistance()));
        payload.put("Times",     formatTimes(e.getTimes()));

        StringBuilder sb = new StringBuilder("{");
        boolean first = true;
        for (Map.Entry<String, String> entry : payload.entrySet()) {
            if (!first) sb.append(",");
            sb.append("\"").append(entry.getKey()).append("\":\"")
              .append(escapeJson(entry.getValue())).append("\"");
            first = false;
        }
        sb.append("}");
        return sb.toString();
    }

    private static String nvl(Object v) {
        return v == null ? "" : v.toString();
    }

    private static String formatSpeed(double speed) {
        if (speed < 0) return "";  // 未计算
        return String.format("%.2f", speed) + "m/s";
    }

    private static String formatDistance(int distance) {
        if (distance < 0) return "";  // 未填充
        return String.format("%.2f", distance * 2.8) + "米";
    }

    private static String formatTimes(int times) {
        if (times < 0) return "";  // 未填充
        return times + "次";
    }

    /**
     * 合并事件路径告警（如 LEVELING_TIMEOUT）与规则引擎告警 + 巡检告警。
     * 从两个独立的 marker key 读取，互不覆盖。
     */
    private String mergeAlarms(String deviceId, String eventAlarm) {
        try {
            // 规则告警（AlarmHandler 写入）
            Object ruleObj = stringRedisTemplate.opsForHash()
                    .get("elevator:status", deviceId + AlarmHandler.MARKER_RULE_ALARM);
            String ruleAlarm = (ruleObj != null) ? ruleObj.toString() : "";

            // 巡检告警（ScheduledAlarmChecker 写入）
            Object patrolObj = stringRedisTemplate.opsForHash()
                    .get("elevator:status", deviceId + ":patrol_alarm");
            String patrolAlarm = (patrolObj != null) ? patrolObj.toString() : "";

            java.util.LinkedHashSet<String> merged = new java.util.LinkedHashSet<>();
            if (!eventAlarm.isEmpty()) {
                for (String s : eventAlarm.split(",")) {
                    String trimmed = s.trim();
                    if (!trimmed.isEmpty()) merged.add(trimmed);
                }
            }
            if (!ruleAlarm.isEmpty()) {
                for (String s : ruleAlarm.split(",")) {
                    String trimmed = s.trim();
                    if (!trimmed.isEmpty()) merged.add(trimmed);
                }
            }
            if (!patrolAlarm.isEmpty()) {
                for (String s : patrolAlarm.split(",")) {
                    String trimmed = s.trim();
                    if (!trimmed.isEmpty()) merged.add(trimmed);
                }
            }
            return String.join(",", merged);
        } catch (Exception e) {
            LOGGER.warn("[RedisHandler] 告警合并失败 deviceId={}: {}", deviceId, e.getMessage());
            return eventAlarm;
        }
    }

    private static String escapeJson(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}
