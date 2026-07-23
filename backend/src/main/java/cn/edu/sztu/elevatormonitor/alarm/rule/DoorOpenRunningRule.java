package cn.edu.sztu.elevatormonitor.alarm.rule;

import cn.edu.sztu.elevatormonitor.alarm.AlarmLevel;
import cn.edu.sztu.elevatormonitor.alarm.AlarmRule;
import cn.edu.sztu.elevatormonitor.alarm.DeviceState;
import cn.edu.sztu.elevatormonitor.entity.AlarmEvent;
import cn.edu.sztu.elevatormonitor.entity.ElevatorMessage;
import org.springframework.stereotype.Component;

/**
 * 规则: 开门运行。
 * 电梯在运行过程中（方向非平层00）轿厢门处于开门到位状态(01)且速度>0.01m/s时触发告警，
 * 这是严重的安全隐患。
 */
@Component
public class DoorOpenRunningRule implements AlarmRule {

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

        // 仅在电梯运动中检测（方向非平层00）
        if (dir == null || "00".equals(dir)) {
            return null;
        }

        // 排除硬件故障方向码 "03"（由 HardwareFaultRule 单独处理）
        if ("03".equals(dir)) {
            return null;
        }

        // 速度阈值: 必须>0.01m/s（排除极低速停车时的误报）
        // speed=-1 表示速度未计算，此时仅凭方向+门状态判断
        if (speed >= 0 && speed <= 0.01) {
            return null;
        }

        // 门处于开门到位(01)状态 → 开门运行告警
        if ("01".equals(door)) {
            String passengerInfo = "01".equals(msg.getPassenger()) ? "（有乘客）" : "";
            return AlarmEvent.fire(msg.getDeviceId(), ruleName(), level(), description(),
                    "门状态=开门到位" + passengerInfo + ", 方向=" + dir
                            + ", 速度=" + msg.getSpeed() + "m/s, 位于" + msg.getCurrentFloor() + "楼",
                    msg.getCurrentFloor(), msg.getSpeed());
        }
        return null;
    }
}
