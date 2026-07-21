package cn.edu.sztu.elevatormonitor.alarm.rule;

import cn.edu.sztu.elevatormonitor.alarm.AlarmLevel;
import cn.edu.sztu.elevatormonitor.alarm.AlarmRule;
import cn.edu.sztu.elevatormonitor.alarm.DeviceState;
import cn.edu.sztu.elevatormonitor.entity.AlarmEvent;
import cn.edu.sztu.elevatormonitor.entity.ElevatorMessage;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * 规则: 低速运行。
 * 电梯处于运行状态（方向非平层00）但速度低于最低阈值时触发告警。
 */
@Component
public class LowSpeedRule implements AlarmRule {

    @Value("${alarm.low-speed.min-mps:0.1}")
    private double minSpeedMps;

    @Override
    public String ruleName() { return "LOW_SPEED"; }

    @Override
    public AlarmLevel level() { return AlarmLevel.WARN; }

    @Override
    public String description() { return "电梯低速运行（低于" + minSpeedMps + "m/s）"; }

    @Override
    public AlarmEvent evaluate(ElevatorMessage msg, DeviceState state) {
        // 仅在电梯实际运动中检测（排除平层00和硬件故障03）
        String dir = msg.getDirection();
        if (dir == null || "00".equals(dir) || "03".equals(dir)) {
            return null;
        }

        double speed = msg.getSpeed();
        if (speed > 0 && speed < minSpeedMps) {
            return AlarmEvent.fire(msg.getDeviceId(), ruleName(), level(), description(),
                    "当前速度=" + speed + "m/s, 低于阈值" + minSpeedMps + "m/s, 方向=" + dir + ", 位于" + msg.getCurrentFloor() + "楼",
                    msg.getCurrentFloor(), speed);
        }
        return null;
    }
}
