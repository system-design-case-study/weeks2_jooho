package com.nearbyfreinds.service;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nearbyfreinds.dto.Location;

@Service
public class LocationCacheService {

    private static final long LOCATION_TTL_SECONDS = 600;
    private static final String KEY_PREFIX = "location:user:";

    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;

    public LocationCacheService(
            @Qualifier("cacheRedisTemplate") StringRedisTemplate redisTemplate,
            ObjectMapper objectMapper) {
        this.redisTemplate = redisTemplate;
        this.objectMapper = objectMapper;
    }

    /**
     * @param userId 사용자 ID
     * @param x X 좌표
     * @param y Y 좌표
     * @param timestamp 타임스탬프
     */
    public void setLocation(String userId, int x, int y, long timestamp) {
        String key = KEY_PREFIX + userId;
        String value = serialize(new Location(x, y, timestamp));
        redisTemplate.opsForValue().set(key, value, LOCATION_TTL_SECONDS, TimeUnit.SECONDS);
    }

    /**
     * @param userId 사용자 ID
     * @return 위치 정보, 없으면 Optional.empty()
     */
    public Optional<Location> getLocation(String userId) {
        String key = KEY_PREFIX + userId;
        String value = redisTemplate.opsForValue().get(key);
        if (value == null) {
            return Optional.empty();
        }
        return Optional.of(deserialize(value));
    }

    /**
     * @param userIds 사용자 ID 목록
     * @return 존재하는 사용자의 위치 맵 (없는 키는 제외)
     */
    public Map<String, Location> getLocations(List<String> userIds) {
        if (userIds == null || userIds.isEmpty()) {
            return Map.of();
        }

        List<String> keys = userIds.stream()
                .map(id -> KEY_PREFIX + id)
                .toList();

        List<String> values = redisTemplate.opsForValue().multiGet(keys);
        if (values == null) {
            return Map.of();
        }

        Map<String, Location> result = new HashMap<>();
        for (int i = 0; i < userIds.size(); i++) {
            String value = values.get(i);
            if (value != null) {
                result.put(userIds.get(i), deserialize(value));
            }
        }
        return result;
    }

    /**
     * @param userId 사용자 ID
     */
    public void removeLocation(String userId) {
        String key = KEY_PREFIX + userId;
        redisTemplate.delete(key);
    }

    private String serialize(Location location) {
        try {
            return objectMapper.writeValueAsString(location);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("위치 정보 직렬화 실패", e);
        }
    }

    private Location deserialize(String json) {
        try {
            return objectMapper.readValue(json, Location.class);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("위치 정보 역직렬화 실패", e);
        }
    }
}
