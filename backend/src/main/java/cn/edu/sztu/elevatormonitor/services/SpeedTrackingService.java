package cn.edu.sztu.elevatormonitor.services;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.Map;

/**
 * 基于 Redis 的有状态速度追踪服务。
 *
 * <h3>问题背景</h3>
 * <p>原有速度计算依赖 ElevatorMessage 内部的 timeQueue/floorQueue，这些队列在每次
 * HTTP 请求中重新创建，无法跨请求累积状态，导致 SPEED_ABNORMAL 告警永远无法触发。</p>
 *
 * <h3>解决方案</h3>
 * <p>将每个设备的上次楼层和上报时间存储到 Redis Hash 中，跨请求计算瞬时速度：</p>
 * <pre>
 *   speed (m/s) = |currentFloor - lastFloor| × 2.8 / |currentTime - lastTime|
 * </pre>
 * <p>其中 2.8m 为单层楼高（与现有 distance 计算一致）。</p>
 *
 * <h3>缓存速度过期机制（v0.1.6 修复）</h3>
 * <p>当楼层长时间未变化时，缓存的旧速度会过期归零，避免以下问题：</p>
 * <ul>
 *   <li>电梯困人停止后，旧速度残留导致 DoorOpenRunningRule（开门运行）误告警</li>
 *   <li>前端速度显示在电梯实际停止后仍显示非零值</li>
 * </ul>
 * <p>过期时间通过 {@link #CACHED_SPEED_MAX_AGE_SEC} 控制，默认 3 秒。</p>
 *
 * <h3>Redis 数据结构</h3>
 * <pre>
 *   HSET elevator:speedtrack:{deviceId}
 *     lastFloor            → "01"
 *     lastTimeEpoch        → "1719312000"
 *     lastSpeed            → "0.05"       (缓存上次有效速度)
 *     lastFloorChangeEpoch → "1719312000" (上次楼层变化的时间戳，用于缓存过期判定)
 *     lastMovingSpeed      → "0.52"       (上次运行中的有效速度，不平层时不清零，用于新行程的速度预估)
 *     levelingStartEpoch   → "1719312005" (进入平层状态的时间戳，用于3s延迟归零)
 * </pre>
 *
 * @author bugfix
 * @since 0.1.5
 */
@Service
public class SpeedTrackingService {

    private static final Logger LOGGER = LoggerFactory.getLogger(SpeedTrackingService.class);

    /** 单层楼高（米），与 ElevatorMessageRepository 中 distance 计算一致 */
    private static final double FLOOR_HEIGHT_M = 2.8;

    /** 缓存速度最大存活时间（秒），超时后强制归零，防止旧速度残留导致误告警 */
    private static final long CACHED_SPEED_MAX_AGE_SEC = 3;

    /** Redis Hash 键名前缀 */
    private static final String HASH_PREFIX = "elevator:speedtrack:";

    private final StringRedisTemplate stringRedisTemplate;

    public SpeedTrackingService(StringRedisTemplate stringRedisTemplate) {
        this.stringRedisTemplate = stringRedisTemplate;
    }

    /**
     * 计算并更新设备的速度。
     *
     * @param deviceId     设备ID
     * @param currentFloor 当前楼层（如 "01", "02"）
     * @param reportTime   上报时间字符串（HH:mm:ss 格式）
     * @param direction    运行方向（"00"=平层/停止, "01"=上行, "02"=下行）
     * @return 计算出的瞬时速度（m/s），平层时返回 0.0，首次上报返回 0.0
     */
    public double calculateAndUpdateSpeed(String deviceId, String currentFloor, String reportTime, String direction) {
        if (deviceId == null || currentFloor == null || reportTime == null) {
            return 0.0;
        }

        String hashKey = HASH_PREFIX + deviceId;
        long currentEpoch = parseTimeToEpochSeconds(reportTime);

        // 获取上次状态
        Map<Object, Object> lastState = stringRedisTemplate.opsForHash().entries(hashKey);
        String lastFloor = lastState != null ? (String) lastState.get("lastFloor") : null;
        String lastTimeStr = lastState != null ? (String) lastState.get("lastTimeEpoch") : null;
        String lastSpeedStr = lastState != null ? (String) lastState.get("lastSpeed") : null;
        String lastFloorChangeEpochStr = lastState != null ? (String) lastState.get("lastFloorChangeEpoch") : null;

        // 首次上报，无历史数据
        if (lastFloor == null || lastTimeStr == null) {
            // 更新当前状态到 Redis
            stringRedisTemplate.opsForHash().put(hashKey, "lastFloor", currentFloor);
            stringRedisTemplate.opsForHash().put(hashKey, "lastTimeEpoch", String.valueOf(currentEpoch));
            stringRedisTemplate.opsForHash().put(hashKey, "lastSpeed", "0.0");
            stringRedisTemplate.opsForHash().put(hashKey, "lastFloorChangeEpoch", String.valueOf(currentEpoch));
            stringRedisTemplate.opsForHash().put(hashKey, "lastMovingSpeed", "0.0");
            // 首次上报且方向非平层 → 电梯正在运行中，打印诊断日志
            if (!"00".equals(direction)) {
                LOGGER.info("[SpeedTrack] 设备 {} 首次上报(运行中, dir={}), floor={}, 速度待首次楼层变化后计算",
                        deviceId, direction, currentFloor);
            } else {
                LOGGER.debug("[SpeedTrack] 设备 {} 首次上报, floor={}, 速度=0.0", deviceId, currentFloor);
            }
            return 0.0;
        }

        // 解析上次缓存的速度（用于楼层未变时保持显示）
        double cachedSpeed = 0.0;
        if (lastSpeedStr != null) {
            try {
                cachedSpeed = Double.parseDouble(lastSpeedStr);
            } catch (NumberFormatException e) {
                cachedSpeed = 0.0;
            }
        }

        // 解析上次运行中的有效速度（不平层时不清零，用于新行程的速度预估）
        String lastMovingSpeedStr = lastState != null ? (String) lastState.get("lastMovingSpeed") : null;
        double lastMovingSpeed = 0.0;
        if (lastMovingSpeedStr != null) {
            try {
                lastMovingSpeed = Double.parseDouble(lastMovingSpeedStr);
            } catch (NumberFormatException e) {
                lastMovingSpeed = 0.0;
            }
        }

        // 解析上次楼层变化的时间戳（用于缓存过期判定）
        long lastFloorChangeEpoch;
        try {
            lastFloorChangeEpoch = (lastFloorChangeEpochStr != null)
                    ? Long.parseLong(lastFloorChangeEpochStr) : currentEpoch;
        } catch (NumberFormatException e) {
            lastFloorChangeEpoch = currentEpoch;
        }

        // 计算楼层差和时间差
        long lastEpoch;
        int curFloor, prevFloor;
        try {
            lastEpoch = Long.parseLong(lastTimeStr);
            curFloor = Integer.parseInt(currentFloor);
            prevFloor = Integer.parseInt(lastFloor);
        } catch (NumberFormatException e) {
            LOGGER.warn("[SpeedTrack] 数值解析失败: deviceId={}, curFloor={}, lastFloor={}",
                    deviceId, currentFloor, lastFloor);
            return cachedSpeed;  // 解析失败时返回缓存速度，避免错误覆盖
        }

        int floorDiff = Math.abs(curFloor - prevFloor);
        long timeDiffSec = Math.abs(currentEpoch - lastEpoch);

        // 平层（方向=00）→ 速度平滑归零（3s 延迟），避免前端速度显示频繁闪烁
        // 同时保留 lastMovingSpeed 供下次启动时参考
        if ("00".equals(direction)) {
            // 获取进入平层的时间戳
            String levelingStartEpochStr = lastState != null ? (String) lastState.get("levelingStartEpoch") : null;
            long levelingStartEpoch;
            try {
                levelingStartEpoch = (levelingStartEpochStr != null)
                        ? Long.parseLong(levelingStartEpochStr) : 0;
            } catch (NumberFormatException e) {
                levelingStartEpoch = 0;
            }

            // 首次进入平层或之前不在平层状态 → 记录开始时间
            if (levelingStartEpoch == 0) {
                levelingStartEpoch = currentEpoch;
                stringRedisTemplate.opsForHash().put(hashKey, "levelingStartEpoch", String.valueOf(currentEpoch));
            }

            long secSinceLeveling = Math.abs(currentEpoch - levelingStartEpoch);

            // 更新基础状态
            stringRedisTemplate.opsForHash().put(hashKey, "lastFloor", currentFloor);
            stringRedisTemplate.opsForHash().put(hashKey, "lastTimeEpoch", String.valueOf(currentEpoch));
            stringRedisTemplate.opsForHash().put(hashKey, "lastFloorChangeEpoch", String.valueOf(currentEpoch));

            if (secSinceLeveling <= CACHED_SPEED_MAX_AGE_SEC && lastMovingSpeed > 0.0) {
                // 3s 内：保持显示上次运行速度，实现平滑过渡，防止速度数字频繁跳变
                // 注意：仅影响前端显示值，告警规则引擎不受影响（DoorOpenRunningRule 在 dir=00 时直接返回 null）
                stringRedisTemplate.opsForHash().put(hashKey, "lastSpeed", String.valueOf(lastMovingSpeed));
                LOGGER.debug("[SpeedTrack] 设备 {} 平层过渡中(已{}s/{}s), 保持显示速度={}m/s",
                        deviceId, secSinceLeveling, CACHED_SPEED_MAX_AGE_SEC,
                        String.format("%.2f", lastMovingSpeed));
                return lastMovingSpeed;
            }

            // 超过 3s → 真正归零
            stringRedisTemplate.opsForHash().put(hashKey, "lastSpeed", "0.0");
            LOGGER.debug("[SpeedTrack] 设备 {} 平层停止{}s, 速度归零 (lastMovingSpeed={}m/s)",
                    deviceId, secSinceLeveling, String.format("%.2f", lastMovingSpeed));
            return 0.0;
        } else {
            // 非平层 → 清除 levelingStartEpoch，下次进入平层时重新计时
            stringRedisTemplate.opsForHash().put(hashKey, "levelingStartEpoch", "0");
        }

        // 楼层发生变化 → 计算新速度
        if (floorDiff > 0) {
            // 更新 lastFloor 和楼层变化时间戳
            stringRedisTemplate.opsForHash().put(hashKey, "lastFloor", currentFloor);
            stringRedisTemplate.opsForHash().put(hashKey, "lastTimeEpoch", String.valueOf(currentEpoch));
            stringRedisTemplate.opsForHash().put(hashKey, "lastFloorChangeEpoch", String.valueOf(currentEpoch));

            double distance = floorDiff * FLOOR_HEIGHT_M;
            double speed = distance / timeDiffSec;

            // 缓存本次计算的有效速度（含 lastMovingSpeed，不平层时不清零）
            stringRedisTemplate.opsForHash().put(hashKey, "lastSpeed", String.valueOf(speed));
            stringRedisTemplate.opsForHash().put(hashKey, "lastMovingSpeed", String.valueOf(speed));

            LOGGER.info("[SpeedTrack] 设备 {}: {}F→{}F, 间隔{}s, 距离{}m, 速度={}m/s",
                    deviceId, prevFloor, curFloor, timeDiffSec,
                    String.format("%.1f", distance), String.format("%.2f", speed));
            return speed;
        }

        // 楼层未变化 → 检查缓存速度是否过期
        stringRedisTemplate.opsForHash().put(hashKey, "lastTimeEpoch", String.valueOf(currentEpoch));
        long secSinceFloorChange = Math.abs(currentEpoch - lastFloorChangeEpoch);

        if (secSinceFloorChange > CACHED_SPEED_MAX_AGE_SEC) {
            // 缓存速度已过期：电梯可能已停止/困人，旧速度不应继续使用
            // 强制归零，防止 DoorOpenRunningRule 等规则基于旧速度误判
            stringRedisTemplate.opsForHash().put(hashKey, "lastSpeed", "0.0");
            LOGGER.info("[SpeedTrack] 设备 {} 楼层{}未变已{}s > 阈值{}s, 缓存速度过期归零(旧值={}m/s)",
                    deviceId, currentFloor, secSinceFloorChange,
                    CACHED_SPEED_MAX_AGE_SEC, String.format("%.2f", cachedSpeed));
            return 0.0;
        }

        // 缓存速度未过期：若 cachedSpeed 为 0 但电梯在运行中，使用 lastMovingSpeed 兜底
        // 解决"电梯启动后楼层未变前速度始终显示为0"的问题
        double displaySpeed = cachedSpeed;
        if (displaySpeed <= 0.0 && lastMovingSpeed > 0.0 && !"00".equals(direction)) {
            displaySpeed = lastMovingSpeed;
            LOGGER.debug("[SpeedTrack] 设备 {} 楼层未变但电梯运行中, cachedSpeed=0, 使用lastMovingSpeed={}m/s",
                    deviceId, String.format("%.2f", lastMovingSpeed));
        }

        LOGGER.debug("[SpeedTrack] 设备 {} 楼层未变(已{}s/{}s), 返回速度={}m/s",
                deviceId, secSinceFloorChange, CACHED_SPEED_MAX_AGE_SEC,
                String.format("%.2f", displaySpeed));
        return displaySpeed;
    }

    /**
     * 解析 HH:mm:ss 格式时间为当天 epoch 秒数。
     * 使用当天日期 + 上报时间组合，通过完整 epoch 秒数避免跨天问题。
     */
    private long parseTimeToEpochSeconds(String timeStr) {
        try {
            String[] parts = timeStr.split(":");
            if (parts.length < 3) return Instant.now().getEpochSecond();
            int hours = Integer.parseInt(parts[0]);
            int minutes = Integer.parseInt(parts[1]);
            int seconds = Integer.parseInt(parts[2]);
            // 使用当天日期 + 上报时间 → 完整 epoch 秒，解决跨午夜回绕问题
            java.time.LocalDate today = java.time.LocalDate.now();
            java.time.LocalTime time = java.time.LocalTime.of(hours, minutes, seconds);
            return java.time.LocalDateTime.of(today, time)
                    .atZone(java.time.ZoneId.systemDefault())
                    .toEpochSecond();
        } catch (Exception e) {
            LOGGER.warn("[SpeedTrack] 时间解析失败: {}", timeStr);
            return Instant.now().getEpochSecond();
        }
    }
}
