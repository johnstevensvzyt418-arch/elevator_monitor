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
 *
 * <h3>v0.1.9 增强：楼层变化排除误报</h3>
 * 真实设备上报间隔可能很大（相邻楼层变化帧间隔几十秒），
 * SpeedTrackingService 用 {@code speed = 楼层差×2.8 / 间隔} 计算，
 * 上报间隔大时计算速度严重低估（如 2.8/70=0.04m/s），但电梯实际运行正常。
 * 修复：本帧楼层发生变化（电梯正在跨层移动）→ 排除低速判定；
 * 仅当楼层未变且速度低（电梯原地低速/想动动不了）才触发，避免误报。
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
        // 仅在电梯运动中检测（方向非平层00）
        String dir = msg.getDirection();
        if (dir == null || "00".equals(dir)) {
            return null;
        }

        // 门已打开(01) → 电梯停稳在开门上下客，方向字节残留不代表运行，
        // 不判低速（真实设备到站开门时方向可能未及时归零）
        String door = msg.getDoorStatus();
        if ("01".equals(door)) {
            return null;
        }

        String curFloor = msg.getCurrentFloor();
        // 楼层变化追踪：本帧楼层变化说明电梯正在跨层移动（运行正常）
        String prevFloor = state.getPreviousFloorForLowSpeed();
        boolean floorChanged = curFloor != null && !curFloor.equals(prevFloor);
        if (curFloor != null) {
            state.setPreviousFloorForLowSpeed(curFloor);
        }
        // 电梯本帧楼层变化 → 正在正常移动，排除低速误报
        // （计算速度低是因上报间隔大，非真实低速）
        if (floorChanged) {
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
