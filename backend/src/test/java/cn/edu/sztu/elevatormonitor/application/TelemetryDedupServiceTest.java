package cn.edu.sztu.elevatormonitor.application;

import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class TelemetryDedupServiceTest {

    @Test
    void acceptsFirstFrameAndRejectsDuplicate() {
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        @SuppressWarnings("unchecked")
        ValueOperations<String, String> values = mock(ValueOperations.class);
        when(redis.opsForValue()).thenReturn(values);
        when(values.setIfAbsent(anyString(), eq("1"), eq(Duration.ofSeconds(30))))
                .thenReturn(true)
                .thenReturn(false);
        TelemetryDedupService service = new TelemetryDedupService(redis, 30);

        assertFalse(service.isDuplicate("device-1", "AB CD"));
        assertTrue(service.isDuplicate("device-1", "abcd"));
    }

    @Test
    void failsOpenWhenRedisIsUnavailable() {
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        when(redis.opsForValue()).thenThrow(new IllegalStateException("offline"));
        TelemetryDedupService service = new TelemetryDedupService(redis, 30);

        assertFalse(service.isDuplicate("device-1", "abcd"));
    }
}
