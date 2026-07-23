package cn.edu.sztu.elevatormonitor.application;

import cn.edu.sztu.elevatormonitor.domain.event.ElevatorEvent;
import cn.edu.sztu.elevatormonitor.event.EventPublisher;
import cn.edu.sztu.elevatormonitor.protocol.MNKFrame;
import cn.edu.sztu.elevatormonitor.protocol.MNKParser;
import cn.edu.sztu.elevatormonitor.protocol.ProtocolParseException;
import cn.edu.sztu.elevatormonitor.services.LevelingTrackingService;
import cn.edu.sztu.elevatormonitor.services.SpeedTrackingService;
import cn.edu.sztu.elevatormonitor.services.DistanceTrackingService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

/**
 * MNK 应用服务 — 协议数据上报的统一入口。
 *
 * <h3>职责（严格遵守）</h3>
 * <ol>
 *   <li>调用 {@link MNKParser} 解析协议数据 → {@link MNKFrame}</li>
 *   <li>调用 {@link SpeedTrackingService} / {@link LevelingTrackingService} 填充有状态字段</li>
 *   <li>将 MNKFrame 转换为 {@link ElevatorEvent}</li>
 *   <li>通过 {@link EventPublisher} 发布事件</li>
 * </ol>
 *
 * <h3>禁止事项</h3>
 * <ul>
 *   <li>❌ 不允许包含任何协议解析逻辑</li>
 *   <li>❌ 不允许包含业务规则判断</li>
 * </ul>
 *
 * @author architecture-v2
 * @since 0.2.0
 */
@Service
public class MNKApplicationService {

    private static final Logger LOGGER = LoggerFactory.getLogger(MNKApplicationService.class);

    private final MNKParser parser;
    private final EventPublisher eventPublisher;
    private final SpeedTrackingService speedTrackingService;
    private final LevelingTrackingService levelingTrackingService;
    private final DistanceTrackingService distanceTrackingService;
    private final StringRedisTemplate stringRedisTemplate;

    public MNKApplicationService(MNKParser parser,
                                 EventPublisher eventPublisher,
                                 SpeedTrackingService speedTrackingService,
                                 LevelingTrackingService levelingTrackingService,
                                 DistanceTrackingService distanceTrackingService,
                                 StringRedisTemplate stringRedisTemplate) {
        this.parser = parser;
        this.eventPublisher = eventPublisher;
        this.speedTrackingService = speedTrackingService;
        this.levelingTrackingService = levelingTrackingService;
        this.distanceTrackingService = distanceTrackingService;
        this.stringRedisTemplate = stringRedisTemplate;
    }

    /**
     * 处理 MNK 协议数据上报。
     *
     * <h3>处理流程</h3>
     * <pre>
     *   rawData → MNKParser.parse() → MNKFrame
     *          → SpeedTrackingService 填充速度
     *          → LevelingTrackingService 填充告警
     *          → ElevatorEvent.from(frame)
     *          → EventPublisher.publish(event)
     * </pre>
     *
     * @param rawData    原始 HEX 协议字符串
     * @param reportTime 设备上报时间
     * @param elevatorId 电梯设备ID（保留参数，用于日志）
     * @return 0=成功, -1=报文为空, -2=协议解析异常
     */
    public int handleDataReport(String rawData, String reportTime, String elevatorId) {
        // ---- 1. 空值快速失败 ----
        if (rawData == null || rawData.isEmpty()) {
            LOGGER.error("[MNK-App] 报文为空, elevatorId={}", elevatorId);
            return -1;
        }

        // ---- 2. 协议解析 ----
        MNKFrame frame;
        try {
            frame = parser.parse(rawData, reportTime);
        } catch (ProtocolParseException e) {
            // 打印完整原始报文（含长度），便于排查设备侧发送的异常数据
            LOGGER.error("[MNK-App] 协议解析失败: code={}, msg={}, elevatorId={}, "
                            + "rawLen={}, rawData={}",
                    e.getErrorCode(), e.getMessage(), elevatorId,
                    rawData != null ? rawData.length() : 0,
                    rawData != null && rawData.length() <= 200 ? rawData
                            : (rawData != null ? rawData.substring(0, 200) + "..." : "null"),
                    e);
            return -2;
        }

        // ---- 3. 有状态字段填充（跨请求累积值） ----
        // 注意: 这些服务依赖 Redis，若 Redis 不可用则使用默认值优雅降级
        String deviceId = frame.getDeviceId();
        String currentFloor = frame.getCurrentFloor();
        String targetFloor = frame.getTargetFloor();
        String doorStatus = frame.getDoorStatus();

        // 3a. 跨请求速度计算
        double speed = frame.getSpeed();
        if (speed < 0) {
            try {
                speed = speedTrackingService.calculateAndUpdateSpeed(deviceId, currentFloor, reportTime);
            } catch (Exception e) {
                LOGGER.warn("[MNK-App] 速度计算失败(Redis不可用?), deviceId={}, 使用默认值0", deviceId);
                speed = 0.0;
            }
        }

        // 3b. 跨请求困人检测（平层+有乘客+门打不开超时）
        String alarm = frame.getAlarm();
        if (alarm == null || alarm.isEmpty()) {
            try {
                String levelingAlarm = levelingTrackingService.checkLevelingTimeout(
                        deviceId, currentFloor, targetFloor, doorStatus, frame.getPassenger());
                if (levelingAlarm != null) {
                    alarm = levelingAlarm;
                }
            } catch (Exception e) {
                LOGGER.warn("[MNK-App] 困人检测失败(Redis不可用?), deviceId={}", deviceId);
            }
        }

        // 3c. 跨请求距离/次数累积（修复 V2 路径 distance/times 永远为空的问题）
        int distance = frame.getDistance();
        int times = frame.getTimes();
        try {
            DistanceTrackingService.CumulativeResult cumul =
                    distanceTrackingService.updateAndGet(deviceId, currentFloor);
            distance = cumul.distance;
            times = cumul.times;
        } catch (Exception e) {
            LOGGER.warn("[MNK-App] 累积追踪失败(Redis不可用?), deviceId={}", deviceId);
        }

        // 3d. 门状态修正: 协议"00"表示开关门中, 需结合历史状态判定
        if (doorStatus == null || doorStatus.isEmpty()) {
            // 从 Redis 读取上次确定门状态来区分关门中(02)/开门中(03)
            String lastDoorKey = "elevator:door:" + deviceId;
            Object lastDoorObj = null;
            try {
                lastDoorObj = stringRedisTemplate.opsForValue().get(lastDoorKey);
            } catch (Exception ex) {
                LOGGER.debug("[MNK-App] 读取上次门状态失败(Redis不可用?): {}", ex.getMessage());
            }
            String lastDoor = (lastDoorObj != null) ? lastDoorObj.toString() : "";
            // 上次关门 → 现在开门中; 上次开门 → 现在关门中; 未知 → 默认关门中
            if ("00".equals(lastDoor)) {
                doorStatus = "03"; // 开门中
            } else {
                doorStatus = "02"; // 关门中（含首次未知）
            }
        } else {
            // 确定的门状态(00关门到位/01开门到位) → 记录到 Redis 供后续帧参考
            try {
                stringRedisTemplate.opsForValue().set("elevator:door:" + deviceId, doorStatus);
            } catch (Exception ex) {
                LOGGER.debug("[MNK-App] 记录门状态失败(Redis不可用?): {}", ex.getMessage());
            }
        }

        // ---- 4. 重建 MNKFrame（带填充后的值） ----
        MNKFrame enrichedFrame = MNKFrame.builder()
                .protocolVersion(frame.getProtocolVersion())
                .timestamp(frame.getTimestamp())
                .deviceId(deviceId)
                .elevatorStatus(frame.getElevatorStatus())
                .doorStatus(doorStatus)
                .currentFloor(currentFloor)
                .targetFloor(targetFloor)
                .direction(frame.getDirection())
                .alarm(alarm)
                .passenger(frame.getPassenger())
                .speed(speed)
                .runtime(frame.getRuntime())
                .distance(distance)
                .times(times)
                .rawData(rawData)
                .build();

        // ---- 5. 转换为领域事件 ----
        ElevatorEvent event = ElevatorEvent.from(enrichedFrame);

        // ---- 6. 发布事件 ----
        eventPublisher.publish(event);

        LOGGER.debug("[MNK-App] 处理完成: eventId={}, deviceId={}, floor={}, speed={}, alarm={}",
                event.getEventId(), event.getDeviceId(), event.getCurrentFloor(),
                event.getSpeed(), event.getAlarm());
        return 0;
    }
}
