package cn.edu.sztu.elevatormonitor.services;

import cn.edu.sztu.elevatormonitor.ai.AiRuleFusionService;
import cn.edu.sztu.elevatormonitor.alarm.AlarmRuleEngine;
import cn.edu.sztu.elevatormonitor.entity.AlarmEvent;
import cn.edu.sztu.elevatormonitor.entity.ElevatorMessage;
import cn.edu.sztu.elevatormonitor.entity.repository.AlarmEventJpaRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 告警服务 — 异步评估 + 持久化 + 实时推送。
 *
 * 设计原则:
 *   1. @Async 确保告警评估不阻塞实时数据链路
 *   2. 告警事件双写: JPA → MySQL + Redis Pub → 前端
 *   3. 告警频道: elevator:alarm
 */
@Service
public class AlarmService {

    private static final Logger LOGGER = LoggerFactory.getLogger(AlarmService.class);

    /** Redis 告警频道 */
    public static final String CHANNEL_ELEVATOR_ALARM = "elevator:alarm";

    private final AlarmRuleEngine engine;
    private final AlarmEventJpaRepository alarmRepo;
    private final StringRedisTemplate stringRedisTemplate;
    private final AiRuleFusionService aiRuleFusionService;

    public AlarmService(AlarmRuleEngine engine,
                        AlarmEventJpaRepository alarmRepo,
                        StringRedisTemplate stringRedisTemplate,
                        AiRuleFusionService aiRuleFusionService) {
        this.engine = engine;
        this.alarmRepo = alarmRepo;
        this.stringRedisTemplate = stringRedisTemplate;
        this.aiRuleFusionService = aiRuleFusionService;
        LOGGER.info("[Alarm] 告警服务初始化完成, Redis频道={}", CHANNEL_ELEVATOR_ALARM);
    }

    /**
     * 异步评估告警、持久化、推送前端。
     * 调用方无需等待返回。
     *
     * @param msg 已解析的 ElevatorMessage
     */
    @Async("alarmExecutor")
    public void evaluateAsync(ElevatorMessage msg) {
        LOGGER.info("[Alarm] evaluateAsync 被调用, deviceId={}, floor={}, dir={}",
                msg.getDeviceId(), msg.getCurrentFloor(), msg.getDirection());
        try {
            List<AlarmEvent> events = engine.evaluate(msg);
            LOGGER.info("[Alarm] 规则评估完成, deviceId={}, 触发事件数={}",
                    msg.getDeviceId(), events.size());
            for (AlarmEvent event : events) {
                // 1. 持久化到 MySQL
                try {
                    alarmRepo.save(event);
                } catch (Exception e) {
                    LOGGER.error("[Alarm] 告警入库失败, deviceId={}, rule={}",
                            event.getDeviceId(), event.getRuleName(), e);
                }

                // 2. 实时推送到 Redis Pub/Sub → Go → WebSocket 前端
                try {
                    String json = event.toJson();
                    stringRedisTemplate.convertAndSend(CHANNEL_ELEVATOR_ALARM, json);
                    LOGGER.debug("[Alarm] 告警已推送: {}", json);
                } catch (Exception e) {
                    LOGGER.error("[Alarm] 告警推送失败, deviceId={}, rule={}",
                            event.getDeviceId(), event.getRuleName(), e);
                }
            }

            // 3. 将触发的告警回写到 status HSET，让前端告警灯实时联动
            if (!events.isEmpty()) {
                updateStatusAlarm(msg.getDeviceId(), events);
            }
        } catch (Exception e) {
            LOGGER.error("[Alarm] 告警评估异常, deviceId={}", msg.getDeviceId(), e);
        }
    }

    /**
     * 回写 elevator:status HSET 的 Alarm 字段并重新 PUBLISH，
     * 使前端通过正常的 status 消息即可看到告警灯变化。
     *
     * <p>修复 (2026-08-03):
     * <ul>
     *   <li>只保留 FIRED（触发）的规则；CLEARED（恢复）的规则必须从告警字段移除，
     *       否则告警灯会一直残留不熄灭</li>
     *   <li>保留非规则引擎产生的事件告警（如 LEVELING_TIMEOUT），避免被整体覆盖丢失</li>
     * </ul>
     */
    private void updateStatusAlarm(String deviceId, List<AlarmEvent> events) {
        try {
            // 1. 只收集本轮 FIRED 的规则
            java.util.Set<String> firedRules = new java.util.LinkedHashSet<>();
            for (AlarmEvent ae : events) {
                if (!AlarmEvent.TYPE_FIRED.equals(ae.getEventType())) {
                    continue; // CLEARED 规则不写入，保证告警能正常熄灭
                }
                firedRules.add(ae.getRuleName());
            }

            Object raw = stringRedisTemplate.opsForHash().get("elevator:status", deviceId);
            if (raw != null) {
                String json = raw.toString();
                String currentAlarm = extractAlarmField(json);

                // 2. 规则引擎已知的告警码集合，用于区分"事件告警"与"规则告警"
                java.util.Set<String> ruleNames = new java.util.HashSet<>();
                for (cn.edu.sztu.elevatormonitor.alarm.AlarmRule r : engine.getRules()) {
                    ruleNames.add(r.ruleName());
                }

                // 3. 保留事件告警（非规则引擎产生的，如 LEVELING_TIMEOUT）
                java.util.Set<String> eventAlarms = new java.util.LinkedHashSet<>();
                if (!currentAlarm.isEmpty()) {
                    for (String s : currentAlarm.split(",")) {
                        String t = s.trim();
                        if (t.isEmpty() || ruleNames.contains(t)) {
                            continue; // 旧规则告警由本轮 firedRules 决定
                        }
                        eventAlarms.add(t);
                    }
                }

                // 4. 合并事件告警 + 本轮 FIRED 规则告警
                java.util.Set<String> merged = new java.util.LinkedHashSet<>(eventAlarms);
                merged.addAll(firedRules);
                String alarmValue = String.join(",", merged);

                String updated = json.replaceFirst("\"Alarm\":\"[^\"]*\"",
                        "\"Alarm\":\"" + alarmValue + "\"");
                stringRedisTemplate.opsForHash().put("elevator:status", deviceId, updated);
                stringRedisTemplate.convertAndSend("elevator:status", updated);
                aiRuleFusionService.updateAlarmState(deviceId, alarmValue);
                LOGGER.info("[Alarm] status Alarm 已更新: deviceId={}, alarm={}", deviceId, alarmValue);
            }
        } catch (Exception e) {
            LOGGER.error("[Alarm] 更新 status Alarm 失败: deviceId={}", deviceId, e);
        }
    }

    /** 从 status JSON 中提取 Alarm 字段值（无该字段返回空串） */
    private String extractAlarmField(String json) {
        java.util.regex.Matcher m = java.util.regex.Pattern
                .compile("\"Alarm\":\"([^\"]*)\"")
                .matcher(json);
        return m.find() ? m.group(1) : "";
    }
}
