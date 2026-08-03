package cn.edu.sztu.elevatormonitor.utils;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.Date;

public class TimeDiff {
    private static final Logger LOGGER = LoggerFactory.getLogger(TimeDiff.class);

    public static float getSecondDiff(String t1, String t2) {
        float seconds = 0;
        try {
            // 兼容 "HH:mm:ss" 与 "HH:mm:ss.SSS" 两种格式
            // （设备/模拟器上报的 time 参数多为 "HH:mm:ss"，旧实现用 .SSS 解析必然失败返回0）
            Date date1 = parseTime(t1);
            Date date2 = parseTime(t2);
            seconds = (date2.getTime() - date1.getTime()) / 1000.f;
        } catch (ParseException e) {
            LOGGER.error("[TimeDiff] 时间解析失败, t1={}, t2={}", t1, t2, e);
        }
        return seconds;
    }

    private static Date parseTime(String s) throws ParseException {
        try {
            return new SimpleDateFormat("HH:mm:ss.SSS").parse(s);
        } catch (ParseException e) {
            return new SimpleDateFormat("HH:mm:ss").parse(s);
        }
    }
}
