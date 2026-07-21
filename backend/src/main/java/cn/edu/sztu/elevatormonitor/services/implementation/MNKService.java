package cn.edu.sztu.elevatormonitor.services.implementation;

import cn.edu.sztu.elevatormonitor.entity.ElevatorMessage;
import cn.edu.sztu.elevatormonitor.enums.DestFloorConstant;
import cn.edu.sztu.elevatormonitor.entity.repository.ElevatorMessageRepository;
import cn.edu.sztu.elevatormonitor.services.AlarmService;
import cn.edu.sztu.elevatormonitor.services.ElevatorService;
import cn.edu.sztu.elevatormonitor.services.HistoryPersistenceService;
import cn.edu.sztu.elevatormonitor.services.DistanceTrackingService;
import cn.edu.sztu.elevatormonitor.services.LevelingTrackingService;
import cn.edu.sztu.elevatormonitor.services.SpeedTrackingService;
import cn.edu.sztu.elevatormonitor.utils.GetTimeInSeconds;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

@Service
public class MNKService implements ElevatorService {
    private static final Logger LOGGER = LoggerFactory.getLogger(MNKService.class);

    @Value("${push.endpoint.mnk}")
    private String mnkApi;

    private final ElevatorMessageRepository elevatorMessageRepository;
    private final HistoryPersistenceService historyPersistenceService;
    private final AlarmService alarmService;
    private final SpeedTrackingService speedTrackingService;
    private final LevelingTrackingService levelingTrackingService;
    private final DistanceTrackingService distanceTrackingService;

    public MNKService(ElevatorMessageRepository elevatorMessageRepository,
                      HistoryPersistenceService historyPersistenceService,
                      AlarmService alarmService,
                      SpeedTrackingService speedTrackingService,
                      LevelingTrackingService levelingTrackingService,
                      DistanceTrackingService distanceTrackingService) {
        this.elevatorMessageRepository = elevatorMessageRepository;
        this.historyPersistenceService = historyPersistenceService;
        this.alarmService = alarmService;
        this.speedTrackingService = speedTrackingService;
        this.levelingTrackingService = levelingTrackingService;
        this.distanceTrackingService = distanceTrackingService;
    }

    /** 协议格式: F + 19字节日期 + /8字节ID + 4×16字节HEX段 = 最少92字符(兼容设备侧非标长度) */
    private static final int MNK_MIN_LEN = 92;

    @Override
    public int uploadData(String data,String time ,String elevatorID) {
        // -------------------------------------------------------
        //  第一层: 空值 / 长度预校验
        // -------------------------------------------------------
        if (data == null || data.isEmpty()) {
            LOGGER.error("[MNK] 报文为空, elevatorID={}", elevatorID);
            return -1;
        }
        if (data.length() < MNK_MIN_LEN) {
            LOGGER.error("[MNK] 报文长度不足(期望>={}实际={}), data={}, elevatorID={}",
                    MNK_MIN_LEN, data.length(), data, elevatorID);
            return -2;
        }

        // -------------------------------------------------------
        //  第二层: 协议解析 (try-catch 保护)
        // -------------------------------------------------------
        try {
            // 每次请求新建独立的 ElevatorMessage，避免多电梯并发时的数据污染
            ElevatorMessage elevatorMessage = new ElevatorMessage();
            // F2020/11/10 11:24:46/00000004/000000000000517c 00000000000421b9 30050000000053c3 d00063000000220e
            //                               内招：目标楼层  开关门：              运行：上下行+当前楼层(第二字节05\09\0d\11)
            // F2020/11/10 13:10:10/00000004/00000000001021b6 0100000000005171 d00063000000220e 3509000000005336

            data = data.toLowerCase();
            String s1 = data.substring(30, 46); // 内招信号
            String s2 = data.substring(46, 62); // s1、s2共同判断开关门
            String s3 = data.substring(62, 78);
            String s4 = data.substring(78, 94);

            for (int i = 30; i < 94; i += 16) {
                String tmp = data.substring(i+12, i+14);
                if (tmp.equals("51")) {
                    s1 = data.substring(i, i + 16);
                } else if (tmp.equals("21")) {
                    s2 = data.substring(i, i + 16);
                } else if (tmp.equals("53")) {
                    s3 = data.substring(i, i + 16);
                } else if (tmp.equals("22")) {
                    s4 = data.substring(i, i + 16);
                } else {
                    LOGGER.error("[MNK] 非法信号标识符 tmp={} at offset={}, data={}, elevatorID={}",
                            tmp, i, data, elevatorID);
                    return -2;
                }
            }


            elevatorMessage.setDeviceId(data.substring(21, 29));

            // 开关门信号 ok
            String subS2 = s2.substring(10, 12);
            // "10":关门到位  "00":开关门中  "04":开门到位
            String doorStatus = "";
            switch (subS2) {
                case "04" : doorStatus = "01"; //开门到位
                    elevatorMessage.setDoorFlag(true);
                    break;
                case "10" : doorStatus = "00"; //关门到位
                    elevatorMessage.setDoorFlag(false);
                    break;
                case "00" :
                    if (elevatorMessage.isDoorFlag()) {
                        doorStatus = "02"; // 关门中
                    } else {
                        doorStatus = "03"; // 开门中
                    }
                    break;
                default:
                    LOGGER.error("[MNK] 非法开关门信号 subS2={}, data={}, elevatorID={}",
                            subS2, data, elevatorID);
                    return -2;
            }
            // 设置门状态
            elevatorMessage.setDoorStatus(doorStatus);

            // 目标楼层 ok
            DestFloorConstant destFloorConstant = DestFloorConstant.getDestFloorConstant(s1.charAt(1));
            elevatorMessage.setTargetFloor(destFloorConstant.destFloorDesc);

            // 运行方向 ok
            String dit = s3.substring(0, 2); // 上下行的字节：34 => 上行  35 => 下行
            String direction;
            if (dit.matches("[3][0167]")) {
                direction = "00"; // 平层
            } else if (dit.equals("34")) {
                direction = "01";  // 上行
            } else if (dit.equals("35")) {
                direction = "02"; // 下行
            } else {
                LOGGER.error("[MNK] 非法运行方向 dit={}, data={}, elevatorID={}",
                        dit, data, elevatorID);
                return -2;
            }
            // 当前方向
            elevatorMessage.setDirection(direction);

            // 当前楼层 ok
            String curFloor = s3.substring(2, 4);
            switch (curFloor) {
                case "05" : curFloor = "01";
                    break;
                case "09" : curFloor = "02";
                    break;
                case "0d" : curFloor = "03";
                    break;
                case "11" : curFloor = "04";
                    break;
                default:
                    LOGGER.error("[MNK] 非法楼层信号 curFloor={}, data={}, elevatorID={}",
                            curFloor, data, elevatorID);
                    return -2;
            }
            elevatorMessage.setCurrentFloor(curFloor);

            // 更新方向/楼层队列（保留用于其他业务逻辑）
            elevatorMessage.updateFloorAndDirectionAndTime(time);

            // 基于 Redis 的有状态速度计算（跨请求累积，修复 SPEED_ABNORMAL 无法触发）
            double trackedSpeed = speedTrackingService.calculateAndUpdateSpeed(
                    elevatorMessage.getDeviceId(), curFloor, time);
            if (trackedSpeed > 0.0) {
                elevatorMessage.setSpeed(trackedSpeed);
                LOGGER.debug("[MNK] Redis速度追踪: deviceId={}, speed={}m/s",
                        elevatorMessage.getDeviceId(), trackedSpeed);
            }

            // 乘客
            elevatorMessage.setPassenger();

            //报警（原有请求内逻辑，保留兼容）
            elevatorMessage.setMalfunction(GetTimeInSeconds.getSeconds(time));

            // 基于 Redis 的有状态平层超时检测（跨请求累积，修复 ALARM_FIELD 无法触发）
            String levelingAlarm = levelingTrackingService.checkLevelingTimeout(
                    elevatorMessage.getDeviceId(),
                    elevatorMessage.getCurrentFloor(),
                    elevatorMessage.getTargetFloor(),
                    elevatorMessage.getDoorStatus());
            if (levelingAlarm != null) {
                elevatorMessage.setAlarm(levelingAlarm);
                LOGGER.warn("[MNK] 平层超时告警: deviceId={}, alarm={}",
                        elevatorMessage.getDeviceId(), levelingAlarm);
            }

            // 电梯状态默认为正常00
            elevatorMessage.setElevatorStatus("00");

            // 运行时间
            String date = data.substring(1, 20);
            if (elevatorMessage.getBeginTime() == 0) {
                elevatorMessage.setBeginTime(GetTimeInSeconds.getYMDSeconds(date));
            } else {
                elevatorMessage.setRuntime(date);
            }

            elevatorMessage.setDistance();
            elevatorMessage.setTimes();

            // 用 Redis 跨请求累积值替换硬编码初始值(1000/100)
            DistanceTrackingService.CumulativeResult cumul =
                    distanceTrackingService.updateAndGet(elevatorMessage.getDeviceId(), curFloor);
            elevatorMessage.setDistance(cumul.distance);  // 覆盖旧的 setDistance() 结果
            elevatorMessage.setTimes(cumul.times);         // 覆盖旧的 setTimes() 结果

            LOGGER.debug("[MNK] 解析成功: {}", elevatorMessage);
            // 异步持久化历史数据 (不阻塞实时推送)
            historyPersistenceService.saveAsync(elevatorMessage, time);
            // 异步评估告警规则 (不阻塞实时推送)
            alarmService.evaluateAsync(elevatorMessage);
            return elevatorMessageRepository.sendToFrontEnd(elevatorMessage, mnkApi) ? 0 : 1;

        } catch (NumberFormatException e) {
            LOGGER.error("[MNK] HEX解析失败(非法数值), data={}, time={}, elevatorID={}",
                    data, time, elevatorID, e);
            return -2;
        } catch (IndexOutOfBoundsException e) {
            LOGGER.error("[MNK] 报文长度异常(索引越界), data={}, time={}, elevatorID={}",
                    data, time, elevatorID, e);
            return -2;
        } catch (RuntimeException e) {
            LOGGER.error("[MNK] 协议解析未知异常, data={}, time={}, elevatorID={}",
                    data, time, elevatorID, e);
            return -2;
        }
    }
}
