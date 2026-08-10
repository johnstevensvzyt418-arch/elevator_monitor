package cn.edu.sztu.elevatormonitor.alarm;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 告警冷却管理器 — 防止同设备+同规则在短时间内重复触发告警，避免告警风暴。
 *
 * <h3>设计要点</h3>
 * <ul>
 *   <li>唯一键: {@code deviceId + ":" + ruleName}</li>
 *   <li>首次告警直接放行，不受冷却限制</li>
 *   <li>冷却期内 (默认 300s) 的重复触发被静默丢弃</li>
 *   <li>冷却结束后允许再次触发，自动续期时间戳</li>
 *   <li>冷却仅作用于 FIRED 事件，不影响 CLEARED 恢复事件</li>
 * </ul>
 *
 * <h3>线程安全</h3>
 * 基于 {@link ConcurrentHashMap#compute} 实现原子 "检查+记录"，
 * 避免 check-then-act 竞态条件。
 *
 * @author Elevator Monitor Team
 */
@Component
public class AlarmCooldownManager {

    private static final Logger LOGGER = LoggerFactory.getLogger(AlarmCooldownManager.class);

    /** 默认冷却时间 (秒)，可通过 {@code alarm.cooldown.default-seconds} 配置 */
    @Value("${alarm.cooldown.default-seconds:300}")
    private int cooldownSeconds;

    /**
     * 冷却记录表。
     * key   = "deviceId:ruleName"
     * value = 最近一次成功触发 FIRED 事件的瞬时时间
     */
    private final ConcurrentHashMap<String, Instant> lastFireTimes = new ConcurrentHashMap<>();

    /**
     * 原子操作：检查是否允许触发 + 记录触发时间。
     * <p>
     * 规则：
     * <ol>
     *   <li>首次触发 (无历史记录) → 允许，记录当前时间</li>
     *   <li>距离上次触发 ≥ cooldownSeconds → 允许，更新时间</li>
     *   <li>距离上次触发 &lt; cooldownSeconds → 拒绝，保持原时间戳</li>
     * </ol>
     *
     * @param deviceId 设备ID
     * @param ruleName 规则名称
     * @return true=允许触发告警, false=冷却中应跳过
     */
    public boolean tryFire(String deviceId, String ruleName) {
        String key = buildKey(deviceId, ruleName);
        Instant now = Instant.now();

        boolean[] allowed = {false};

        lastFireTimes.compute(key, (k, lastFire) -> {
            if (lastFire == null) {
                // 首次触发 — 直接放行
                allowed[0] = true;
                return now;
            }
            long elapsed = Duration.between(lastFire, now).getSeconds();
            if (elapsed >= cooldownSeconds) {
                // 冷却已结束 — 允许再次触发，更新时间戳
                allowed[0] = true;
                return now;
            }
            // 仍在冷却期内 — 保持原时间戳不变
            allowed[0] = false;
            return lastFire;
        });

        if (!allowed[0]) {
            LOGGER.debug("[Cooldown] 告警冷却中, 已跳过: device={}, rule={}, cooldown={}s",
                    deviceId, ruleName, cooldownSeconds);
        }
        return allowed[0];
    }

    /**
     * 清除冷却记录（规则恢复为正常时调用）。
     * 使"恢复后短时间内再次故障"能立即重新触发告警，无需等待冷却期结束。
     */
    public void reset(String deviceId, String ruleName) {
        lastFireTimes.remove(buildKey(deviceId, ruleName));
        LOGGER.debug("[Cooldown] 冷却记录已清除: device={}, rule={}", deviceId, ruleName);
    }

    /**
     * 获取当前配置的冷却时间 (用于诊断/日志)。
     */
    public int getCooldownSeconds() {
        return cooldownSeconds;
    }

    /**
     * 构建冷却记录的唯一键。
     */
    private String buildKey(String deviceId, String ruleName) {
        return deviceId + ":" + ruleName;
    }
}
