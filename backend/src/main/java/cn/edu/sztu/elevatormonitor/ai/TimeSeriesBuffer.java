package cn.edu.sztu.elevatormonitor.ai;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.function.LongSupplier;
import java.util.stream.Collectors;

/**
 * 时序数据缓冲区 — 按 deviceId 在 Redis 中维护滑动窗口，供 AI 推理使用。
 *
 * <h3>存储方案</h3>
 * <ul>
 *   <li>Redis Key: {@code ai:series:{deviceId}} → List</li>
 *   <li>每条数据: JSON 数组 {@code [f1, f2, f3, f4, f5]} (5 维特征)</li>
 *   <li>使用 LPUSH + LTRIM 维护固定窗口大小</li>
 * </ul>
 *
 * <h3>特征提取规则</h3>
 * <p>从 {@code ElevatorEvent} 中提取 5 维数值特征：</p>
 * <ol>
 *   <li>门状态 → 数值 (00=0, 01=1, 02=2, 03=3)</li>
 *   <li>当前楼层 → 整数</li>
 *   <li>目标楼层 → 整数</li>
 *   <li>运行方向 → 数值 (00=0, 01=1, 02=2)</li>
 *   <li>相邻设备报文的时间间隔（秒）</li>
 * </ol>
 *
 * @author ai-integration
 * @since 0.3.0
 */
@Component
public class TimeSeriesBuffer {

    private static final Logger LOGGER = LoggerFactory.getLogger(TimeSeriesBuffer.class);

    /** Redis Key 前缀 */
    private static final String KEY_PREFIX = "ai:series:mnk-v2:";

    /** 当前连续窗口最后一条设备数据的时间。 */
    private static final String LAST_SAMPLE_PREFIX = "ai:last-sample:mnk-v2:";

    /** 滑动窗口默认大小 */
    private static final int DEFAULT_WINDOW_SIZE = 5;

    private final StringRedisTemplate redis;
    private final LongSupplier epochSeconds;

    @Autowired
    public TimeSeriesBuffer(StringRedisTemplate redis) {
        this(redis, () -> java.time.Instant.now().getEpochSecond());
    }

    TimeSeriesBuffer(StringRedisTemplate redis, LongSupplier epochSeconds) {
        this.redis = redis;
        this.epochSeconds = epochSeconds;
    }

    // ============================================================
    // 写入：追加特征向量到 Redis List
    // ============================================================

    /**
     * 将一条特征向量追加到设备时序缓冲区。
     *
     * @param deviceId 设备编号
     * @param features 5 维特征数组
     */
    public void append(String deviceId, double[] features) {
        append(deviceId, features, DEFAULT_WINDOW_SIZE);
    }

    /**
     * 将一条特征向量追加到设备时序缓冲区（可指定窗口大小）。
     *
     * @param deviceId 设备编号
     * @param features 5 维特征数组
     * @param windowSize 窗口大小
     */
    public void append(String deviceId, double[] features, int windowSize) {
        String key = KEY_PREFIX + deviceId;
        String json = featuresToJson(features);

        try {
            // LPUSH: 新数据放在列表头部
            redis.opsForList().leftPush(key, json);
            // LTRIM: 只保留最近 windowSize 条
            redis.opsForList().trim(key, 0, windowSize - 1);

            LOGGER.debug("[TS-Buffer] 追加 deviceId={} features={} windowSize={}",
                    deviceId, json, windowSize);
        } catch (Exception e) {
            LOGGER.error("[TS-Buffer] Redis 写入失败 deviceId={}: {}", deviceId, e.getMessage());
        }
    }

    /**
     * 追加一条样本，并确保窗口内设备时间连续。
     */
    public boolean appendContinuous(String deviceId, double[] features,
                                    int windowSize, long maxGapSeconds) {
        return appendContinuous(deviceId, features, epochSeconds.getAsLong(),
                windowSize, maxGapSeconds);
    }

    public boolean appendContinuous(String deviceId, double[] features,
                                    Long sampleEpochSeconds,
                                    int windowSize, long maxGapSeconds) {
        return appendContinuousInternal(deviceId, features, sampleEpochSeconds,
                windowSize, maxGapSeconds, null);
    }

    /**
     * 追加四个基础特征，并以设备时间差生成第五个特征。
     */
    public boolean appendContinuousWithInterval(String deviceId, double[] baseFeatures,
                                                Long sampleEpochSeconds,
                                                int windowSize, long maxGapSeconds,
                                                double firstIntervalSeconds) {
        if (baseFeatures == null || baseFeatures.length != 4) {
            throw new IllegalArgumentException("baseFeatures must contain exactly 4 values");
        }
        double[] features = Arrays.copyOf(baseFeatures, 5);
        return appendContinuousInternal(deviceId, features, sampleEpochSeconds,
                windowSize, maxGapSeconds, firstIntervalSeconds);
    }

    private boolean appendContinuousInternal(String deviceId, double[] features,
                                             Long sampleEpochSeconds,
                                             int windowSize, long maxGapSeconds,
                                             Double firstIntervalSeconds) {
        String key = KEY_PREFIX + deviceId;
        String lastSampleKey = LAST_SAMPLE_PREFIX + deviceId;
        boolean reset = false;

        try {
            Long size = redis.opsForList().size(key);
            String previousValue = redis.opsForValue().get(lastSampleKey);
            long gapSeconds = Long.MIN_VALUE;

            if (size != null && size > 0) {
                if (sampleEpochSeconds == null || previousValue == null) {
                    reset = true;
                } else {
                    try {
                        gapSeconds = sampleEpochSeconds - Long.parseLong(previousValue);
                        reset = gapSeconds < 0 || gapSeconds > maxGapSeconds;

                        if (gapSeconds == 0) {
                            String previousJson = redis.opsForList().index(key, 0);
                            List<Double> previousFeatures = previousJson == null
                                    ? java.util.Collections.emptyList()
                                    : jsonToFeatures(previousJson);
                            if (previousFeatures.size() >= 2
                                    && Double.compare(previousFeatures.get(1), features[1]) != 0) {
                                reset = true;
                                LOGGER.warn("[TS-Buffer] same-timestamp floor conflict deviceId={} previousFloor={} currentFloor={}",
                                        deviceId, previousFeatures.get(1), features[1]);
                            }
                        }
                    } catch (NumberFormatException ignored) {
                        reset = true;
                    }
                }
            }

            if (reset) {
                redis.delete(key);
                size = 0L;
                LOGGER.info("[TS-Buffer] reset discontinuous window deviceId={} maxGapSeconds={}",
                        deviceId, maxGapSeconds);
            }

            if (firstIntervalSeconds != null) {
                double intervalSeconds = firstIntervalSeconds;
                if (!reset && size != null && size > 0 && gapSeconds > 0) {
                    intervalSeconds = gapSeconds;
                }
                features[4] = intervalSeconds;
            }

            String json = featuresToJson(features);
            redis.opsForList().leftPush(key, json);
            redis.opsForList().trim(key, 0, windowSize - 1L);
            if (sampleEpochSeconds == null) {
                redis.delete(lastSampleKey);
            } else {
                redis.opsForValue().set(lastSampleKey, String.valueOf(sampleEpochSeconds));
            }
            return reset;
        } catch (Exception e) {
            LOGGER.error("[TS-Buffer] continuous append failed deviceId={}: {}",
                    deviceId, e.getMessage());
            return reset;
        }
    }

    // ============================================================
    // 读取：取出指定设备的完整窗口序列
    // ============================================================

    /**
     * 读取设备的最新时序窗口（按时间从旧到新排列）。
     *
     * @param deviceId 设备编号
     * @return 二维数组 {@code [时间步数, 5]}；若数据不足返回空列表
     */
    public List<List<Double>> readWindow(String deviceId) {
        return readWindow(deviceId, DEFAULT_WINDOW_SIZE);
    }

    /**
     * 读取设备的最新时序窗口。
     *
     * @param deviceId  设备编号
     * @param minLength 最小长度要求（不足则返回空）
     * @return 二维数组；数据不足返回空列表
     */
    public List<List<Double>> readWindow(String deviceId, int minLength) {
        String key = KEY_PREFIX + deviceId;

        try {
            Long size = redis.opsForList().size(key);
            if (size == null || size < minLength) {
                LOGGER.debug("[TS-Buffer] 数据不足 deviceId={} size={} minLength={}",
                        deviceId, size, minLength);
                return java.util.Collections.emptyList();
            }

            // 只读取模型需要的固定长度，避免旧尖峰持续污染得分。
            List<String> rawList = redis.opsForList().range(key, 0, minLength - 1L);
            if (rawList == null || rawList.isEmpty()) {
                return java.util.Collections.emptyList();
            }

            // 反序：Redis List 头部是最新数据，模型需要从旧到新
            List<String> ordered = new ArrayList<>(rawList);
            java.util.Collections.reverse(ordered);

            // 解析 JSON 数组
            List<List<Double>> result = ordered.stream()
                    .map(this::jsonToFeatures)
                    .collect(Collectors.toList());

            LOGGER.debug("[TS-Buffer] 读取窗口 deviceId={} size={}", deviceId, result.size());
            return result;

        } catch (Exception e) {
            LOGGER.error("[TS-Buffer] Redis 读取失败 deviceId={}: {}", deviceId, e.getMessage());
            return java.util.Collections.emptyList();
        }
    }

    /**
     * 获取当前窗口大小。
     */
    public long size(String deviceId) {
        try {
            Long s = redis.opsForList().size(KEY_PREFIX + deviceId);
            return s != null ? s : 0;
        } catch (Exception e) {
            return 0;
        }
    }

    // ============================================================
    // 特征提取工具方法
    // ============================================================

    /**
     * 门状态字符串 → 数值。
     * <pre>
     *   "00" 关门到位 → 0
     *   "01" 开门到位 → 1
     *   "02" 关门中   → 2
     *   "03" 开门中   → 3
     * </pre>
     */
    public static double parseDoorStatus(String doorStatus) {
        if (doorStatus == null || doorStatus.isEmpty()) return 0;
        try { return Integer.parseInt(doorStatus); } catch (NumberFormatException e) { return 0; }
    }

    /**
     * 楼层字符串 → 数值。
     */
    public static double parseFloor(String floor) {
        if (floor == null || floor.isEmpty()) return 0;
        try { return Integer.parseInt(floor); } catch (NumberFormatException e) { return 0; }
    }

    /**
     * 方向字符串 → 数值。
     * <pre>
     *   "00" 平层 → 0
     *   "01" 上行 → 1
     *   "02" 下行 → 2
     *   "03" 硬件故障 → 3
     * </pre>
     */
    public static double parseDirection(String direction) {
        if (direction == null || direction.isEmpty()) return 0;
        try { return Integer.parseInt(direction); } catch (NumberFormatException e) { return 0; }
    }

    /**
     * 乘客状态 → 数值。
     * <pre>
     *   "00" 无人 → 0
     *   "01" 有人 → 1
     * </pre>
     */
    public static double parsePassenger(String passenger) {
        if (passenger == null || passenger.isEmpty()) return 0;
        try { return Integer.parseInt(passenger); } catch (NumberFormatException e) { return 0; }
    }

    // ============================================================
    // JSON 序列化/反序列化
    // ============================================================

    /** 将 5 维特征数组序列化为 JSON 字符串。 */
    private String featuresToJson(double[] features) {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < features.length; i++) {
            if (i > 0) sb.append(",");
            sb.append(features[i]);
        }
        sb.append("]");
        return sb.toString();
    }

    /** 将 JSON 字符串反序列化为特征列表。 */
    private List<Double> jsonToFeatures(String json) {
        // 简单解析 "[1.0,2.0,3.0,4.0,5.0]"
        String content = json.replaceAll("[\\[\\]\\s]", "");
        String[] parts = content.split(",");
        List<Double> result = new ArrayList<>(parts.length);
        for (String p : parts) {
            if (!p.isEmpty()) {
                try {
                    result.add(Double.parseDouble(p));
                } catch (NumberFormatException e) {
                    result.add(0.0);
                }
            }
        }
        return result;
    }
}
