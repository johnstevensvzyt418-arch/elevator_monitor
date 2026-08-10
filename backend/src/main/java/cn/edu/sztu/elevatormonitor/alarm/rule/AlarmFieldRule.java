package cn.edu.sztu.elevatormonitor.alarm.rule;

import cn.edu.sztu.elevatormonitor.alarm.AlarmLevel;
import cn.edu.sztu.elevatormonitor.alarm.AlarmRule;
import cn.edu.sztu.elevatormonitor.alarm.DeviceState;
import cn.edu.sztu.elevatormonitor.entity.AlarmEvent;
import cn.edu.sztu.elevatormonitor.entity.ElevatorMessage;
import org.springframework.stereotype.Component;

/**
 * 规则5: 报警字段异常。
 * ElevatorMessage.alarm 字段不是"正常"时触发。
 */
@Component
public class AlarmFieldRule implements AlarmRule {

    @Override
    public String ruleName() { return "ALARM_FIELD"; }

    @Override
    public AlarmLevel level() { return AlarmLevel.WARN; }

    @Override
    public String description() { return "电梯上报报警字段异常"; }

    @Override
    public AlarmEvent evaluate(ElevatorMessage msg, DeviceState state) {
        String alarm = msg.getAlarm();
        // 困人告警(LEVELING_TIMEOUT)由专门机制处理，不重复归为“报警字段异常”
        if (alarm != null && !"正常".equals(alarm) && !"".equals(alarm)
                && !alarm.contains("LEVELING")) {
            return AlarmEvent.fire(msg.getDeviceId(), ruleName(), level(), description(),
                    "报警内容: " + alarm + ", 当前楼层=" + msg.getCurrentFloor(),
                    msg.getCurrentFloor(), msg.getSpeed());
        }
        return null;
    }
}
