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
 *   <li>平层停靠(方向00且当前楼层命中目标楼层) 且 有乘客 且 门未打开(≠01) → 判定为困人风险</li>
 *   <li>累计有效时长超过阈值秒数 → 返回告警标识 "LEVELING_TIMEOUT"（抖动帧暂停累加不清零）</li>
 *   <li>告警触发后<b>保持返回告警</b>，连续 {@link #BAD_FRAME_THRESHOLD} 帧条件解除才清除（容忍单帧毛刺）</li>
 *   <li>楼层匹配支持组合内召（如 "1、2"），当前楼层命中任一层即视为平层</li>
 * </ol>
 *
 * <h3>Redis 数据结构</h3>
 * <pre>
 *   HSET elevator:leveling:{deviceId}
 *     recorded  → "1" / "0"            是否进入困人风险流程
 *     accSec    → 累计有效时长(秒)     抖动帧暂停累加，不清零
 *     lastValid → 最近一次有效帧 epoch（"-1"=暂停累加中）
 *     badCount  → 连续异常帧计数        达到 BAD_FRAME_THRESHOLD 才重置
 *     fired     → "1" / "0"           告警是否已触发，用于持久化
 * </pre>
 *
 * @author bugfix
 * @since 0.1.5
 */
@Service
public class LevelingTrackingService {

    private static final Logger LOGGER = LoggerFactory.getLogger(LevelingTrackingService.class);

    /** 平层超时阈值（秒），可通过配置文件覆盖 */
    @Value("${alarm.leveling.timeout-seconds:30}")
    private int timeoutSeconds;

    /** Redis Hash 键名前缀 */
    private static final String HASH_PREFIX = "elevator:leveling:";

    /** 连续异常帧达到该值才完全重置（抖动容忍），单帧毛刺只暂停累加、不清零 */
    private static final int BAD_FRAME_THRESHOLD = 2;

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
     * @param targetFloor  目标楼层（如 "01", "无"、组合"1、2"）
     * @param doorStatus   门状态: "00"=关门, "01"=开门到位
     * @param passenger    乘客状态: "01"=有乘客(内招), "00"=无乘客
     * @param direction    运行方向（保留兼容，不再作为平层硬性条件，避免到站方向未归零漏报）
     * @return 告警标识字符串，无告警返回 null
     */
    public String checkLevelingTimeout(String deviceId, String currentFloor,
                                        String targetFloor, String doorStatus,
                                        String passenger, String direction) {
        if (deviceId == null || currentFloor == null || doorStatus == null) {
            return null;
        }

        String hashKey = HASH_PREFIX + deviceId;
        // 平层判定: 当前楼层命中目标楼层(支持组合内召)。
        // 注意: 不要求 direction==00 —— 真实设备到站/困人时方向字节可能未及时归零
        // (仍报 01/02 运行方向) 或报故障方向(03)，若严格要求 direction==00 会漏报困人
        // (电梯实际已停住但方向非 00)。
        // 防"运行中误报"依赖楼层变化重置: 电梯运行中经过目标层时楼层会很快变化
        // (cur≠target) → 触发无效帧 → 连续 2 帧后重置计时; 只有电梯真正停在目标层才计时。
        boolean isLeveling = floorEquals(currentFloor, targetFloor);
        boolean hasPassenger = "01".equals(passenger);
        boolean isDoorOpen = "01".equals(doorStatus);
        boolean valid = isLeveling && hasPassenger && !isDoorOpen;

        Map<Object, Object> state = stringRedisTemplate.opsForHash().entries(hashKey);
        String fired = state != null ? (String) state.get("fired") : null;
        long nowSec = Instant.now().getEpochSecond();

        // ============ 告警已触发：保持返回告警，连续 BAD_FRAME_THRESHOLD 帧条件解除才清除 ============
        // 单帧毛刺（door=01 抖动 / passenger=00 抖动 / direction 抖动）不会让告警闪烁消失。
        if ("1".equals(fired)) {
            if (valid) {
                // 恢复有效，清零异常计数，继续保持告警
                stringRedisTemplate.opsForHash().put(hashKey, "badCount", "0");
                return ALARM_LEVELING_TIMEOUT;
            }
            int bad = incrBadCount(hashKey, state);
            LOGGER.info("[Leveling] 设备 {} 困人告警持续中(异常帧{}/{}), floor={}",
                    deviceId, bad, BAD_FRAME_THRESHOLD, currentFloor);
            if (bad >= BAD_FRAME_THRESHOLD) {
                resetAll(hashKey);
                return null;
            }
            return ALARM_LEVELING_TIMEOUT; // 抖动帧仍保持告警
        }

        // ============ 条件不满足：连续 BAD_FRAME_THRESHOLD 帧才完全重置 ============
        // 容忍单帧瞬态异常（传感器毛刺/协议抖动），避免计时被反复清零永远达不到阈值。
        if (!valid) {
            int bad = incrBadCount(hashKey, state);
            if (bad == 1) {
                // 第一帧异常：暂停累计（暂停期不计入），但不清零
                stringRedisTemplate.opsForHash().put(hashKey, "lastValid", "-1");
                LOGGER.info("[Leveling] 设备 {} 困人条件异常(第1帧, 暂不清零) — 平层={}, 有乘客={}, 门开={} | "
                        + "cur={}, target={}, door={}, passenger={}",
                        deviceId, isLeveling, hasPassenger, isDoorOpen,
                        currentFloor, targetFloor, doorStatus, passenger);
            }
            if (bad >= BAD_FRAME_THRESHOLD) {
                resetAll(hashKey);
            }
            return null;
        }

        // ============ 有效帧：累计有效时长（抖动帧暂停累加，不清零） ============
        stringRedisTemplate.opsForHash().put(hashKey, "badCount", "0");
        String recorded = state != null ? (String) state.get("recorded") : null;
        String accSecStr = state != null ? (String) state.get("accSec") : null;
        String lastValidStr = state != null ? (String) state.get("lastValid") : null;
        boolean paused = "-1".equals(lastValidStr);

        if (!"1".equals(recorded) || lastValidStr == null || paused) {
            // 首次进入 或 从暂停恢复：重设计时起点，不把暂停期计入
            stringRedisTemplate.opsForHash().put(hashKey, "recorded", "1");
            if (lastValidStr == null || !"1".equals(recorded)) {
                stringRedisTemplate.opsForHash().put(hashKey, "accSec", "0");
                LOGGER.info("[Leveling] 设备 {} 平层有乘客门未开, 开始计时 (阈值={}s), floor={}",
                        deviceId, timeoutSeconds, currentFloor);
            } else {
                LOGGER.info("[Leveling] 设备 {} 暂停后恢复计时(不累计暂停期), 累计{}s, floor={}",
                        deviceId, accSecStr == null ? 0 : accSecStr, currentFloor);
            }
            stringRedisTemplate.opsForHash().put(hashKey, "lastValid", String.valueOf(nowSec));
            return null;
        }

        long lastValidSec;
        try {
            lastValidSec = Long.parseLong(lastValidStr);
        } catch (NumberFormatException e) {
            resetAll(hashKey);
            return null;
        }

        long accSec = 0;
        try {
            accSec = Long.parseLong(accSecStr);
        } catch (NumberFormatException e) {
            accSec = 0;
        }
        // 有效帧间距累加到累计时长
        long delta = nowSec - lastValidSec;
        if (delta > 0) {
            accSec += delta;
            stringRedisTemplate.opsForHash().put(hashKey, "accSec", String.valueOf(accSec));
        }
        stringRedisTemplate.opsForHash().put(hashKey, "lastValid", String.valueOf(nowSec));

        if (accSec >= timeoutSeconds) {
            LOGGER.warn("[Leveling] 设备 {} 困人告警触发! 累计有效时长{}s >= 阈值{}s, floor={}",
                    deviceId, accSec, timeoutSeconds, currentFloor);
            // 标记告警已触发，后续调用持续返回告警直到条件解除
            stringRedisTemplate.opsForHash().put(hashKey, "fired", "1");
            return ALARM_LEVELING_TIMEOUT;
        }

        LOGGER.debug("[Leveling] 设备 {} 困人风险计时中, 累计{}s/{}s, floor={}",
                deviceId, accSec, timeoutSeconds, currentFloor);
        return null;
    }

    /**
     * 楼层匹配：targetFloor 可能是组合内召（如 "1、2"、"1、2、3"），
     * 按顿号拆分后，当前楼层命中任意一个即视为平层，避免组合内召时漏判困人。
     */
    private boolean floorEquals(String currentFloor, String targetFloor) {
        if (currentFloor == null || targetFloor == null) return false;
        String[] parts = targetFloor.split("、");
        for (String part : parts) {
            if (floorEqualsSingle(currentFloor, part.trim())) {
                return true;
            }
        }
        return false;
    }

    /**
     * 数值化比较两个楼层值，兼容 "01" 与 "1" 等前导零差异。
     */
    private boolean floorEqualsSingle(String f1, String f2) {
        if (f1 == null || f2 == null) return false;
        try {
            return Integer.parseInt(f1) == Integer.parseInt(f2);
        } catch (NumberFormatException e) {
            // 非数字楼层（如 "无", "B1"）退化为字符串比较
            return f1.equals(f2);
        }
    }

    /** 自增连续异常帧计数，返回当前值 */
    private int incrBadCount(String hashKey, Map<Object, Object> state) {
        String badStr = state != null ? (String) state.get("badCount") : null;
        int bad = 0;
        try {
            if (badStr != null) bad = Integer.parseInt(badStr);
        } catch (NumberFormatException e) {
            bad = 0;
        }
        bad++;
        stringRedisTemplate.opsForHash().put(hashKey, "badCount", String.valueOf(bad));
        return bad;
    }

    private void resetAll(String hashKey) {
        stringRedisTemplate.opsForHash().put(hashKey, "recorded", "0");
        stringRedisTemplate.opsForHash().put(hashKey, "accSec", "0");
        stringRedisTemplate.opsForHash().put(hashKey, "lastValid", "0");
        stringRedisTemplate.opsForHash().put(hashKey, "badCount", "0");
        stringRedisTemplate.opsForHash().put(hashKey, "fired", "0");
    }
}
