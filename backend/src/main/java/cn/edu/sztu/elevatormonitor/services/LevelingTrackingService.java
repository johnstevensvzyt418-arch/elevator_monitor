package cn.edu.sztu.elevatormonitor.services;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.Map;

/**
 * 基于 Redis 的有状态平层超时检测服务。
 *
 * <h3>问题背景</h3>
 * <p>原有 {@code ElevatorMessage.setMalfunction()} 中的平层超时检测依赖实例字段
 * {@code isRecorded} 和 {@code levelingTime}，每次 HTTP 请求新建对象导致状态丢失，
 * 无法跨请求触发 ALARM_FIELD 告警。</p>
 *
 * <h3>触发逻辑</h3>
 * <ol>
 *   <li>当前楼层==目标楼层（平层）且 有乘客(内招=01) 且 门未打开(≠01) → 判定为困人风险</li>
 *   <li>开始计时，若该状态持续超过阈值秒数 → 返回告警标识 "LEVELING_TIMEOUT"</li>
 *   <li>告警触发后<b>保持返回告警</b>，直到门打开或楼层变化或乘客离开才清除</li>
 *   <li>门打开(01) 或 楼层≠目标 或 乘客离开 → 完全重置（含告警状态）</li>
 * </ol>
 *
 * <h3>Redis 数据结构</h3>
 * <pre>
 *   HSET elevator:leveling:{deviceId}
 *     recorded  → "1" / "0"
 *     startSec  → epoch秒数
 *     fired     → "1" / "0"  （告警是否已触发，用于持久化）
 * </pre>
 *
 * @author bugfix
 * @since 0.1.5
 */
@Service
public class LevelingTrackingService {

    private static final Logger LOGGER = LoggerFactory.getLogger(LevelingTrackingService.class);

    /** 平层超时阈值（秒），可通过配置文件覆盖 */
    @Value("${alarm.leveling.timeout-seconds:5}")
    private int timeoutSeconds;

    /** Redis Hash 键名前缀 */
    private static final String HASH_PREFIX = "elevator:leveling:";

    /** 告警代码 */
    public static final String ALARM_LEVELING_TIMEOUT = "LEVELING_TIMEOUT";

    private final StringRedisTemplate stringRedisTemplate;

    public LevelingTrackingService(StringRedisTemplate stringRedisTemplate) {
        this.stringRedisTemplate = stringRedisTemplate;
    }

    /**
     * 检测困人风险（平层有乘客门打不开超时），返回告警标识。
     *
     * @param deviceId     设备ID
     * @param currentFloor 当前楼层（如 "01", "02"）
     * @param targetFloor  目标楼层（如 "01", "无"）
     * @param doorStatus   门状态: "00"=关门, "01"=开门到位
     * @param passenger    乘客状态: "01"=有乘客(内招), "00"=无乘客
     * @return 告警标识字符串，无告警返回 null
     */
    public String checkLevelingTimeout(String deviceId, String currentFloor,
                                        String targetFloor, String doorStatus,
                                        String passenger) {
        if (deviceId == null || currentFloor == null || doorStatus == null) {
            return null;
        }

        String hashKey = HASH_PREFIX + deviceId;
        boolean isLeveling = floorEquals(currentFloor, targetFloor);
        boolean hasPassenger = "01".equals(passenger);
        boolean isDoorOpen = "01".equals(doorStatus);

        // 条件不满足 → 完全重置（含告警标记）
        // 困人解除: 门打开了 / 楼层变化 / 乘客离开
        if (!isLeveling || !hasPassenger || isDoorOpen) {
            resetAll(hashKey);
            return null;
        }

        // 平层 + 有乘客 + 门未开 → 检查/启动计时
        Map<Object, Object> state = stringRedisTemplate.opsForHash().entries(hashKey);
        String recorded = state != null ? (String) state.get("recorded") : null;
        String startSecStr = state != null ? (String) state.get("startSec") : null;
        String fired = state != null ? (String) state.get("fired") : null;

        long nowSec = Instant.now().getEpochSecond();

        // 告警已触发且条件未解除 → 持续返回告警，保持前端指示灯常亮
        if ("1".equals(fired)) {
            LOGGER.debug("[Leveling] 设备 {} 困人告警持续中, floor={}", deviceId, currentFloor);
            return ALARM_LEVELING_TIMEOUT;
        }

        if (!"1".equals(recorded) || startSecStr == null) {
            // 首次进入困人风险状态 → 开始计时
            stringRedisTemplate.opsForHash().put(hashKey, "recorded", "1");
            stringRedisTemplate.opsForHash().put(hashKey, "startSec", String.valueOf(nowSec));
            stringRedisTemplate.opsForHash().put(hashKey, "fired", "0");
            LOGGER.info("[Leveling] 设备 {} 平层有乘客门未开, 开始计时 (阈值={}s), floor={}",
                    deviceId, timeoutSeconds, currentFloor);
            return null;
        }

        // 已记录 → 检查是否超时
        long startSec;
        try {
            startSec = Long.parseLong(startSecStr);
        } catch (NumberFormatException e) {
            resetAll(hashKey);
            return null;
        }

        long elapsed = nowSec - startSec;
        if (elapsed >= timeoutSeconds) {
            LOGGER.warn("[Leveling] 设备 {} 困人告警触发! 平层有乘客门未开已持续{}s > 阈值{}s, floor={}",
                    deviceId, elapsed, timeoutSeconds, currentFloor);
            // 标记告警已触发但不重置计时器，后续调用持续返回告警直到条件解除
            stringRedisTemplate.opsForHash().put(hashKey, "fired", "1");
            return ALARM_LEVELING_TIMEOUT;
        }

        LOGGER.debug("[Leveling] 设备 {} 困人风险计时中, 已持续{}s/{}s, floor={}",
                deviceId, elapsed, timeoutSeconds, currentFloor);
        return null;
    }

    /**
     * 数值化比较两个楼层值，兼容 "01" 与 "1" 等前导零差异。
     */
    private boolean floorEquals(String f1, String f2) {
        if (f1 == null || f2 == null) return false;
        try {
            return Integer.parseInt(f1) == Integer.parseInt(f2);
        } catch (NumberFormatException e) {
            // 非数字楼层（如 "无", "B1"）退化为字符串比较
            return f1.equals(f2);
        }
    }

    private void resetAll(String hashKey) {
        stringRedisTemplate.opsForHash().put(hashKey, "recorded", "0");
        stringRedisTemplate.opsForHash().put(hashKey, "startSec", "0");
        stringRedisTemplate.opsForHash().put(hashKey, "fired", "0");
    }
}
