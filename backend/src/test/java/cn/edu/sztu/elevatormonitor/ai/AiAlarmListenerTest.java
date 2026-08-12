package cn.edu.sztu.elevatormonitor.ai;

import cn.edu.sztu.elevatormonitor.domain.event.ElevatorEvent;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class AiAlarmListenerTest {

    @Test
    void activeRuleAlarmPausesModelCollection() {
        TimeSeriesBuffer buffer = mock(TimeSeriesBuffer.class);
        AiPredictClient predictClient = mock(AiPredictClient.class);
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        AiRuleFusionService fusionService = mock(AiRuleFusionService.class);
        ElevatorEvent event = mock(ElevatorEvent.class);

        when(event.getDeviceId()).thenReturn("device-1");
        when(fusionService.isActive("device-1")).thenReturn(true);

        AiAlarmListener listener = new AiAlarmListener(
                buffer, predictClient, redis, fusionService);
        listener.onElevatorEvent(event);

        verify(fusionService).isActive("device-1");
        verifyNoInteractions(buffer);
        verifyNoInteractions(predictClient);
        verifyNoInteractions(redis);
    }
}
