package com.nearbyfreinds.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.Cursor;
import org.springframework.data.redis.core.ScanOptions;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.test.util.ReflectionTestUtils;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nearbyfreinds.hash.ConsistentHashRing;
import com.nearbyfreinds.pubsub.RedisPubSubManager;
import com.nearbyfreinds.websocket.LocationWebSocketHandler;
import com.nearbyfreinds.websocket.UserSession;

class SystemControllerTest {

    private SystemController controller;
    private ConsistentHashRing<String> hashRing;
    private LocationWebSocketHandler webSocketHandler;
    private RedisPubSubManager pubSubManager;
    private StringRedisTemplate cacheRedisTemplate;
    private ObjectMapper objectMapper;

    @BeforeEach
    void setUp() {
        hashRing = new ConsistentHashRing<>();
        hashRing.addNode("redis-pubsub-1");
        hashRing.addNode("redis-pubsub-2");

        webSocketHandler = mock(LocationWebSocketHandler.class);
        pubSubManager = mock(RedisPubSubManager.class);
        cacheRedisTemplate = mock(StringRedisTemplate.class);
        objectMapper = new ObjectMapper();

        controller = new SystemController(
                hashRing, webSocketHandler, pubSubManager,
                cacheRedisTemplate, objectMapper);

        ReflectionTestUtils.setField(controller, "instanceId", "ws-test");
    }

    @Test
    @DisplayName("hash-ring API가 노드 위치와 채널 매핑을 반환한다")
    void getHashRing_returnsNodesAndChannelMapping() {
        // given
        when(pubSubManager.getSubscribedChannels())
                .thenReturn(Set.of("user:user-1", "user:user-2"));
        when(pubSubManager.getTargetNode("user:user-1")).thenReturn("redis-pubsub-1");
        when(pubSubManager.getTargetNode("user:user-2")).thenReturn("redis-pubsub-2");

        // when
        Map<String, Object> result = controller.getHashRing();

        // then
        assertThat(result).containsKeys("nodes", "channelMapping");

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> nodes = (List<Map<String, Object>>) result.get("nodes");
        assertThat(nodes).isNotEmpty();
        assertThat(nodes.get(0)).containsKeys("position", "server", "virtual", "virtualIndex");

        @SuppressWarnings("unchecked")
        Map<String, String> channelMapping = (Map<String, String>) result.get("channelMapping");
        assertThat(channelMapping).hasSize(2);
        assertThat(channelMapping.get("user:user-1")).isEqualTo("redis-pubsub-1");
        assertThat(channelMapping.get("user:user-2")).isEqualTo("redis-pubsub-2");
    }

    @Test
    @DisplayName("connections API가 현재 서버의 연결 사용자 목록을 반환한다")
    void getConnections_returnsConnectedUsers() {
        // given
        ConcurrentHashMap<String, UserSession> sessions = new ConcurrentHashMap<>();
        UserSession session1 = new UserSession("user-1", mock(org.springframework.web.socket.WebSocketSession.class), 100, 200, System.currentTimeMillis());
        UserSession session2 = new UserSession("user-3", mock(org.springframework.web.socket.WebSocketSession.class), 500, 600, System.currentTimeMillis());
        sessions.put("user-1", session1);
        sessions.put("user-3", session2);
        when(webSocketHandler.getSessions()).thenReturn(sessions);

        // when
        Map<String, Object> result = controller.getConnections();

        // then
        assertThat(result.get("serverId")).isEqualTo("ws-test");
        assertThat(result.get("totalConnections")).isEqualTo(2);

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> users = (List<Map<String, Object>>) result.get("users");
        assertThat(users).hasSize(2);
    }

    @Test
    @DisplayName("connections API에 연결 사용자가 없으면 빈 목록을 반환한다")
    void getConnections_returnsEmptyWhenNoUsers() {
        // given
        when(webSocketHandler.getSessions()).thenReturn(new ConcurrentHashMap<>());

        // when
        Map<String, Object> result = controller.getConnections();

        // then
        assertThat(result.get("totalConnections")).isEqualTo(0);

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> users = (List<Map<String, Object>>) result.get("users");
        assertThat(users).isEmpty();
    }

    @Test
    @DisplayName("channels API가 Redis 노드별 채널 목록을 반환한다")
    void getChannels_returnsChannelsByNode() {
        // given
        Map<String, List<String>> channelsByNode = Map.of(
                "redis-pubsub-1", List.of("user:user-1", "user:user-3"),
                "redis-pubsub-2", List.of("user:user-2")
        );
        when(pubSubManager.getChannelsByNode()).thenReturn(channelsByNode);

        // when
        Map<String, Object> result = controller.getChannels();

        // then
        assertThat(result).containsKeys("redis-pubsub-1", "redis-pubsub-2");

        @SuppressWarnings("unchecked")
        Map<String, Object> node1 = (Map<String, Object>) result.get("redis-pubsub-1");
        assertThat(node1.get("subscriberCount")).isEqualTo(2);

        @SuppressWarnings("unchecked")
        List<String> node1Channels = (List<String>) node1.get("channels");
        assertThat(node1Channels).containsExactlyInAnyOrder("user:user-1", "user:user-3");

        @SuppressWarnings("unchecked")
        Map<String, Object> node2 = (Map<String, Object>) result.get("redis-pubsub-2");
        assertThat(node2.get("subscriberCount")).isEqualTo(1);
    }

    @SuppressWarnings("unchecked")
    @Test
    @DisplayName("cache API가 SCAN으로 위치 데이터를 반환한다")
    void getCache_returnsLocationDataUsingScan() {
        // given
        Cursor<String> cursor = mock(Cursor.class);
        when(cursor.hasNext()).thenReturn(true, false);
        when(cursor.next()).thenReturn("location:user:user-1");
        when(cacheRedisTemplate.scan(org.mockito.ArgumentMatchers.any(ScanOptions.class))).thenReturn(cursor);

        ValueOperations<String, String> valueOps = mock(ValueOperations.class);
        when(cacheRedisTemplate.opsForValue()).thenReturn(valueOps);
        when(valueOps.get("location:user:user-1")).thenReturn("{\"x\":100,\"y\":200,\"timestamp\":1234567890}");
        when(cacheRedisTemplate.getExpire("location:user:user-1", TimeUnit.SECONDS)).thenReturn(3500L);

        // when
        List<Map<String, Object>> result = controller.getCache();

        // then
        assertThat(result).hasSize(1);
        Map<String, Object> entry = result.get(0);
        assertThat(entry.get("userId")).isEqualTo("user-1");
        assertThat(entry.get("x")).isEqualTo(100);
        assertThat(entry.get("y")).isEqualTo(200);
        assertThat(entry.get("timestamp")).isEqualTo(1234567890L);
        assertThat(entry.get("ttl")).isEqualTo(3500L);
    }

    @SuppressWarnings("unchecked")
    @Test
    @DisplayName("cache API에 데이터가 없으면 빈 리스트를 반환한다")
    void getCache_returnsEmptyWhenNoData() {
        // given
        Cursor<String> cursor = mock(Cursor.class);
        when(cursor.hasNext()).thenReturn(false);
        when(cacheRedisTemplate.scan(org.mockito.ArgumentMatchers.any(ScanOptions.class))).thenReturn(cursor);

        // when
        List<Map<String, Object>> result = controller.getCache();

        // then
        assertThat(result).isEmpty();
    }

    @Test
    @DisplayName("hash-ring API에 구독 채널이 없으면 빈 channelMapping을 반환한다")
    void getHashRing_returnsEmptyChannelMappingWhenNoSubscriptions() {
        // given
        when(pubSubManager.getSubscribedChannels()).thenReturn(Set.of());

        // when
        Map<String, Object> result = controller.getHashRing();

        // then
        @SuppressWarnings("unchecked")
        Map<String, String> channelMapping = (Map<String, String>) result.get("channelMapping");
        assertThat(channelMapping).isEmpty();
    }
}
