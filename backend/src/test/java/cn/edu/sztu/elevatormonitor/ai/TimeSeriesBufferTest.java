package cn.edu.sztu.elevatormonitor.ai;

import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.ListOperations;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class TimeSeriesBufferTest {

    @Test
    void readWindowReturnsExactlyFiveLatestSamplesInChronologicalOrder() {
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        @SuppressWarnings("unchecked")
        ListOperations<String, String> lists = mock(ListOperations.class);
        when(redis.opsForList()).thenReturn(lists);
        String key = "ai:series:mnk-v2:device-1";
        when(lists.size(key)).thenReturn(8L);
        when(lists.range(key, 0, 4L)).thenReturn(Arrays.asList(
                "[5,5,5,5,5]", "[4,4,4,4,4]", "[3,3,3,3,3]",
                "[2,2,2,2,2]", "[1,1,1,1,1]"));

        List<List<Double>> window = new TimeSeriesBuffer(redis).readWindow("device-1", 5);

        assertEquals(5, window.size());
        assertEquals(Arrays.asList(1.0, 1.0, 1.0, 1.0, 1.0), window.get(0));
        assertEquals(Arrays.asList(5.0, 5.0, 5.0, 5.0, 5.0), window.get(4));
    }

    @Test
    void storesDeviceTimeDeltaAsFeatureFive() {
        StringRedisTemplate redis = mockRedisWithPreviousSample(1L, "1000", null);
        TimeSeriesBuffer buffer = new TimeSeriesBuffer(redis);

        boolean reset = buffer.appendContinuousWithInterval(
                "device-1", new double[]{0, 4, 1, 2}, 1008L, 5, 10, 1.0);

        assertFalse(reset);
        verify(redis.opsForList()).leftPush(
                "ai:series:mnk-v2:device-1", "[0.0,4.0,1.0,2.0,8.0]");
    }

    @Test
    void resetsWhenGapExceedsTenSeconds() {
        StringRedisTemplate redis = mockRedisWithPreviousSample(4L, "1000", null);
        TimeSeriesBuffer buffer = new TimeSeriesBuffer(redis);

        boolean reset = buffer.appendContinuousWithInterval(
                "device-1", new double[]{0, 4, 4, 0}, 1011L, 5, 10, 1.0);

        assertTrue(reset);
        verify(redis).delete("ai:series:mnk-v2:device-1");
        verify(redis.opsForList()).leftPush(
                "ai:series:mnk-v2:device-1", "[0.0,4.0,4.0,0.0,1.0]");
    }

    @Test
    void sameTimestampDifferentFloorStartsNewWindow() {
        StringRedisTemplate redis = mockRedisWithPreviousSample(
                4L, "1000", "[0.0,4.0,1.0,2.0,1.0]");
        TimeSeriesBuffer buffer = new TimeSeriesBuffer(redis);

        boolean reset = buffer.appendContinuousWithInterval(
                "device-1", new double[]{0, 1, 1, 0}, 1000L, 5, 10, 1.0);

        assertTrue(reset);
        verify(redis).delete("ai:series:mnk-v2:device-1");
        verify(redis.opsForList()).leftPush(
                "ai:series:mnk-v2:device-1", "[0.0,1.0,1.0,0.0,1.0]");
    }

    @Test
    void sameTimestampSameFloorUsesTrainingDefaultInterval() {
        StringRedisTemplate redis = mockRedisWithPreviousSample(
                1L, "1000", "[0.0,1.0,1.0,0.0,1.0]");
        TimeSeriesBuffer buffer = new TimeSeriesBuffer(redis);

        boolean reset = buffer.appendContinuousWithInterval(
                "device-1", new double[]{3, 1, 1, 0}, 1000L, 5, 10, 1.0);

        assertFalse(reset);
        verify(redis, never()).delete("ai:series:mnk-v2:device-1");
        verify(redis.opsForList()).leftPush(
                "ai:series:mnk-v2:device-1", "[3.0,1.0,1.0,0.0,1.0]");
    }

    @SuppressWarnings("unchecked")
    private StringRedisTemplate mockRedisWithPreviousSample(
            long size, String previousTime, String previousJson) {
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        ListOperations<String, String> lists = mock(ListOperations.class);
        ValueOperations<String, String> values = mock(ValueOperations.class);
        when(redis.opsForList()).thenReturn(lists);
        when(redis.opsForValue()).thenReturn(values);
        when(lists.size("ai:series:mnk-v2:device-1")).thenReturn(size);
        when(lists.index("ai:series:mnk-v2:device-1", 0)).thenReturn(previousJson);
        when(values.get("ai:last-sample:mnk-v2:device-1")).thenReturn(previousTime);
        return redis;
    }
}
