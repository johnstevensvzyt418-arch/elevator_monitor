package cn.edu.sztu.elevatormonitor.entity;

import cn.edu.sztu.elevatormonitor.utils.GetTimeInSeconds;
import cn.edu.sztu.elevatormonitor.utils.TimeDiff;
import lombok.Data;
import lombok.experimental.Accessors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.LinkedList;
import java.util.Queue;
import java.util.TimeZone;

@Accessors(chain = true)
@Data
public class ElevatorMessage {
    private final static Logger LOGGER = LoggerFactory.getLogger(ElevatorMessage.class);
    private boolean doorFlag = false; // 判断开门中还是关门中
    private Queue<String> timeQueue = new LinkedList<>(); // 时间队列：存储每次特定方向变化或者楼层方向改变的时间点
    private Queue<String> dirtQueue = new LinkedList<>(); // 方向队列：存储方向的变化
    private Queue<String> floorQueue = new LinkedList<>(); // 楼层队列：存储楼层的变化

    private Queue<Calendar> CalendarTime = new LinkedList<>();

    private String deviceId;    // 设备id
    private String elevatorStatus; // 电梯状态
    private String doorStatus;    // 门状态
    private String targetFloor; // 目标楼层
    private String currentFloor; // 当前楼层
    private String direction;   // 电梯方向
    private String passenger = "00";   // 乘客
    private double speed; // 速度
    private String alarm;       // 报警信号
    private long beginTime = 0;
    private String runtime = "0"; // 运行时间
    private int distance = 1000; // 运行里程
    private int times = 100; // 运行次数
    private String levelSeq; // 平层序列

    public ElevatorMessage() {

    }

// 判断方向是否从平层到上行或下行
    public boolean isDirectionChange() {
        if (dirtQueue.size() <= 1) {
            return false;
        }
        String d1 = dirtQueue.poll();
        String d2 = dirtQueue.poll();
        dirtQueue.offer(d1);
        dirtQueue.offer(d2);

        if((d1.equals("00") && d2.equals("01")) || (d1.equals("00") && d2.equals("02"))){
            return true;
        }
        return false;
    }
    // 判断楼层是否变化
    public boolean isFloorChange() {
        if (floorQueue.size() <= 1) {
            return false;
        }

        String f1 = floorQueue.poll();
        String f2 = floorQueue.poll();
        floorQueue.offer(f1);//队列空时  offer不会抛出异常
        floorQueue.offer(f2);

        if(!f1.equals(f2)) {
            return true;
        }
        return false;
    }
    private long levelingTimeA = 0;

    private long levelingTimeB = 0;
    private boolean isRecorded = false; // 是否已经记录
    // 是否是从上行或下行到平层
    public boolean isUpOrDownToLevel() {
        if (dirtQueue.size() <= 1) {
            return false;
        }
        String d1 = dirtQueue.poll();
        String d2 = dirtQueue.poll();
        dirtQueue.offer(d1);
        dirtQueue.offer(d2);
        if(d1!=d2) return true;
        if((d1.equals("01") && d2.equals("00")) || (d1.equals("02") && d2.equals("00"))) {
            return true;
        }

        return false;
    }
    // 计算速度
    private void calculateSpeed() {
        if (timeQueue.size() == 2) {
            String t1 = timeQueue.poll();
            String t2 = timeQueue.poll();
            float secondDiff = TimeDiff.getSecondDiff(t1, t2);
            LOGGER.debug(""+secondDiff);
            speed = (double) Math.round((2.8/secondDiff)*100)/100;// 保留两位小数
            timeQueue.offer(t1);
            timeQueue.offer(t2);
            LOGGER.debug("speed "+speed);
        }
        else {
            speed = 0.0;
        }
    }
    // 更新楼层、方向、及时间并计算速度
    public void updateFloorAndDirectionAndTime(String time) {
        dirtQueue.offer(getDirection());
        if (dirtQueue.size() == 3) {
            dirtQueue.poll();
        }
        floorQueue.offer(getCurrentFloor());
        if (floorQueue.size() == 3) {
            floorQueue.poll();
        }
        // 楼层改变或者方向改变则加入时间
        if (isDirectionChange() || isFloorChange()) {
            timeQueue.offer(time);
        }
        if (timeQueue.size() == 3) {
            timeQueue.poll();
        }
        calculateSpeed();//计算运行速度
        if (getDirection().equals("00") ) {
            timeQueue.clear();
            CalendarTime.clear();
            setSpeed(0.0);
        }
    }
    public void updateFloorAndDirectionAndTime(Calendar time)
    {
        LOGGER.debug("floorQueue enter "+getCurrentFloor());
        floorQueue.offer(getCurrentFloor());//当前楼层入队
        if (floorQueue.size() == 3) {
            floorQueue.poll();
        }

        if(isFloorChange())//楼层改变 记录楼层改变时间
        {
            CalendarTime.offer(time);
        }

        if (CalendarTime.size() == 3) {
            CalendarTime.poll();
        }

        calculateSpeed();
        if (getDirection().equals("00")) {
            CalendarTime.clear();
            setSpeed(0.0);
        }
    }
    // 乘客：开门到位 → 乘客已离开；门未开 + 有内招 → 有乘客
    public void setPassenger() {
        if ("01".equals(getDoorStatus())) {
            setPassenger("00"); // 开门到位 → 乘客已离开
        } else if (!"无".equals(targetFloor)) {
            setPassenger("01"); // 门未开 + 有内招 → 有乘客
        } else {
            setPassenger("00");
        }
    }
    // 报警
    public void setMalfunction(long t) {
        if (isUpOrDownToLevel() ) {
            setLevelingTimeA(t);
            LOGGER.debug("timeA = {}", t);
            setRecorded(true);
        }
        if (isRecorded() && getDoorStatus().equals("01")) {
            setRecorded(false);
        }
        // 默认正常，仅在检测到异常时覆盖
        setAlarm("正常");
        if (isRecorded()) {
            setLevelingTimeB(t);
            LOGGER.debug("timeA = {}", getLevelingTimeA());
            LOGGER.debug("timeB = {}", getLevelingTimeB());
            if (getLevelingTimeB() - getLevelingTimeA() > 5) {
                setAlarm("00");  // 平层超时告警
            }
        }
        // 修复: 移除末尾无条件覆盖 setAlarm("正常")，使异常告警标识得以保留
    }

    // 运行时间
    public void setRuntime(String time) {
        long curTime = GetTimeInSeconds.getYMDSeconds(time);
        LOGGER.debug("curTime:{}, beginTime:{}", curTime, beginTime);
        long intervals = curTime - getBeginTime();
        runtime = GetTimeInSeconds.formatDateTime(intervals);
    }
    public void setRuntime(Calendar calendar)
    {
        Calendar calendar2 = Calendar.getInstance(TimeZone.getTimeZone("GMT+08:00"));
        SimpleDateFormat sdf = new SimpleDateFormat("yyyy/MM/dd HH:mm:ss");
        sdf.setTimeZone(TimeZone.getTimeZone("GMT+08:00")); // 设置时区为 GMT+08:00
        String formattedDate = sdf.format(calendar2.getTime());
        setRuntime(formattedDate);
    }

    // 运行里程 楼层变化 +1
    public void setDistance() {
        if (isFloorChange()) {
            distance++;
        }
        LOGGER.debug("mileage:{}", distance);
    }

    // 运行次数 每次平层 +1
    public void setTimes() {
        times++;
        LOGGER.debug("NumbersOfRuns:{}", times);
    }

    // ==================== 显式 Getters / Setters ====================
    // 注意: 类上保留 @Data，以下方法与 Lombok 生成互补。
    // Lombok 会跳过已存在的方法，因此显式定义不会导致重复。
    // 当 IDE/构建环境 Lombok 注解处理不可用时，以下方法保证编译通过。

    // --- deviceId ---
    public String getDeviceId() { return deviceId; }
    public void setDeviceId(String deviceId) { this.deviceId = deviceId; }

    // --- elevatorStatus ---
    public String getElevatorStatus() { return elevatorStatus; }
    public void setElevatorStatus(String elevatorStatus) { this.elevatorStatus = elevatorStatus; }

    // --- doorStatus ---
    public String getDoorStatus() { return doorStatus; }
    public void setDoorStatus(String doorStatus) { this.doorStatus = doorStatus; }

    // --- targetFloor ---
    public String getTargetFloor() { return targetFloor; }
    public void setTargetFloor(String targetFloor) { this.targetFloor = targetFloor; }

    // --- currentFloor ---
    public String getCurrentFloor() { return currentFloor; }
    public void setCurrentFloor(String currentFloor) { this.currentFloor = currentFloor; }

    // --- direction ---
    public String getDirection() { return direction; }
    public void setDirection(String direction) { this.direction = direction; }

    // --- passenger ---
    // 注: setPassenger() 无参版本为业务方法（见上方），此处提供标准 setter
    public String getPassenger() { return passenger; }
    public void setPassenger(String passenger) { this.passenger = passenger; }

    // --- speed ---
    public double getSpeed() { return speed; }
    public void setSpeed(double speed) { this.speed = speed; }

    // --- alarm ---
    public String getAlarm() { return alarm; }
    public void setAlarm(String alarm) { this.alarm = alarm; }

    // --- beginTime ---
    public long getBeginTime() { return beginTime; }
    public void setBeginTime(long beginTime) { this.beginTime = beginTime; }

    // --- runtime ---
    // 注: setRuntime(String) 为业务方法（见上方），此处仅提供 getter
    public String getRuntime() { return runtime; }

    // --- distance ---
    // 注: setDistance() 无参版本为业务方法（见上方），此处提供标准 getter/setter
    public int getDistance() { return distance; }
    public void setDistance(int distance) { this.distance = distance; }

    // --- times ---
    // 注: setTimes() 无参版本为业务方法（见上方），此处提供标准 getter/setter
    public int getTimes() { return times; }
    public void setTimes(int times) { this.times = times; }

    // --- levelSeq ---
    public String getLevelSeq() { return levelSeq; }
    public void setLevelSeq(String levelSeq) { this.levelSeq = levelSeq; }

    // --- doorFlag (boolean → isDoorFlag 规范) ---
    public boolean isDoorFlag() { return doorFlag; }
    public void setDoorFlag(boolean doorFlag) { this.doorFlag = doorFlag; }

    // --- timeQueue ---
    public Queue<String> getTimeQueue() { return timeQueue; }
    public void setTimeQueue(Queue<String> timeQueue) { this.timeQueue = timeQueue; }

    // --- dirtQueue ---
    public Queue<String> getDirtQueue() { return dirtQueue; }
    public void setDirtQueue(Queue<String> dirtQueue) { this.dirtQueue = dirtQueue; }

    // --- floorQueue ---
    public Queue<String> getFloorQueue() { return floorQueue; }
    public void setFloorQueue(Queue<String> floorQueue) { this.floorQueue = floorQueue; }

    // --- CalendarTime ---
    public Queue<Calendar> getCalendarTime() { return CalendarTime; }
    public void setCalendarTime(Queue<Calendar> CalendarTime) { this.CalendarTime = CalendarTime; }

    // --- levelingTimeA ---
    public long getLevelingTimeA() { return levelingTimeA; }
    public void setLevelingTimeA(long levelingTimeA) { this.levelingTimeA = levelingTimeA; }

    // --- levelingTimeB ---
    public long getLevelingTimeB() { return levelingTimeB; }
    public void setLevelingTimeB(long levelingTimeB) { this.levelingTimeB = levelingTimeB; }

    // --- isRecorded (boolean → isRecorded 规范) ---
    public boolean isRecorded() { return isRecorded; }
    public void setRecorded(boolean isRecorded) { this.isRecorded = isRecorded; }

    @Override
    public String toString() {
        return "ElevatorMessage{" +
                "deviceId='" + deviceId + '\'' +
                ", elevatorStatus='" + elevatorStatus + '\'' +
                ", doorStatus='" + doorStatus + '\'' +
                ", targetFloor='" + targetFloor + '\'' +
                ", currentFloor='" + currentFloor + '\'' +
                ", direction='" + direction + '\'' +
                ", passenger='" + passenger + '\'' +
                ", speed=" + speed +
                ", alarm='" + alarm + '\'' +
                ", runtime='" + runtime + '\'' +
                ", distance=" + distance +
                ", times=" + times +
                '}';
    }
}
