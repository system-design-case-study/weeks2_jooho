package com.nearbyfreinds.websocket;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.ArrayList;
import java.util.concurrent.CompletableFuture;
import java.util.function.BiConsumer;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.redis.connection.Message;
import org.springframework.data.redis.connection.MessageListener;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nearbyfreinds.dto.Location;
import com.nearbyfreinds.dto.LocationUpdateMessage;
import com.nearbyfreinds.pubsub.RedisPubSubManager;
import com.nearbyfreinds.service.FriendService;
import com.nearbyfreinds.service.LocationCacheService;
import com.nearbyfreinds.service.LocationHistoryService;
import com.nearbyfreinds.service.UserEventService;

class LocationWebSocketHandlerTest {

    private LocationWebSocketHandler handler;
    private FriendService friendService;
    private LocationCacheService locationCacheService;
    private LocationHistoryService locationHistoryService;
    private RedisPubSubManager redisPubSubManager;
    private UserEventService userEventService;
    private ObjectMapper objectMapper;

    @BeforeEach
    void setUp() {
        friendService = mock(FriendService.class);
        locationCacheService = mock(LocationCacheService.class);
        locationHistoryService = mock(LocationHistoryService.class);
        redisPubSubManager = mock(RedisPubSubManager.class);
        userEventService = mock(UserEventService.class);
        objectMapper = new ObjectMapper();

        handler = new LocationWebSocketHandler(
                friendService, locationCacheService, locationHistoryService,
                redisPubSubManager, userEventService, objectMapper);

        ReflectionTestUtils.setField(handler, "instanceId", "ws-test");
        ReflectionTestUtils.setField(handler, "searchRadius", 200);

        when(redisPubSubManager.subscribeWithNodeInfo(anyString(), any()))
                .thenReturn(new ArrayList<>());
    }

    @Test
    @DisplayName("연결 시 사용자 ID가 자동 부여되고 세션 맵에 등록된다")
    void afterConnectionEstablished_assignsUserIdAndRegistersSession() throws Exception {
        // given
        WebSocketSession session = createMockSession();
        when(friendService.getFriends(anyString())).thenReturn(List.of());
        when(locationCacheService.getLocations(any())).thenReturn(Map.of());

        // when
        handler.afterConnectionEstablished(session);

        // then
        assertThat(handler.getSessions()).hasSize(1);
        String userId = (String) session.getAttributes().get("userId");
        assertThat(userId).startsWith("user-");
        assertThat(handler.getSessions()).containsKey(userId);
    }

    @Test
    @DisplayName("INIT 메시지가 올바른 포맷으로 클라이언트에 전송된다")
    void afterConnectionEstablished_sendsInitMessage() throws Exception {
        // given
        WebSocketSession session = createMockSession();
        List<String> friendIds = List.of("user-100", "user-200");
        when(friendService.getFriends(anyString())).thenReturn(friendIds);

        Map<String, Location> friendLocations = new HashMap<>();
        friendLocations.put("user-100", new Location(100, 200, System.currentTimeMillis()));
        when(locationCacheService.getLocations(friendIds)).thenReturn(friendLocations);

        ArgumentCaptor<TextMessage> messageCaptor = ArgumentCaptor.forClass(TextMessage.class);

        // when
        handler.afterConnectionEstablished(session);

        // then
        verify(session).sendMessage(messageCaptor.capture());
        String payload = messageCaptor.getValue().getPayload();
        JsonNode initMsg = objectMapper.readTree(payload);

        assertThat(initMsg.get("type").asText()).isEqualTo("INIT");
        assertThat(initMsg.get("userId").asText()).startsWith("user-");
        assertThat(initMsg.has("x")).isTrue();
        assertThat(initMsg.has("y")).isTrue();
        assertThat(initMsg.get("searchRadius").asInt()).isEqualTo(200);

        JsonNode friends = initMsg.get("friends");
        assertThat(friends.isArray()).isTrue();
        assertThat(friends).hasSize(2);

        JsonNode onlineFriend = friends.get(0);
        assertThat(onlineFriend.get("id").asText()).isEqualTo("user-100");
        assertThat(onlineFriend.get("online").asBoolean()).isTrue();
        assertThat(onlineFriend.get("x").asInt()).isEqualTo(100);
        assertThat(onlineFriend.get("y").asInt()).isEqualTo(200);

        JsonNode offlineFriend = friends.get(1);
        assertThat(offlineFriend.get("id").asText()).isEqualTo("user-200");
        assertThat(offlineFriend.get("online").asBoolean()).isFalse();
        assertThat(offlineFriend.get("x").isNull()).isTrue();
        assertThat(offlineFriend.get("y").isNull()).isTrue();
    }

    @Test
    @DisplayName("연결 시 Redis 캐시에 위치가 저장되고 친구 채널이 구독된다")
    void afterConnectionEstablished_storesCacheAndSubscribes() throws Exception {
        // given
        WebSocketSession session = createMockSession();
        List<String> friendIds = List.of("user-50");
        when(friendService.getFriends(anyString())).thenReturn(friendIds);
        when(locationCacheService.getLocations(friendIds)).thenReturn(Map.of());

        // when
        handler.afterConnectionEstablished(session);

        // then
        verify(locationCacheService).setLocation(anyString(), anyInt(), anyInt(), anyLong());
        verify(redisPubSubManager).subscribeWithNodeInfo(eq("user:user-50"), any());
        verify(redisPubSubManager).publish(anyString(), any(LocationUpdateMessage.class));
    }

    @Test
    @DisplayName("연결 해제 시 세션 맵에서 제거되고 리소스가 정리된다")
    void afterConnectionClosed_removesSessionAndCleansUp() throws Exception {
        // given
        WebSocketSession session = createMockSession();
        when(friendService.getFriends(anyString())).thenReturn(List.of("user-50"));
        when(locationCacheService.getLocations(any())).thenReturn(Map.of());

        handler.afterConnectionEstablished(session);
        String userId = (String) session.getAttributes().get("userId");
        assertThat(handler.getSessions()).containsKey(userId);

        // when
        handler.afterConnectionClosed(session, CloseStatus.NORMAL);

        // then
        assertThat(handler.getSessions()).doesNotContainKey(userId);
        verify(locationCacheService).removeLocation(userId);
        verify(redisPubSubManager).unsubscribe(eq("user:user-50"), any(List.class));
    }

    @Test
    @DisplayName("메시지 타입별 분기가 올바르게 동작한다")
    void handleTextMessage_routesByType() throws Exception {
        // given
        WebSocketSession session = createMockSession();
        when(friendService.getFriends(anyString())).thenReturn(List.of());
        when(locationCacheService.getLocations(any())).thenReturn(Map.of());
        when(redisPubSubManager.getTargetNode(anyString())).thenReturn("redis-pubsub-1");
        when(locationHistoryService.saveLocation(anyString(), anyInt(), anyInt(), anyString()))
                .thenReturn(CompletableFuture.completedFuture(null));
        handler.afterConnectionEstablished(session);

        // when & then - LOCATION_UPDATE
        TextMessage locationMsg = new TextMessage("{\"type\":\"LOCATION_UPDATE\",\"x\":100,\"y\":200}");
        handler.handleMessage(session, locationMsg);

        // when & then - ADD_FRIEND
        TextMessage addFriendMsg = new TextMessage("{\"type\":\"ADD_FRIEND\",\"friendId\":\"user-99\"}");
        handler.handleMessage(session, addFriendMsg);

        // when & then - REMOVE_FRIEND
        TextMessage removeFriendMsg = new TextMessage("{\"type\":\"REMOVE_FRIEND\",\"friendId\":\"user-99\"}");
        handler.handleMessage(session, removeFriendMsg);

        // when & then - UNKNOWN
        TextMessage unknownMsg = new TextMessage("{\"type\":\"UNKNOWN\"}");
        handler.handleMessage(session, unknownMsg);
    }

    @Test
    @DisplayName("여러 연결 시 사용자 ID가 순차 증가한다")
    void afterConnectionEstablished_incrementsUserIds() throws Exception {
        // given
        WebSocketSession session1 = createMockSession();
        WebSocketSession session2 = createMockSession();
        when(friendService.getFriends(anyString())).thenReturn(List.of());
        when(locationCacheService.getLocations(any())).thenReturn(Map.of());

        // when
        handler.afterConnectionEstablished(session1);
        handler.afterConnectionEstablished(session2);

        // then
        String userId1 = (String) session1.getAttributes().get("userId");
        String userId2 = (String) session2.getAttributes().get("userId");
        assertThat(userId1).isNotEqualTo(userId2);
        assertThat(handler.getSessions()).hasSize(2);
    }

    @Test
    @DisplayName("LOCATION_UPDATE 수신 시 세션 맵의 좌표가 갱신된다")
    void handleLocationUpdate_updatesSessionCoordinates() throws Exception {
        // given
        WebSocketSession session = createMockSession();
        when(friendService.getFriends(anyString())).thenReturn(List.of());
        when(locationCacheService.getLocations(any())).thenReturn(Map.of());
        when(redisPubSubManager.getTargetNode(anyString())).thenReturn("redis-pubsub-1");
        when(locationHistoryService.saveLocation(anyString(), anyInt(), anyInt(), anyString()))
                .thenReturn(CompletableFuture.completedFuture(null));
        handler.afterConnectionEstablished(session);
        String userId = (String) session.getAttributes().get("userId");

        // when
        TextMessage msg = new TextMessage("{\"type\":\"LOCATION_UPDATE\",\"x\":500,\"y\":300}");
        handler.handleMessage(session, msg);

        // then
        UserSession userSession = handler.getSessions().get(userId);
        assertThat(userSession.getX()).isEqualTo(500);
        assertThat(userSession.getY()).isEqualTo(300);
    }

    @Test
    @DisplayName("LOCATION_UPDATE 수신 시 Cassandra에 위치 이력이 비동기 저장된다")
    void handleLocationUpdate_savesToCassandra() throws Exception {
        // given
        WebSocketSession session = createMockSession();
        when(friendService.getFriends(anyString())).thenReturn(List.of());
        when(locationCacheService.getLocations(any())).thenReturn(Map.of());
        when(redisPubSubManager.getTargetNode(anyString())).thenReturn("redis-pubsub-1");
        when(locationHistoryService.saveLocation(anyString(), anyInt(), anyInt(), anyString()))
                .thenReturn(CompletableFuture.completedFuture(null));
        handler.afterConnectionEstablished(session);
        String userId = (String) session.getAttributes().get("userId");

        // when
        TextMessage msg = new TextMessage("{\"type\":\"LOCATION_UPDATE\",\"x\":100,\"y\":200}");
        handler.handleMessage(session, msg);

        // then
        verify(locationHistoryService).saveLocation(eq(userId), eq(100), eq(200), eq("ws-test"));
    }

    @Test
    @DisplayName("LOCATION_UPDATE 수신 시 Redis 캐시에 최신 위치가 갱신된다")
    void handleLocationUpdate_updatesRedisCache() throws Exception {
        // given
        WebSocketSession session = createMockSession();
        when(friendService.getFriends(anyString())).thenReturn(List.of());
        when(locationCacheService.getLocations(any())).thenReturn(Map.of());
        when(redisPubSubManager.getTargetNode(anyString())).thenReturn("redis-pubsub-1");
        when(locationHistoryService.saveLocation(anyString(), anyInt(), anyInt(), anyString()))
                .thenReturn(CompletableFuture.completedFuture(null));
        handler.afterConnectionEstablished(session);
        String userId = (String) session.getAttributes().get("userId");

        // when
        TextMessage msg = new TextMessage("{\"type\":\"LOCATION_UPDATE\",\"x\":400,\"y\":500}");
        handler.handleMessage(session, msg);

        // then
        verify(locationCacheService).setLocation(eq(userId), eq(400), eq(500), anyLong());
    }

    @Test
    @DisplayName("LOCATION_UPDATE 수신 시 Pub/Sub에 올바른 채널로 메시지가 발행된다")
    void handleLocationUpdate_publishesToPubSub() throws Exception {
        // given
        WebSocketSession session = createMockSession();
        when(friendService.getFriends(anyString())).thenReturn(List.of());
        when(locationCacheService.getLocations(any())).thenReturn(Map.of());
        when(redisPubSubManager.getTargetNode(anyString())).thenReturn("redis-pubsub-1");
        when(locationHistoryService.saveLocation(anyString(), anyInt(), anyInt(), anyString()))
                .thenReturn(CompletableFuture.completedFuture(null));
        handler.afterConnectionEstablished(session);
        String userId = (String) session.getAttributes().get("userId");

        // when
        TextMessage msg = new TextMessage("{\"type\":\"LOCATION_UPDATE\",\"x\":300,\"y\":400}");
        handler.handleMessage(session, msg);

        // then
        ArgumentCaptor<String> channelCaptor = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<LocationUpdateMessage> messageCaptor = ArgumentCaptor.forClass(LocationUpdateMessage.class);
        verify(redisPubSubManager, times(2)).publish(channelCaptor.capture(), messageCaptor.capture());

        String publishedChannel = channelCaptor.getAllValues().get(1);
        assertThat(publishedChannel).isEqualTo("user:" + userId);

        LocationUpdateMessage publishedMessage = messageCaptor.getAllValues().get(1);
        assertThat(publishedMessage.getX()).isEqualTo(300);
        assertThat(publishedMessage.getY()).isEqualTo(400);
        assertThat(publishedMessage.getUserId()).isEqualTo(userId);
    }

    @Test
    @DisplayName("발행된 메시지에 path 배열이 포함되어 있다")
    void handleLocationUpdate_includesPropagationPath() throws Exception {
        // given
        WebSocketSession session = createMockSession();
        when(friendService.getFriends(anyString())).thenReturn(List.of());
        when(locationCacheService.getLocations(any())).thenReturn(Map.of());
        when(redisPubSubManager.getTargetNode(anyString())).thenReturn("redis-pubsub-1");
        when(locationHistoryService.saveLocation(anyString(), anyInt(), anyInt(), anyString()))
                .thenReturn(CompletableFuture.completedFuture(null));
        handler.afterConnectionEstablished(session);

        // when
        TextMessage msg = new TextMessage("{\"type\":\"LOCATION_UPDATE\",\"x\":100,\"y\":200}");
        handler.handleMessage(session, msg);

        // then
        ArgumentCaptor<LocationUpdateMessage> messageCaptor = ArgumentCaptor.forClass(LocationUpdateMessage.class);
        verify(redisPubSubManager, times(2)).publish(anyString(), messageCaptor.capture());

        LocationUpdateMessage publishedMessage = messageCaptor.getAllValues().get(1);
        assertThat(publishedMessage.getPath()).hasSize(2);
        assertThat(publishedMessage.getPath().get(0).node()).isEqualTo("ws-test");
        assertThat(publishedMessage.getPath().get(0).action().getValue()).isEqualTo("receive");
        assertThat(publishedMessage.getPath().get(1).node()).isEqualTo("redis-pubsub-1");
        assertThat(publishedMessage.getPath().get(1).action().getValue()).isEqualTo("publish");
    }

    @Test
    @DisplayName("Cassandra 장애 시에도 캐시 갱신과 Pub/Sub 발행이 정상 동작한다")
    void handleLocationUpdate_continuesWhenCassandraFails() throws Exception {
        // given
        WebSocketSession session = createMockSession();
        when(friendService.getFriends(anyString())).thenReturn(List.of());
        when(locationCacheService.getLocations(any())).thenReturn(Map.of());
        when(redisPubSubManager.getTargetNode(anyString())).thenReturn("redis-pubsub-1");
        when(locationHistoryService.saveLocation(anyString(), anyInt(), anyInt(), anyString()))
                .thenReturn(CompletableFuture.failedFuture(new RuntimeException("Cassandra 연결 실패")));
        handler.afterConnectionEstablished(session);
        String userId = (String) session.getAttributes().get("userId");

        // when
        TextMessage msg = new TextMessage("{\"type\":\"LOCATION_UPDATE\",\"x\":100,\"y\":200}");
        handler.handleMessage(session, msg);

        // then
        verify(locationCacheService).setLocation(eq(userId), eq(100), eq(200), anyLong());
        verify(redisPubSubManager, times(2)).publish(anyString(), any(LocationUpdateMessage.class));
    }

    @Test
    @DisplayName("Redis 캐시 갱신 실패 시 Pub/Sub 발행이 수행되지 않는다")
    void handleLocationUpdate_stopsWhenCacheFails() throws Exception {
        // given
        WebSocketSession session = createMockSession();
        when(friendService.getFriends(anyString())).thenReturn(List.of());
        when(locationCacheService.getLocations(any())).thenReturn(Map.of());
        when(locationHistoryService.saveLocation(anyString(), anyInt(), anyInt(), anyString()))
                .thenReturn(CompletableFuture.completedFuture(null));
        handler.afterConnectionEstablished(session);
        String userId = (String) session.getAttributes().get("userId");

        doThrow(new RuntimeException("Redis 연결 실패"))
                .when(locationCacheService).setLocation(eq(userId), eq(100), eq(200), anyLong());

        // when
        TextMessage msg = new TextMessage("{\"type\":\"LOCATION_UPDATE\",\"x\":100,\"y\":200}");
        handler.handleMessage(session, msg);

        // then - publish는 afterConnectionEstablished에서 1번만 호출됨 (LOCATION_UPDATE에서는 호출 안 됨)
        verify(redisPubSubManager, times(1)).publish(anyString(), any(LocationUpdateMessage.class));
    }

    @Test
    @DisplayName("Pub/Sub 수신 시 친구 관계인 사용자에게만 FRIEND_LOCATION이 전송된다")
    void nodeAwareListener_sendsFriendLocationToSubscriber() throws Exception {
        // given
        WebSocketSession subscriberSession = createMockSession();
        when(friendService.getFriends(anyString())).thenReturn(List.of("user-sender"));
        when(locationCacheService.getLocations(any())).thenReturn(Map.of());
        handler.afterConnectionEstablished(subscriberSession);
        String subscriberUserId = (String) subscriberSession.getAttributes().get("userId");

        UserSession subSession = handler.getSessions().get(subscriberUserId);
        subSession.updateLocation(100, 100, System.currentTimeMillis());

        BiConsumer<String, Message> listener = handler.getNodeListener(subscriberUserId);

        LocationUpdateMessage senderMsg = new LocationUpdateMessage("user-sender", 150, 150, System.currentTimeMillis());
        String json = objectMapper.writeValueAsString(senderMsg);
        Message redisMessage = createRedisMessage(json, "user:user-sender");

        // when
        listener.accept("redis-pubsub-1", redisMessage);

        // then
        ArgumentCaptor<TextMessage> captor = ArgumentCaptor.forClass(TextMessage.class);
        verify(subscriberSession, times(3)).sendMessage(captor.capture());

        JsonNode friendLocMsg = objectMapper.readTree(captor.getAllValues().get(1).getPayload());
        assertThat(friendLocMsg.get("type").asText()).isEqualTo("FRIEND_LOCATION");
        assertThat(friendLocMsg.get("friendId").asText()).isEqualTo("user-sender");
        assertThat(friendLocMsg.get("x").asInt()).isEqualTo(150);
        assertThat(friendLocMsg.get("y").asInt()).isEqualTo(150);
    }

    @Test
    @DisplayName("반경 내 친구에게 inRange: true, 반경 밖 친구에게 inRange: false가 전송된다")
    void nodeAwareListener_setsInRangeCorrectly() throws Exception {
        // given
        WebSocketSession subscriberSession = createMockSession();
        when(friendService.getFriends(anyString())).thenReturn(List.of());
        when(locationCacheService.getLocations(any())).thenReturn(Map.of());
        handler.afterConnectionEstablished(subscriberSession);
        String subscriberUserId = (String) subscriberSession.getAttributes().get("userId");

        UserSession subSession = handler.getSessions().get(subscriberUserId);
        subSession.updateLocation(0, 0, System.currentTimeMillis());

        BiConsumer<String, Message> listener = handler.getNodeListener(subscriberUserId);

        // when - 반경 내 (distance = sqrt(100^2 + 100^2) ≈ 141.4)
        LocationUpdateMessage inRangeMsg = new LocationUpdateMessage("user-near", 100, 100, System.currentTimeMillis());
        listener.accept("redis-pubsub-1", createRedisMessage(objectMapper.writeValueAsString(inRangeMsg), "user:user-near"));

        // when - 반경 밖 (distance = sqrt(300^2 + 300^2) ≈ 424.3)
        LocationUpdateMessage outRangeMsg = new LocationUpdateMessage("user-far", 300, 300, System.currentTimeMillis());
        listener.accept("redis-pubsub-1", createRedisMessage(objectMapper.writeValueAsString(outRangeMsg), "user:user-far"));

        // then
        ArgumentCaptor<TextMessage> captor = ArgumentCaptor.forClass(TextMessage.class);
        verify(subscriberSession, times(5)).sendMessage(captor.capture());

        JsonNode nearMsg = objectMapper.readTree(captor.getAllValues().get(1).getPayload());
        assertThat(nearMsg.get("type").asText()).isEqualTo("FRIEND_LOCATION");
        assertThat(nearMsg.get("inRange").asBoolean()).isTrue();
        assertThat(nearMsg.get("distance").asDouble()).isLessThan(200.0);

        JsonNode farMsg = objectMapper.readTree(captor.getAllValues().get(3).getPayload());
        assertThat(farMsg.get("type").asText()).isEqualTo("FRIEND_LOCATION");
        assertThat(farMsg.get("inRange").asBoolean()).isFalse();
        assertThat(farMsg.get("distance").asDouble()).isGreaterThan(200.0);
    }

    @Test
    @DisplayName("Euclidean 거리 계산이 정확하다")
    void nodeAwareListener_calculatesDistanceCorrectly() throws Exception {
        // given
        WebSocketSession subscriberSession = createMockSession();
        when(friendService.getFriends(anyString())).thenReturn(List.of());
        when(locationCacheService.getLocations(any())).thenReturn(Map.of());
        handler.afterConnectionEstablished(subscriberSession);
        String subscriberUserId = (String) subscriberSession.getAttributes().get("userId");

        UserSession subSession = handler.getSessions().get(subscriberUserId);
        subSession.updateLocation(0, 0, System.currentTimeMillis());

        BiConsumer<String, Message> listener = handler.getNodeListener(subscriberUserId);

        LocationUpdateMessage senderMsg = new LocationUpdateMessage("user-sender", 300, 400, System.currentTimeMillis());
        listener.accept("redis-pubsub-1", createRedisMessage(objectMapper.writeValueAsString(senderMsg), "user:user-sender"));

        // then - distance = sqrt(300^2 + 400^2) = 500.0
        ArgumentCaptor<TextMessage> captor = ArgumentCaptor.forClass(TextMessage.class);
        verify(subscriberSession, times(3)).sendMessage(captor.capture());

        JsonNode friendLocMsg = objectMapper.readTree(captor.getAllValues().get(1).getPayload());
        assertThat(friendLocMsg.get("distance").asDouble()).isEqualTo(500.0);
    }

    @Test
    @DisplayName("자기 자신의 위치 업데이트가 자신에게 재전송되지 않는다")
    void nodeAwareListener_skipsSelfMessage() throws Exception {
        // given
        WebSocketSession subscriberSession = createMockSession();
        when(friendService.getFriends(anyString())).thenReturn(List.of());
        when(locationCacheService.getLocations(any())).thenReturn(Map.of());
        handler.afterConnectionEstablished(subscriberSession);
        String subscriberUserId = (String) subscriberSession.getAttributes().get("userId");

        BiConsumer<String, Message> listener = handler.getNodeListener(subscriberUserId);

        LocationUpdateMessage selfMsg = new LocationUpdateMessage(subscriberUserId, 100, 100, System.currentTimeMillis());
        listener.accept("redis-pubsub-1", createRedisMessage(objectMapper.writeValueAsString(selfMsg), "user:" + subscriberUserId));

        // then - INIT 메시지 1번만 전송, FRIEND_LOCATION은 전송 안 됨
        verify(subscriberSession, times(1)).sendMessage(any(TextMessage.class));
    }

    @Test
    @DisplayName("PROPAGATION_LOG 메시지가 모든 클라이언트에 브로드캐스트된다")
    void nodeAwareListener_broadcastsPropagationLog() throws Exception {
        // given
        WebSocketSession session1 = createMockSession();
        WebSocketSession session2 = createMockSession();
        when(friendService.getFriends(anyString())).thenReturn(List.of());
        when(locationCacheService.getLocations(any())).thenReturn(Map.of());
        handler.afterConnectionEstablished(session1);
        handler.afterConnectionEstablished(session2);
        String user1Id = (String) session1.getAttributes().get("userId");

        UserSession sub1 = handler.getSessions().get(user1Id);
        sub1.updateLocation(0, 0, System.currentTimeMillis());

        BiConsumer<String, Message> listener = handler.getNodeListener(user1Id);

        LocationUpdateMessage senderMsg = new LocationUpdateMessage("user-sender", 100, 100, System.currentTimeMillis());
        listener.accept("redis-pubsub-1", createRedisMessage(objectMapper.writeValueAsString(senderMsg), "user:user-sender"));

        // then - session1: INIT + FRIEND_LOCATION + PROPAGATION_LOG = 3
        ArgumentCaptor<TextMessage> captor1 = ArgumentCaptor.forClass(TextMessage.class);
        verify(session1, times(3)).sendMessage(captor1.capture());

        JsonNode propagationLog = objectMapper.readTree(captor1.getAllValues().get(2).getPayload());
        assertThat(propagationLog.get("type").asText()).isEqualTo("PROPAGATION_LOG");
        assertThat(propagationLog.get("sourceUser").asText()).isEqualTo("user-sender");
        assertThat(propagationLog.get("wsServer").asText()).isEqualTo("ws-test");
        assertThat(propagationLog.get("redisNode").asText()).isEqualTo("redis-pubsub-1");
        assertThat(propagationLog.get("channel").asText()).isEqualTo("user:user-sender");

        JsonNode subscribers = propagationLog.get("subscribers");
        assertThat(subscribers.isArray()).isTrue();
        assertThat(subscribers).hasSize(1);
        assertThat(subscribers.get(0).get("userId").asText()).isEqualTo(user1Id);
        assertThat(subscribers.get(0).get("inRange").asBoolean()).isTrue();
        assertThat(subscribers.get(0).get("sent").asBoolean()).isTrue();

        // session2에도 PROPAGATION_LOG가 전송됨 (INIT + PROPAGATION_LOG = 2)
        verify(session2, times(2)).sendMessage(any(TextMessage.class));
    }

    @Test
    @DisplayName("수신 메시지의 path 배열에 subscribe_receive와 deliver 정보가 추가된다")
    void nodeAwareListener_addsPathEntries() throws Exception {
        // given
        WebSocketSession subscriberSession = createMockSession();
        when(friendService.getFriends(anyString())).thenReturn(List.of());
        when(locationCacheService.getLocations(any())).thenReturn(Map.of());
        handler.afterConnectionEstablished(subscriberSession);
        String subscriberUserId = (String) subscriberSession.getAttributes().get("userId");

        UserSession subSession = handler.getSessions().get(subscriberUserId);
        subSession.updateLocation(0, 0, System.currentTimeMillis());

        BiConsumer<String, Message> listener = handler.getNodeListener(subscriberUserId);

        LocationUpdateMessage senderMsg = new LocationUpdateMessage("user-sender", 100, 100, System.currentTimeMillis());
        senderMsg.addPath(new com.nearbyfreinds.dto.PropagationPath("ws-origin", com.nearbyfreinds.dto.PathAction.RECEIVE, System.currentTimeMillis()));
        senderMsg.addPath(new com.nearbyfreinds.dto.PropagationPath("redis-pubsub-1", com.nearbyfreinds.dto.PathAction.PUBLISH, System.currentTimeMillis()));
        String json = objectMapper.writeValueAsString(senderMsg);

        // when
        listener.accept("redis-pubsub-1", createRedisMessage(json, "user:user-sender"));

        // then - PROPAGATION_LOG를 통해 subscribe_receive 단계의 redisNode 확인
        ArgumentCaptor<TextMessage> captor = ArgumentCaptor.forClass(TextMessage.class);
        verify(subscriberSession, times(3)).sendMessage(captor.capture());

        JsonNode propagationLog = objectMapper.readTree(captor.getAllValues().get(2).getPayload());
        assertThat(propagationLog.get("type").asText()).isEqualTo("PROPAGATION_LOG");
        assertThat(propagationLog.get("redisNode").asText()).isEqualTo("redis-pubsub-1");
        assertThat(propagationLog.get("wsServer").asText()).isEqualTo("ws-test");

        // FRIEND_LOCATION을 통해 deliver 단계가 수행되었음을 확인
        JsonNode friendLocation = objectMapper.readTree(captor.getAllValues().get(1).getPayload());
        assertThat(friendLocation.get("type").asText()).isEqualTo("FRIEND_LOCATION");
        assertThat(friendLocation.get("friendId").asText()).isEqualTo("user-sender");
    }

    private Message createRedisMessage(String body, String channel) {
        return new Message() {
            @Override
            public byte[] getBody() {
                return body.getBytes();
            }

            @Override
            public byte[] getChannel() {
                return channel.getBytes();
            }
        };
    }

    private WebSocketSession createMockSession() {
        WebSocketSession session = mock(WebSocketSession.class);
        Map<String, Object> attributes = new HashMap<>();
        when(session.getAttributes()).thenReturn(attributes);
        when(session.isOpen()).thenReturn(true);
        return session;
    }
}
