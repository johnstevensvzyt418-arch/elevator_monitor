package cn.edu.sztu.elevatormonitor.alarm.rule;

import cn.edu.sztu.elevatormonitor.alarm.AlarmLevel;
import cn.edu.sztu.elevatormonitor.alarm.AlarmRule;
import cn.edu.sztu.elevatormonitor.alarm.DeviceState;
import cn.edu.sztu.elevatormonitor.entity.AlarmEvent;
import cn.edu.sztu.elevatormonitor.entity.ElevatorMessage;
import org.springframework.stereotype.Component;

/**
 * 规则: 开门运行。
 * 电梯在运行过程中（方向非平层00）轿厢门处于开门到位状态(01)时触发告警。
 * 速度阈值 0.3m/s 用于排除到站停车时方向未及时更新导致的误报。
 *
 * <h3>v0.1.6 增强</h3>
 * <ul>
 *   <li>增加楼层校验：当前楼层≠目标楼层时才是真正的"运行中"，停在目标楼层开门不触发</li>
 *   <li>结合 SpeedTrackingService 的缓存速度过期机制，消除困人场景下旧速度残留导致误报</li>
 * </ul>
 */
@Component
public class DoorOpenRunningRule implements AlarmRule {

    /** 最低运行速度阈值(m/s)，低于此值视为已停止，避免残值误报 */
    private static final double MIN_SPEED_MPS = 0.3;

    /**
     * "移动保持窗口"（秒）：告警已激活后，电梯楼层在最近该秒数内变化过则保持告警，
     * 避免"开门运行"期间楼层上报帧恰好未变化导致告警闪烁/误恢复。
     * <p>修复真实设备到站停稳但方向字节未及时归零（仍报 01/02）时，
     * SpeedTrackingService 因 direction≠00 不归零、速度残留导致误报开门运行：
     * 触发必须满足"本帧楼层确实变化"（电梯此刻正在跨层移动）；已停稳（无论方向
     * 字节如何）则楼层不变，不再触发。窗口仅用于已激活告警的保持，不用于触发。</p>
     */
    private static final long MOVING_WINDOW_SECONDS = 5;

    @Override
    public String ruleName() { return "DOOR_OPEN_RUNNING"; }

    @Override
    public AlarmLevel level() { return AlarmLevel.CRITICAL; }

    @Override
    public String description() { return "电梯开门运行（门未关闭时移动）"; }

    @Override
    public AlarmEvent evaluate(ElevatorMessage msg, DeviceState state) {
        String dir = msg.getDirection();
        String door = msg.getDoorStatus();
        double speed = msg.getSpeed();
        String curFloor = msg.getCurrentFloor();
        String targetFloor = msg.getTargetFloor();

        // 仅在电梯运动中检测（方向非平层00）
        if (dir == null || "00".equals(dir)) {
            return null;
        }

        // 排除硬件故障方向码 "03"（由 HardwareFaultRule 单独处理）
        if ("03".equals(dir)) {
            return null;
        }

        // ============ 移动判定（v0.1.7 修复平层速度残留误报） ============
        // 真实设备到站停稳时方向字节可能未及时归零（仍报 01/02），此时
        // SpeedTrackingService 因 direction≠00 不进入平层归零分支，楼层未变
        // 且缓存未过期时速度残留（>0.3），若停靠层≠目标层会误报开门运行。
        // 修复核心：电梯真正"开门运行"时楼层必然正在跨层变化；已停稳则
        // 楼层不变。因此触发必须满足"本帧楼层确实变化"（floorChanged）。
        // 已激活后若楼层短暂未变（上报帧间隔/传感器抖动），用移动窗口保持，
        // 避免告警闪烁；但不会用窗口"重新触发"，从而彻底消除停稳误报。
        String prevFloor = state.getPreviousFloorForDoorOpen();
        boolean floorChanged = curFloor != null && !curFloor.equals(prevFloor);
        if (curFloor != null) {
            state.setPreviousFloorForDoorOpen(curFloor);
        }
        if (floorChanged) {
            state.setLastFloorChangeTimeForDoorOpen(java.time.Instant.now());
        }

        // 告警是否已激活（本规则）
        boolean alreadyActive = state.isAlarmActive(ruleName());

        // 移动窗口内保持：已激活且楼层在窗口内变化过 → 保持告警（本帧未变也不恢复）
        java.time.Instant lastChange = state.getLastFloorChangeTimeForDoorOpen();
        boolean inMovingWindow = lastChange != null
                && java.time.Duration.between(lastChange, java.time.Instant.now()).getSeconds()
                        <= MOVING_WINDOW_SECONDS;

        // 关键判定：
        //  - 楼层本帧变化 → 电梯确实在跨层移动 → 允许触发
        //  - 楼层本帧未变但告警已激活且在窗口内 → 保持（不闪烁、不误恢复）
        //  - 楼层本帧未变且告警未激活 → 电梯已停稳（或首次帧），不触发
        boolean eligibleToTrigger = floorChanged
                || (alreadyActive && inMovingWindow);
        if (!eligibleToTrigger) {
            return null;
        }

        // 速度阈值: 必须 >0.3m/s（排除到站减速残值和停车误报）
        // speed=-1 表示速度未计算，此时保守处理不触发告警，避免误报
        // 注意：已激活保持期间若速度残留仍>0.3 则可继续产生事件；若速度已归零
        // 但仍在移动窗口内，也应保持（返回 fire 由引擎去重，避免闪烁）。
        if (speed < 0 || speed <= MIN_SPEED_MPS) {
            // 速度不满足：仅在已激活保持期返回 fire 维持状态，未激活则不触发
            if (alreadyActive && inMovingWindow) {
                return AlarmEvent.fire(msg.getDeviceId(), ruleName(), level(), description(),
                        "门状态=开门到位, 方向=" + dir + ", 位于" + curFloor + "楼(保持)",
                        curFloor, msg.getSpeed());
            }
            return null;
        }

        // 门处于开门到位(01)状态 → 开门运行告警
        if ("01".equals(door)) {
            // v0.1.6: 校验电梯确实在楼层之间运行（非停在目标楼层开门）
            // 停在目标楼层时开门属于正常操作，不应触发"开门运行"
            // 使用数值比较兼容 "02" 与 "2" 的前导零差异
            if (curFloor != null && targetFloor != null && floorsEqual(curFloor, targetFloor)) {
                return null;
            }

            String passengerInfo = "01".equals(msg.getPassenger()) ? "（有乘客）" : "";
            return AlarmEvent.fire(msg.getDeviceId(), ruleName(), level(), description(),
                    "门状态=开门到位" + passengerInfo + ", 方向=" + dir
                            + ", 速度=" + msg.getSpeed() + "m/s, 位于" + curFloor + "楼",
                    curFloor, msg.getSpeed());
        }
        return null;
    }

    /**
     * 楼层匹配：targetFloor 可能是组合内召（如 "2、3"、"1、2、3"），
     * 按顿号拆分后，当前楼层命中任意一个即视为平层（停在目标层开门属正常操作，
     * 不应触发开门运行告警）。与 LevelingTrackingService.floorEquals 逻辑保持一致。
     */
    private boolean floorsEqual(String f1, String f2) {
        if (f1 == null || f2 == null) return false;
        String[] parts = f2.split("、");
        for (String part : parts) {
            if (floorsEqualSingle(f1, part.trim())) {
                return true;
            }
        }
        return false;
    }

    /**
     * 数值化比较单个楼层，兼容 "01" 与 "1" 等前导零差异。
     */
    private boolean floorsEqualSingle(String f1, String f2) {
        if (f1 == null || f2 == null) return false;
        try {
            return Integer.parseInt(f1) == Integer.parseInt(f2);
        } catch (NumberFormatException e) {
            // 非数字楼层（如 "无", "B1"）退化为字符串比较
            return f1.equals(f2);
        }
    }
}
