package com.nearbyfreinds.service;

import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import org.mockito.Mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nearbyfreinds.dto.Location;

@ExtendWith(MockitoExtension.class)
class LocationCacheServiceTest {

    @Mock
    private StringRedisTemplate redisTemplate;

    @Mock
    private ValueOperations<String, String> valueOperations;

    private LocationCacheService locationCacheService;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @BeforeEach
    void setUp() {
        locationCacheService = new LocationCacheService(redisTemplate, objectMapper);
    }

    @Test
    @DisplayName("위치 저장 후 조회 시 동일한 x, y, timestamp 반환")
    void setAndGetLocation() {
        // given
        String userId = "user-1";
        int x = 500;
        int y = 300;
        long timestamp = 1234567890L;
        String key = "location:user:" + userId;
        String json = "{\"x\":500,\"y\":300,\"timestamp\":1234567890}";

        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.get(key)).thenReturn(json);

        // when
        locationCacheService.setLocation(userId, x, y, timestamp);
        Optional<Location> result = locationCacheService.getLocation(userId);

        // then
        assertThat(result).isPresent();
        assertThat(result.get().x()).isEqualTo(x);
        assertThat(result.get().y()).isEqualTo(y);
        assertThat(result.get().timestamp()).isEqualTo(timestamp);

        verify(valueOperations).set(eq(key), anyString(), eq(600L), eq(TimeUnit.SECONDS));
    }

    @Test
    @DisplayName("배치 조회 시 존재하는 사용자만 결과에 포함")
    void getLocations_onlyExistingUsers() {
        // given
        List<String> userIds = List.of("user-1", "user-2", "user-3");
        List<String> keys = List.of("location:user:user-1", "location:user:user-2", "location:user:user-3");

        String json1 = "{\"x\":100,\"y\":200,\"timestamp\":1000}";
        String json3 = "{\"x\":300,\"y\":400,\"timestamp\":3000}";

        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.multiGet(keys)).thenReturn(Arrays.asList(json1, null, json3));

        // when
        Map<String, Location> result = locationCacheService.getLocations(userIds);

        // then
        assertThat(result).hasSize(2);
        assertThat(result).containsKey("user-1");
        assertThat(result).containsKey("user-3");
        assertThat(result).doesNotContainKey("user-2");

        assertThat(result.get("user-1").x()).isEqualTo(100);
        assertThat(result.get("user-3").x()).isEqualTo(300);
    }

    @Test
    @DisplayName("존재하지 않는 사용자 조회 시 Optional.empty() 반환")
    void getLocation_nonExistentUser() {
        // given
        String userId = "non-existent";
        String key = "location:user:" + userId;

        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.get(key)).thenReturn(null);

        // when
        Optional<Location> result = locationCacheService.getLocation(userId);

        // then
        assertThat(result).isEmpty();
    }

    @Test
    @DisplayName("removeLocation 후 조회 시 Optional.empty() 반환")
    void removeLocation() {
        // given
        String userId = "user-1";
        String key = "location:user:" + userId;

        when(redisTemplate.delete(key)).thenReturn(true);
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.get(key)).thenReturn(null);

        // when
        locationCacheService.removeLocation(userId);
        Optional<Location> result = locationCacheService.getLocation(userId);

        // then
        assertThat(result).isEmpty();
        verify(redisTemplate).delete(key);
    }

    @Test
    @DisplayName("저장된 JSON이 사람이 읽을 수 있는 형태인지 검증")
    void serialization_humanReadableJson() throws Exception {
        // given
        String userId = "test-1";
        int x = 500;
        int y = 300;
        long timestamp = 1234567890L;

        when(redisTemplate.opsForValue()).thenReturn(valueOperations);

        // when
        locationCacheService.setLocation(userId, x, y, timestamp);

        // then
        verify(valueOperations).set(
                eq("location:user:test-1"),
                eq("{\"x\":500,\"y\":300,\"timestamp\":1234567890}"),
                eq(600L),
                eq(TimeUnit.SECONDS));
    }

    @Test
    @DisplayName("빈 목록으로 배치 조회 시 빈 맵 반환")
    void getLocations_emptyList() {
        // given
        // when
        Map<String, Location> result = locationCacheService.getLocations(List.of());

        // then
        assertThat(result).isEmpty();
    }
}
