package cn.edu.sztu.elevatormonitor.alarm;

import cn.edu.sztu.elevatormonitor.services.LevelingTrackingService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

/**
 * 后台定时巡检任务 — 解决"事件驱动告警"在设备断连时无法触发的根本缺陷。
 *
 * <h3>背景</h3>
 * <p>所有告警规则都是"被动触发"的（收到消息 → 执行检测）。如果嵌入式设备
 * 故障/MQTT断连导致心跳停止，时间类告警（困人、门超时、长时间闲置）将
 * 永远不会触发。本巡检器填补了这个缺口。</p>
 *
 * <h3>巡检周期</h3>
 * <p>每 10 秒扫描一次所有在线设备（来自 elevator:status），检查：</p>
 * <ol>
 *   <li><b>设备离线</b> — 最后心跳超过阈值</li>
 *   <li><b>平层超时（困人）</b> — 开门+平层状态持续过久</li>
 *   <li><b>门未关超时</b> — 门非关门到位状态持续过久</li>
 *   <li><b>长时间闲置</b> — 非平层状态下楼层未变化过久</li>
 * </ol>
 *
 * <h3>Redis 时间戳结构</h3>
 * <pre>
 *   HSET elevator:timestamps:{deviceId}
 *     lastMessageTime    → epoch秒（最后收到消息的时间）
 *     lastDoorClosedTime → epoch秒（最后关门到位的时间）
 *     lastMoveTime       → epoch秒（最后楼层变化的时间）
 *     lastFloor          → 当前楼层（用于判断楼层是否变化）
 * </pre>
 *
 * @author scheduled-checker
 * @since 0.3.1
 */
@Component
public class ScheduledAlarmChecker {

    private static final Logger LOGGER = LoggerFactory.getLogger(ScheduledAlarmChecker.class);

    private static final String HASH_STATUS = "elevator:status";
    private static final String HASH_TIMESTAMPS_PREFIX = "elevator:timestamps:";
    private static final String CHANNEL_STATUS = "elevator:status";
    private static final String CHANNEL_ALARM = "elevator:alarm";

    /** 设备离线阈值（秒），超过此时间未收到心跳视为离线 */
    @Value("${alarm.device-offline.threshold-seconds:120}")
    private int offlineThresholdSeconds;

    /** 门未关超时阈值（秒） */
    @Value("${alarm.door-open.threshold-seconds:20}")
    private int doorOpenThresholdSeconds;

    /** 长时间闲置阈值（秒） */
    @Value("${alarm.long-idle.threshold-seconds:60}")
    private int longIdleThresholdSeconds;

    /** 巡检告警标记 key（HSET 子键），独立于规则告警，互不覆盖 */
    private static final String MARKER_PATROL_ALARM = ":patrol_alarm";

    private final StringRedisTemplate stringRedisTemplate;
    private final LevelingTrackingService levelingTrackingService;
    private final ObjectMapper objectMapper;

    public ScheduledAlarmChecker(StringRedisTemplate stringRedisTemplate,
                                 LevelingTrackingService levelingTrackingService) {
        this.stringRedisTemplate = stringRedisTemplate;
        this.levelingTrackingService = levelingTrackingService;
        this.objectMapper = new ObjectMapper();
        LOGGER.info("[巡检] 定时巡检器初始化完成, 周期=10s, 离线阈值={}s, 门超时={}s, 闲置={}s",
                offlineThresholdSeconds, doorOpenThresholdSeconds, longIdleThresholdSeconds);
    }

    /**
     * 每 10 秒执行一次全设备巡检。
     */
    @Scheduled(fixedDelay = 10_000)
    public void patrol() {
        try {
            Map<Object, Object> allStatus = stringRedisTemplate.opsForHash().entries(HASH_STATUS);
            if (allStatus == null || allStatus.isEmpty()) {
                return; // 无设备在线
            }

            long nowSec = Instant.now().getEpochSecond();
            int checkedCount = 0;
            int alarmCount = 0;

            for (Map.Entry<Object, Object> entry : allStatus.entrySet()) {
                String deviceId = entry.getKey().toString();
                // 跳过辅助字段（如 alarm 子字段）
                if (deviceId.contains(":")) continue;

                String json = entry.getValue().toString();
                checkedCount++;

                try {
                    JsonNode node = objectMapper.readTree(json);
                    Set<String> patrolAlarms = new LinkedHashSet<>();  // 本轮触发的巡检告警

                    // 更新/读取设备时间戳
                    String door = node.has("Door") ? node.get("Door").asText() : "";
                    String floor = node.has("Floor") ? node.get("Floor").asText() : "";
                    String targetFloor = node.has("ToFloor") ? node.get("ToFloor").asText() : "";
                    String direction = node.has("Direction") ? node.get("Direction").asText() : "";
                    String passenger = node.has("Passenger") ? node.get("Passenger").asText() : "";
                    String timestampsKey = HASH_TIMESTAMPS_PREFIX + deviceId;

                    // ---- 检查1: 设备离线 ----
                    String lastMsgStr = (String) stringRedisTemplate.opsForHash()
                            .get(timestampsKey, "lastMessageTime");
                    if (lastMsgStr != null) {
                        long lastMsgSec = Long.parseLong(lastMsgStr);
                        long offlineSec = nowSec - lastMsgSec;
                        if (offlineSec >= offlineThresholdSeconds) {
                            LOGGER.warn("[巡检] 设备离线: deviceId={}, 离线{}s", deviceId, offlineSec);
                            patrolAlarms.add("DEVICE_OFFLINE");
                            triggerAlarm(deviceId, patrolAlarms,
                                    "设备离线" + offlineSec + "秒", floor, node);
                            alarmCount++;
                            continue; // 已离线，跳过其他检查
                        }
                    }

                    // ---- 检查2: 困人检测（平层+有乘客+门打不开超时） ----
                    String levelingAlarm = levelingTrackingService.checkLevelingTimeout(
                            deviceId, floor, targetFloor, door, passenger);
                    if (levelingAlarm != null) {
                        LOGGER.warn("[巡检] 困人告警: deviceId={}, alarm={}", deviceId, levelingAlarm);
                        patrolAlarms.add(levelingAlarm);
                        alarmCount++;
                    }

                    // ---- 检查3: 门未关超时 ----
                    if (!"00".equals(door) && !door.isEmpty()) {
                        updateTimestampIfDoorClosed(timestampsKey, door, nowSec);
                        String lastClosedStr = (String) stringRedisTemplate.opsForHash()
                                .get(timestampsKey, "lastDoorClosedTime");
                        if (lastClosedStr != null) {
                            long lastClosedSec = Long.parseLong(lastClosedStr);
                            long openSec = nowSec - lastClosedSec;
                            if (openSec >= doorOpenThresholdSeconds) {
                                LOGGER.warn("[巡检] 门未关超时: deviceId={}, 持续{}s", deviceId, openSec);
                                patrolAlarms.add("DOOR_OPEN_TOO_LONG");
                                alarmCount++;
                            }
                        }
                    } else if ("00".equals(door)) {
                        updateTimestampIfDoorClosed(timestampsKey, door, nowSec);
                    }

                    // ---- 检查4: 长时间闲置 ----
                    if (!"00".equals(direction) && !direction.isEmpty()) {
                        updateTimestampIfMoved(timestampsKey, floor, nowSec);
                        String lastMoveStr = (String) stringRedisTemplate.opsForHash()
                                .get(timestampsKey, "lastMoveTime");
                        if (lastMoveStr != null) {
                            long lastMoveSec = Long.parseLong(lastMoveStr);
                            long idleSec = nowSec - lastMoveSec;
                            if (idleSec >= longIdleThresholdSeconds) {
                                LOGGER.warn("[巡检] 长时间闲置: deviceId={}, 持续{}s", deviceId, idleSec);
                                patrolAlarms.add("LONG_IDLE");
                                alarmCount++;
                            }
                        }
                    }

                    // 根据本轮巡检结果更新 marker key 和 status（含清除已解除的告警）
                    triggerAlarm(deviceId, patrolAlarms, "", floor, node);

                } catch (Exception e) {
                    LOGGER.error("[巡检] 设备 {} 巡检异常", deviceId, e);
                }
            }

            if (checkedCount > 0) {
                LOGGER.debug("[巡检] 本轮完成: 检查{}台, 告警{}条", checkedCount, alarmCount);
            }
        } catch (Exception e) {
            LOGGER.error("[巡检] 定时巡检执行异常", e);
        }
    }

    // ==================== 时间戳更新 ====================

    private void updateTimestampIfDoorClosed(String key, String door, long nowSec) {
        if ("00".equals(door)) {
            stringRedisTemplate.opsForHash().put(key, "lastDoorClosedTime", String.valueOf(nowSec));
        }
    }

    private void updateTimestampIfMoved(String key, String floor, long nowSec) {
        String lastFloor = (String) stringRedisTemplate.opsForHash().get(key, "lastFloor");
        if (lastFloor == null || !lastFloor.equals(floor)) {
            stringRedisTemplate.opsForHash().put(key, "lastMoveTime", String.valueOf(nowSec));
            stringRedisTemplate.opsForHash().put(key, "lastFloor", floor);
        }
    }

    // ==================== 告警触发 ====================

    /**
     * 触发/更新巡检告警：根据当前触发的告警集合更新 marker key 和 status JSON。
     * 若集合为空表示所有巡检告警已解除，会清除 marker 和 status 中的巡检告警。
     */
    private void triggerAlarm(String deviceId, Set<String> patrolAlarms, String desc,
                               String floor, JsonNode node) {
        try {
            String newAlarm = String.join(",", patrolAlarms);

            // 更新 marker key（核心：RedisHandler.mergeAlarms 读取此 key）
            stringRedisTemplate.opsForHash().put(HASH_STATUS, deviceId + MARKER_PATROL_ALARM, newAlarm);

            // 保留事件路径的告警（如 LEVELING_TIMEOUT），只替换巡检告警部分
            // 读取当前 status 中的 Alarm 字段，分离事件告警和巡检告警
            String currentAlarm = node.has("Alarm") ? node.get("Alarm").asText() : "";
            Set<String> eventAlarms = new LinkedHashSet<>();
            if (!currentAlarm.isEmpty()) {
                for (String s : currentAlarm.split(",")) {
                    String trimmed = s.trim();
                    if (trimmed.isEmpty()) continue;
                    // 巡检告警码列表
                    if ("DEVICE_OFFLINE".equals(trimmed) || "LEVELING_TIMEOUT".equals(trimmed)
                            || "DOOR_OPEN_TOO_LONG".equals(trimmed) || "LONG_IDLE".equals(trimmed)) {
                        continue; // 跳过旧的巡检告警，由 patrolAlarms 替换
                    }
                    eventAlarms.add(trimmed);
                }
            }
            // 合并事件告警 + 本轮巡检告警
            Set<String> merged = new LinkedHashSet<>(eventAlarms);
            merged.addAll(patrolAlarms);
            String finalAlarm = String.join(",", merged);

            // 重建 JSON
            StringBuilder sb = new StringBuilder("{");
            java.util.Iterator<String> fieldNames = node.fieldNames();
            boolean first = true;
            while (fieldNames.hasNext()) {
                String fn = fieldNames.next();
                if (!first) sb.append(",");
                sb.append("\"").append(fn).append("\":\"");
                if ("Alarm".equals(fn)) {
                    sb.append(escapeJson(finalAlarm));
                } else {
                    sb.append(escapeJson(node.get(fn).asText()));
                }
                sb.append("\"");
                first = false;
            }
            sb.append("}");

            String updatedJson = sb.toString();
            stringRedisTemplate.opsForHash().put(HASH_STATUS, deviceId, updatedJson);
            stringRedisTemplate.convertAndSend(CHANNEL_STATUS, updatedJson);

            // 推送告警事件（仅当有新告警触发时）
            if (!patrolAlarms.isEmpty()) {
                String alarmJson = "{\"DeviceId\":\"" + deviceId + "\",\"RuleName\":\""
                        + newAlarm + "\",\"Description\":\"" + desc + "\",\"Floor\":\""
                        + floor + "\"}";
                stringRedisTemplate.convertAndSend(CHANNEL_ALARM, alarmJson);
            }

            LOGGER.info("[巡检] 告警已更新: deviceId={}, alarms={}", deviceId, finalAlarm);
        } catch (Exception e) {
            LOGGER.error("[巡检] 告警推送失败: deviceId={}, alarms={}", deviceId, patrolAlarms, e);
        }
    }

    private static String escapeJson(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}
