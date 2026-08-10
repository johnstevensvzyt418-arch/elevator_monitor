package cn.edu.sztu.elevatormonitor.ai;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.redis.core.HashOperations;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class AiRuleFusionServiceTest {

    private static final String DEVICE_ID = "device-1";
    private static final String ACTIVE_KEY = "ai:rule-fusion:active:" + DEVICE_ID;

    private TimeSeriesBuffer buffer;
    private StringRedisTemplate redis;
    private ValueOperations<String, String> values;
    private HashOperations<String, Object, Object> hashes;
    private AiRuleFusionService service;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        buffer = mock(TimeSeriesBuffer.class);
        redis = mock(StringRedisTemplate.class);
        values = mock(ValueOperations.class);
        hashes = mock(HashOperations.class);
        when(redis.opsForValue()).thenReturn(values);
        when(redis.opsForHash()).thenReturn(hashes);
        service = new AiRuleFusionService(buffer, redis, true, 90.0, 10.0);
    }

    @Test
    void firstRuleAlarmPublishesCompositeScoreAndClearsWindow() {
        when(values.get(ACTIVE_KEY)).thenReturn(null);
        when(hashes.get("elevator:ai_result", DEVICE_ID))
                .thenReturn("{\"score\":42.5,\"source\":\"MODEL\"}");

        service.updateAlarmState(DEVICE_ID, "DOOR_OPEN_TOO_LONG");

        verify(values).set(ACTIVE_KEY, "DOOR_OPEN_TOO_LONG");
        verify(buffer).clear(DEVICE_ID);
        ArgumentCaptor<Object> result = ArgumentCaptor.forClass(Object.class);
        verify(hashes).put(eq("elevator:ai_result"), eq(DEVICE_ID), result.capture());
        String json = result.getValue().toString();
        assertTrue(json.contains("\"score\":100.0000"));
        assertTrue(json.contains("\"modelScore\":42.5000"));
        assertTrue(json.contains("\"source\":\"RULE_FUSION\""));
        assertTrue(json.contains("\"alarmCode\":\"DOOR_OPEN_TOO_LONG\""));
        verify(redis).convertAndSend("elevator:ai_result", json);
    }

    @Test
    void persistentAlarmDoesNotPublishDuplicateHistoryPoint() {
        when(values.get(ACTIVE_KEY)).thenReturn("LEVELING_TIMEOUT");

        service.updateAlarmState(DEVICE_ID, "LEVELING_TIMEOUT");

        verifyNoInteractions(buffer);
        verify(redis, never()).convertAndSend(eq("elevator:ai_result"), org.mockito.ArgumentMatchers.any());
    }

    @Test
    void alarmClearResetsWindowAndPublishesCollectingState() {
        when(values.get(ACTIVE_KEY)).thenReturn("LEVELING_TIMEOUT");

        service.updateAlarmState(DEVICE_ID, "");

        verify(buffer).clear(DEVICE_ID);
        verify(redis).delete(ACTIVE_KEY);
        verify(hashes).delete("elevator:ai_alarm", DEVICE_ID);
        ArgumentCaptor<Object> result = ArgumentCaptor.forClass(Object.class);
        verify(hashes).put(eq("elevator:ai_result"), eq(DEVICE_ID), result.capture());
        String json = result.getValue().toString();
        assertTrue(json.contains("\"state\":\"COLLECTING\""));
        assertTrue(json.contains("\"ready\":false"));
        assertTrue(json.contains("\"sampleCount\":0"));
    }

    @Test
    void normalizationExcludesNormalAndAiOriginatedCodes() {
        assertEquals("DOOR_OPEN_TOO_LONG,LEVELING_TIMEOUT",
                AiRuleFusionService.normalizeAlarmCodes(
                        "AI_ABNORMAL,正常,LEVELING_TIMEOUT,DOOR_OPEN_TOO_LONG,00"));
    }
}
