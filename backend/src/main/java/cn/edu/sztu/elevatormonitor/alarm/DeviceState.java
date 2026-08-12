package cn.edu.sztu.elevatormonitor.alarm;

import java.time.Instant;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 单台设备的运行时状态 — 供有状态告警规则使用。
 *
 * 线程安全:
 *   - 引用获取由 DeviceStateStore (Caffeine Cache) 保证
 *   - 内部 activeAlarms 使用 ConcurrentHashMap.newKeySet() 自保
 *   - 其他字段 (Instant/String) 的读写依赖 Caffeine 单 entry 串行化语义
 */
public class DeviceState {

    /** 设备ID */
    private final String deviceId;

    // ---- 时间戳 ----
    /** 最后一次楼层变化的时间 */
    private Instant lastMoveTime;
    /** 最后一次关门到位的时间 */
    private Instant lastDoorClosedTime;
    /** 最后一次收到消息的时间 */
    private Instant lastMessageTime;

    // ---- 楼层追踪 ----
    /** 上一次的楼层值，供 DirectionMismatchRule 使用 */
    private String previousFloor;
    /** 上一次的方向，供 DirectionMismatchRule 使用 */
    private String previousDirection;
    /** LongIdleRule 专用：上一次检测时的楼层，用于判断电梯是否真正移动 */
    private String lastFloorForIdleCheck;
    /** FloorJumpRule 专用：上一次的楼层值，用于检测跳变（独立字段避免规则间状态污染） */
    private String previousFloorForJump;
    /** DoorOpenRunningRule 专用：上一次的楼层值，用于判断电梯是否真正在楼层间移动 */
    private String previousFloorForDoorOpen;
    /** DoorOpenRunningRule 专用：最近一次楼层变化时间，用于"移动窗口"判定 */
    private Instant lastFloorChangeTimeForDoorOpen;

    // ---- 告警去重 ----
    /** 当前处于激活状态的告警规则名集合 (ConcurrentHashMap.newKeySet 保证线程安全) */
    private final Set<String> activeAlarms = ConcurrentHashMap.newKeySet();

    public DeviceState(String deviceId) {
        this.deviceId = deviceId;
    }

    // ==================== 时间戳操作 ====================

    public void markMoved() {
        this.lastMoveTime = Instant.now();
    }

    public void markDoorClosed() {
        this.lastDoorClosedTime = Instant.now();
    }

    public void markMessageReceived() {
        this.lastMessageTime = Instant.now();
    }

    // ==================== 楼层 & 方向追踪 ====================

    public void updateFloorAndDirection(String floor, String direction) {
        this.previousFloor = this.previousFloor == null ? floor : this.previousFloor;
        this.previousDirection = this.previousDirection == null ? direction : this.previousDirection;
    }

    // ==================== 告警生命周期 ====================

    /** @return true 若此告警尚未激活（首次触发） */
    public boolean activateAlarm(String ruleName) {
        return activeAlarms.add(ruleName);
    }

    /** @return true 若此告警之前处于激活状态 */
    public boolean deactivateAlarm(String ruleName) {
        return activeAlarms.remove(ruleName);
    }

    public boolean isAlarmActive(String ruleName) {
        return activeAlarms.contains(ruleName);
    }

    public Set<String> getActiveAlarms() {
        return activeAlarms;
    }

    // ==================== Getters ====================

    public String getDeviceId() { return deviceId; }
    public Instant getLastMoveTime() { return lastMoveTime; }
    public Instant getLastDoorClosedTime() { return lastDoorClosedTime; }
    public Instant getLastMessageTime() { return lastMessageTime; }
    public String getPreviousFloor() { return previousFloor; }
    public String getPreviousDirection() { return previousDirection; }
    public void setPreviousFloor(String floor) { this.previousFloor = floor; }
    public void setPreviousDirection(String dir) { this.previousDirection = dir; }

    /** LongIdleRule 专用楼层追踪 */
    public String getLastFloorForIdleCheck() { return lastFloorForIdleCheck; }
    public void setLastFloorForIdleCheck(String floor) { this.lastFloorForIdleCheck = floor; }

    /** FloorJumpRule 专用楼层追踪 */
    public String getPreviousFloorForJump() { return previousFloorForJump; }
    public void setPreviousFloorForJump(String floor) { this.previousFloorForJump = floor; }

    /** DoorOpenRunningRule 专用楼层追踪 */
    public String getPreviousFloorForDoorOpen() { return previousFloorForDoorOpen; }
    public void setPreviousFloorForDoorOpen(String floor) { this.previousFloorForDoorOpen = floor; }
    public Instant getLastFloorChangeTimeForDoorOpen() { return lastFloorChangeTimeForDoorOpen; }
    public void setLastFloorChangeTimeForDoorOpen(Instant t) { this.lastFloorChangeTimeForDoorOpen = t; }
}
