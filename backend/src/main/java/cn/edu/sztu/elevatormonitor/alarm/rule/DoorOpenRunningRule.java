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

        // 速度阈值: 必须 >0.3m/s（排除到站减速残值和停车误报）
        // speed=-1 表示速度未计算，此时保守处理不触发告警，避免误报
        if (speed < 0 || speed <= MIN_SPEED_MPS) {
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
     * 数值化比较两个楼层值，兼容 "01" 与 "1"、"02" 与 "2" 等前导零差异。
     * 与 LevelingTrackingService.floorEquals 逻辑一致。
     */
    private boolean floorsEqual(String f1, String f2) {
        if (f1 == null || f2 == null) return false;
        try {
            return Integer.parseInt(f1) == Integer.parseInt(f2);
        } catch (NumberFormatException e) {
            return f1.equals(f2);
        }
    }
}
