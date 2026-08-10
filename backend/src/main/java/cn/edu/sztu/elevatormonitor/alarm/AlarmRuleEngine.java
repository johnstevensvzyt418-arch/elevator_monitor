package cn.edu.sztu.elevatormonitor.alarm;

import cn.edu.sztu.elevatormonitor.entity.AlarmEvent;
import cn.edu.sztu.elevatormonitor.entity.ElevatorMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 告警规则引擎 — 编排所有 AlarmRule，对每条 ElevatorMessage 进行评估。
 *
 * 核心流程:
 *   1. 获取/创建设备状态
 *   2. 遍历所有规则，收集触发的 AlarmEvent
 *   3. 去重: 同一规则已激活则不再重复 FIRED
 *   4. 恢复: 之前激活的规则现在未触发 → 生成 CLEARED 事件
 *   5. 更新设备状态 (移动时间、关门时间等)
 *
 * 扩展新规则: 只需新建 AlarmRule 实现类并标注 @Component，引擎自动发现。
 */
@Component
public class AlarmRuleEngine {

    private static final Logger LOGGER = LoggerFactory.getLogger(AlarmRuleEngine.class);

    private final List<AlarmRule> rules;
    private final DeviceStateStore stateStore;
    private final AlarmCooldownManager cooldownManager;

    public AlarmRuleEngine(List<AlarmRule> rules,
                           DeviceStateStore stateStore,
                           AlarmCooldownManager cooldownManager) {
        this.rules = rules;
        this.stateStore = stateStore;
        this.cooldownManager = cooldownManager;
        LOGGER.info("[Alarm] 规则引擎初始化完成, 已加载 {} 条规则: {}, 冷却时间={}s",
                rules.size(),
                rules.stream().map(AlarmRule::ruleName).toArray(),
                cooldownManager.getCooldownSeconds());
    }

    /**
     * 对单条电梯消息执行全量规则评估。
     * @return 本次触发的告警事件列表 (FIRED + CLEARED)
     */
    public List<AlarmEvent> evaluate(ElevatorMessage msg) {
        if (msg == null || msg.getDeviceId() == null) {
            return Collections.emptyList();
        }

        DeviceState state = stateStore.getOrCreate(msg.getDeviceId());
        state.markMessageReceived();

        List<AlarmEvent> events = new ArrayList<>();

        for (AlarmRule rule : rules) {
            try {
                AlarmEvent event = rule.evaluate(msg, state);
                if (event != null) {
                    // 冷却检查: 防止同设备+同规则在冷却期内重复触发
                    if (!cooldownManager.tryFire(msg.getDeviceId(), rule.ruleName())) {
                        continue;
                    }
                    // 去重: 同一规则已激活时不重复 FIRED
                    if (!state.isAlarmActive(rule.ruleName())) {
                        state.activateAlarm(rule.ruleName());
                        events.add(event);
                        LOGGER.warn("[Alarm] 告警触发: device={}, rule={}, level={}",
                                msg.getDeviceId(), rule.ruleName(), rule.level());
                    }
                } else {
                    // 规则未触发 → 若之前处于激活状态，生成恢复事件
                    if (state.isAlarmActive(rule.ruleName())) {
                        state.deactivateAlarm(rule.ruleName());
                        // 恢复时清除冷却记录：
                        // 避免"恢复后短时间内再次故障"因冷却期(300s)无法立即重新告警、灯不亮
                        cooldownManager.reset(msg.getDeviceId(), rule.ruleName());
                        AlarmEvent cleared = AlarmEvent.clear(
                                msg.getDeviceId(), rule.ruleName(), rule.description());
                        events.add(cleared);
                        LOGGER.info("[Alarm] 告警恢复: device={}, rule={}",
                                msg.getDeviceId(), rule.ruleName());
                    }
                }
            } catch (Exception e) {
                LOGGER.error("[Alarm] 规则评估异常, rule={}, deviceId={}",
                        rule.ruleName(), msg.getDeviceId(), e);
            }
        }

        // 有状态规则的状态更新已内置于各 rule.evaluate() 中
        return events;
    }

    /**
     * 获取设备当前处于激活状态的规则告警集合。
     * <p>用于告警标记持久化：即使规则处于冷却期内（持续故障时 evaluate 因冷却跳过
     * 产生空事件列表），已激活的告警仍应保持点亮，避免告警灯闪烁/熄灭。</p>
     */
    public java.util.Set<String> getActiveAlarms(String deviceId) {
        DeviceState state = stateStore.get(deviceId);
        return state != null ? state.getActiveAlarms() : java.util.Collections.emptySet();
    }

    public List<AlarmRule> getRules() {
        return rules;
    }
}
