package cn.edu.sztu.elevatormonitor.application;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.Locale;

/** Prevents one physical MNK report from being processed by two ingress paths. */
@Service
public class TelemetryDedupService {

    private static final Logger LOGGER = LoggerFactory.getLogger(TelemetryDedupService.class);
    private static final String KEY_PREFIX = "telemetry:dedup:mnk:";

    private final StringRedisTemplate redis;
    private final Duration ttl;

    public TelemetryDedupService(StringRedisTemplate redis,
                                 @Value("${telemetry.dedup.ttl-seconds:30}") long ttlSeconds) {
        this.redis = redis;
        this.ttl = Duration.ofSeconds(Math.max(1L, ttlSeconds));
    }

    /** Returns true when this exact raw frame was already accepted recently. */
    public boolean isDuplicate(String deviceId, String rawData) {
        if (deviceId == null || deviceId.isEmpty() || rawData == null || rawData.isEmpty()) {
            return false;
        }

        String normalized = rawData.replaceAll("\\s+", "").toLowerCase(Locale.ROOT);
        String key = KEY_PREFIX + deviceId + ":" + sha256(normalized);
        try {
            Boolean accepted = redis.opsForValue().setIfAbsent(key, "1", ttl);
            return Boolean.FALSE.equals(accepted);
        } catch (Exception e) {
            LOGGER.warn("[Telemetry-Dedup] Redis unavailable, fail open deviceId={}: {}",
                    deviceId, e.getMessage());
            return false;
        }
    }

    private String sha256(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] bytes = digest.digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder result = new StringBuilder(bytes.length * 2);
            for (byte b : bytes) {
                result.append(String.format(Locale.ROOT, "%02x", b & 0xff));
            }
            return result.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is not available", e);
        }
    }
}
