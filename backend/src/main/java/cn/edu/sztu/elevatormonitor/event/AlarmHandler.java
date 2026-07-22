package cn.edu.sztu.elevatormonitor.event;

import cn.edu.sztu.elevatormonitor.alarm.AlarmRuleEngine;
import cn.edu.sztu.elevatormonitor.domain.event.ElevatorEvent;
import cn.edu.sztu.elevatormonitor.entity.AlarmEvent;
import cn.edu.sztu.elevatormonitor.entity.ElevatorMessage;
import cn.edu.sztu.elevatormonitor.entity.repository.AlarmEventJpaRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.EventListener;
import org.springframework.core.annotation.Order;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 告警事件处理器 — 监听 {@link ElevatorEvent}，异步执行告警规则评估与推送。
 *
 * <h3>处理流程</h3>
 * <ol>
 *   <li>将 ElevatorEvent 适配为 ElevatorMessage (兼容现有 AlarmRuleEngine)</li>
 *   <li>调用 AlarmRuleEngine.evaluate() 评估告警规则</li>
 *   <li>告警事件双写: JPA → MySQL + Redis Pub → 前端</li>
 * </ol>
 *
 * <h3>隔离性保证</h3>
 * <ul>
 *   <li>使用独立线程池 "alarmExecutor"，不阻塞事件发布线程</li>
 *   <li>告警处理失败不影响 History / Redis 处理器</li>
 * </ul>
 *
 * @author architecture-v2
 * @since 0.2.0
 */
@Component
public class AlarmHandler {

    private static final Logger LOGGER = LoggerFactory.getLogger(AlarmHandler.class);

    /** Redis 告警频道 */
    private static final String CHANNEL_ELEVATOR_ALARM = "elevator:alarm";

    /** 规则告警标记 key（HSET 子键），供 RedisHandler 合并，独立于巡检告警 */
    static final String MARKER_RULE_ALARM = ":rule_alarm";

    private final AlarmRuleEngine alarmRuleEngine;
    private final AlarmEventJpaRepository alarmRepo;
    private final StringRedisTemplate stringRedisTemplate;

    public AlarmHandler(AlarmRuleEngine alarmRuleEngine,
                        AlarmEventJpaRepository alarmRepo,
                        StringRedisTemplate stringRedisTemplate) {
        this.alarmRuleEngine = alarmRuleEngine;
        this.alarmRepo = alarmRepo;
        this.stringRedisTemplate = stringRedisTemplate;
    }

    /**
     * 监听电梯事件，同步执行告警评估（@Order(1) 确保在 RedisHandler 之前运行）。
     *
     * <p>规则评估结果写入 Redis，由 RedisHandler 合并后统一发布，
     * 消除异步竞态导致的告警灯闪烁问题。DB 持久化仍通过 @Async 异步执行。</p>
     */
    @EventListener
    @Order(1)
    public void onElevatorEvent(ElevatorEvent event) {
        LOGGER.info("[AlarmHandler] 收到事件: eventId={}, deviceId={}", event.getEventId(), event.getDeviceId());
        try {
            // 1. 适配为 ElevatorMessage（兼容现有引擎）
            ElevatorMessage msg = adaptToElevatorMessage(event);

            // 2. 规则评估
            List<AlarmEvent> alarmEvents = alarmRuleEngine.evaluate(msg);
            LOGGER.info("[AlarmHandler] 规则评估完成, deviceId={}, 触发数={}",
                    event.getDeviceId(), alarmEvents.size());

            // 3. 持久化(异步) + 推送 + 回写告警标记
            for (AlarmEvent alarm : alarmEvents) {
                persistAlarmAsync(alarm);
                pushAlarm(alarm);
            }
            // 将触发的告警写入标记 key，供 RedisHandler 合并到 status JSON
            // 无告警时清除标记，确保旧告警不会残留
            if (!alarmEvents.isEmpty()) {
                updateAlarmMarker(event.getDeviceId(), alarmEvents);
            } else {
                clearAlarmMarker(event.getDeviceId());
            }
        } catch (Exception e) {
            LOGGER.error("[AlarmHandler] 处理异常: eventId={}, deviceId={}",
                    event.getEventId(), event.getDeviceId(), e);
        }
    }

    /**
     * 将领域事件适配为 ElevatorMessage（过渡期兼容方案）。
     * 未来可直接改造 AlarmRuleEngine 接受 ElevatorEvent。
     */
    private ElevatorMessage adaptToElevatorMessage(ElevatorEvent event) {
        ElevatorMessage msg = new ElevatorMessage();
        msg.setDeviceId(event.getDeviceId());
        msg.setElevatorStatus(event.getElevatorStatus());
        msg.setDoorStatus(event.getDoorStatus());
        msg.setCurrentFloor(event.getCurrentFloor());
        msg.setTargetFloor(event.getTargetFloor());
        msg.setDirection(event.getDirection());
        msg.setSpeed(event.getSpeed());
        msg.setAlarm(event.getAlarm());
        msg.setPassenger(event.getPassenger());
        // 使用 ElevatorEvent 的设备时间格式化 runtime，而非当前系统时间
        if (event.getDeviceTime() != null) {
            msg.setRuntime(event.getDeviceTime().format(
                    java.time.format.DateTimeFormatter.ofPattern("yyyy/MM/dd HH:mm:ss")));
        }
        msg.setDistance(event.getDistance());
        msg.setTimes(event.getTimes());
        return msg;
    }

    /**
     * 异步持久化告警到 MySQL，避免 DB 写入阻塞事件线程。
     */
    @Async("alarmExecutor")
    public void persistAlarmAsync(AlarmEvent alarm) {
        try {
            alarmRepo.save(alarm);
        } catch (Exception e) {
            LOGGER.error("[AlarmHandler] 告警入库失败: deviceId={}, rule={}",
                    alarm.getDeviceId(), alarm.getRuleName(), e);
        }
    }

    private void pushAlarm(AlarmEvent alarm) {
        try {
            String json = alarm.toJson();
            stringRedisTemplate.convertAndSend(CHANNEL_ELEVATOR_ALARM, json);
            LOGGER.debug("[AlarmHandler] 告警已推送: {}", json);
        } catch (Exception e) {
            LOGGER.error("[AlarmHandler] 告警推送失败: deviceId={}, rule={}",
                    alarm.getDeviceId(), alarm.getRuleName(), e);
        }
    }

    /**
     * 将规则引擎告警写入独立的 marker key，供 RedisHandler 合并。
     * 不再直接修改 status HSET 或重新 PUBLISH，消除竞态闪烁。
     */
    private void updateAlarmMarker(String deviceId, List<AlarmEvent> alarmEvents) {
        try {
            StringBuilder sb = new StringBuilder();
            for (AlarmEvent ae : alarmEvents) {
                if (!AlarmEvent.TYPE_FIRED.equals(ae.getEventType())) {
                    continue;
                }
                if (sb.length() > 0) sb.append(",");
                sb.append(ae.getRuleName());
            }
            String alarmValue = sb.toString();
            stringRedisTemplate.opsForHash().put("elevator:status", deviceId + MARKER_RULE_ALARM, alarmValue);
            LOGGER.info("[AlarmHandler] 规则告警标记已写入: deviceId={}, alarm={}", deviceId, alarmValue);
        } catch (Exception e) {
            LOGGER.error("[AlarmHandler] 告警标记写入失败: deviceId={}", deviceId, e);
        }
    }

    /**
     * 清除告警标记 — 当所有规则均未触发时调用，
     * 确保条件解除后告警立即消失，不会残留在 marker key 中。
     */
    private void clearAlarmMarker(String deviceId) {
        try {
            stringRedisTemplate.opsForHash().put("elevator:status", deviceId + MARKER_RULE_ALARM, "");
            LOGGER.debug("[AlarmHandler] 告警标记已清除: deviceId={}", deviceId);
        } catch (Exception e) {
            LOGGER.error("[AlarmHandler] 告警标记清除失败: deviceId={}", deviceId, e);
        }
    }
}
