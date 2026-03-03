package com.nearbyfreinds.websocket;

import java.io.IOException;
import java.net.URLDecoder;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BiConsumer;

import jakarta.annotation.PostConstruct;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.connection.Message;
import org.springframework.data.redis.connection.MessageListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.PingMessage;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;
import org.springframework.web.socket.handler.TextWebSocketHandler;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.nearbyfreinds.domain.User;
import com.nearbyfreinds.dto.Location;
import com.nearbyfreinds.dto.LocationUpdateMessage;
import com.nearbyfreinds.dto.PropagationPathBuilder;
import com.nearbyfreinds.pubsub.RedisPubSubManager;
import com.nearbyfreinds.service.FriendService;
import com.nearbyfreinds.service.LocationCacheService;
import com.nearbyfreinds.service.LocationHistoryService;
import com.nearbyfreinds.service.UserEventService;
import com.nearbyfreinds.repository.jpa.UserRepository;
import com.nearbyfreinds.util.DistanceCalculator;

@Component
public class LocationWebSocketHandler extends TextWebSocketHandler implements WebSocketConfigurer {

    private static final Logger log = LoggerFactory.getLogger(LocationWebSocketHandler.class);

    private static final int COORDINATE_MAX = 1000;
    private static final String USER_ID_PREFIX = "user-";
    private static final String CHANNEL_PREFIX = "user:";
    private static final String FRIEND_NOTIFY_CHANNEL = "system:friend-notify";

    private final AtomicInteger userCounter = new AtomicInteger(0);
    private final ConcurrentHashMap<String, UserSession> sessions = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, List<String>> userSubscriptions = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, BiConsumer<String, Message>> userNodeListeners = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, ConcurrentHashMap<String, List<MessageListener>>> userChannelListeners = new ConcurrentHashMap<>();

    private final FriendService friendService;
    private final LocationCacheService locationCacheService;
    private final LocationHistoryService locationHistoryService;
    private final RedisPubSubManager redisPubSubManager;
    private final UserEventService userEventService;
    private final UserRepository userRepository;
    private final ObjectMapper objectMapper;

    @Value("${app.instance-id}")
    private String instanceId;

    @Value("${app.search-radius}")
    private int searchRadius;

    public LocationWebSocketHandler(
            FriendService friendService,
            LocationCacheService locationCacheService,
            LocationHistoryService locationHistoryService,
            RedisPubSubManager redisPubSubManager,
            UserEventService userEventService,
            UserRepository userRepository,
            ObjectMapper objectMapper) {
        this.friendService = friendService;
        this.locationCacheService = locationCacheService;
        this.locationHistoryService = locationHistoryService;
        this.redisPubSubManager = redisPubSubManager;
        this.userEventService = userEventService;
        this.userRepository = userRepository;
        this.objectMapper = objectMapper;

        userEventService.setBroadcaster(this::broadcastUserList);
    }

    @PostConstruct
    public void initFriendNotifyChannel() {
        redisPubSubManager.subscribeOnFixedNode(FRIEND_NOTIFY_CHANNEL, (message, pattern) -> {
            try {
                JsonNode json = objectMapper.readTree(message.getBody());
                String type = json.path("type").asText();
                String targetUserId = json.path("targetUserId").asText();
                String friendUserId = json.path("friendUserId").asText();

                UserSession targetSession = sessions.get(targetUserId);
                if (targetSession == null) {
                    return;
                }

                switch (type) {
                    case "FRIEND_ADDED" -> handleFriendAddedNotification(targetUserId, friendUserId);
                    case "FRIEND_REMOVED" -> handleFriendRemovedNotification(targetUserId, friendUserId);
                    default -> log.warn("[{}] 알 수 없는 friend-notify 타입: {}", instanceId, type);
                }
            } catch (Exception e) {
                log.error("[{}] friend-notify 처리 실패", instanceId, e);
            }
        });
        log.info("[{}] system:friend-notify 채널 구독 완료", instanceId);
    }

    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        registry.addHandler(this, "/ws/location")
                .setAllowedOrigins("*");
    }

    @Override
    public void afterConnectionEstablished(WebSocketSession session) throws Exception {
        String userId = extractUserIdFromQuery(session);
        if (userId == null || userId.isBlank()) {
            String serverSuffix = instanceId.replace("ws-", "");
            userId = USER_ID_PREFIX + serverSuffix + "-" + userCounter.incrementAndGet();
        }

        UserSession existingSession = sessions.get(userId);
        if (existingSession != null && existingSession.getSession().isOpen()) {
            try {
                existingSession.getSession().close(CloseStatus.NORMAL.withReason("다른 세션에서 접속"));
            } catch (IOException e) {
                log.warn("[{}] 기존 세션 종료 실패: userId={}", instanceId, userId, e);
            }
        }

        int x;
        int y;
        Optional<User> existingUser = userRepository.findById(userId);
        if (existingUser.isPresent()) {
            x = existingUser.get().getInitialX();
            y = existingUser.get().getInitialY();
        } else {
            x = ThreadLocalRandom.current().nextInt(COORDINATE_MAX + 1);
            y = ThreadLocalRandom.current().nextInt(COORDINATE_MAX + 1);
        }
        long timestamp = System.currentTimeMillis();

        UserSession userSession = new UserSession(userId, session, x, y, timestamp);
        sessions.put(userId, userSession);
        session.getAttributes().put("userId", userId);

        int colorHue = Math.abs(userId.hashCode()) % 360;
        userRepository.save(new User(userId, userId, colorHue, x, y));

        log.info("[{}] 새 연결: userId={}, 좌표=({}, {})", instanceId, userId, x, y);

        locationCacheService.setLocation(userId, x, y, timestamp);

        List<String> friendIds = friendService.getFriends(userId);

        Map<String, Location> friendLocations = locationCacheService.getLocations(friendIds);

        BiConsumer<String, Message> listenerCallback = createNodeAwareListener(userId);
        userNodeListeners.put(userId, listenerCallback);
        ConcurrentHashMap<String, List<MessageListener>> channelListeners = new ConcurrentHashMap<>();
        List<String> subscribedChannels = new ArrayList<>();
        for (String friendId : friendIds) {
            String channel = CHANNEL_PREFIX + friendId;
            List<MessageListener> listeners = redisPubSubManager.subscribeWithNodeInfo(channel, listenerCallback);
            channelListeners.put(channel, listeners);
            subscribedChannels.add(channel);
        }
        userChannelListeners.put(userId, channelListeners);
        userSubscriptions.put(userId, subscribedChannels);

        String myChannel = CHANNEL_PREFIX + userId;
        LocationUpdateMessage publishMessage = new LocationUpdateMessage(userId, x, y, timestamp);
        publishMessage.addPath(PropagationPathBuilder.receive(instanceId));
        redisPubSubManager.publish(myChannel, publishMessage);

        ObjectNode initMessage = objectMapper.createObjectNode();
        initMessage.put("type", "INIT");
        initMessage.put("userId", userId);
        initMessage.put("x", x);
        initMessage.put("y", y);
        initMessage.put("searchRadius", searchRadius);

        ArrayNode friendsArray = objectMapper.createArrayNode();
        for (String friendId : friendIds) {
            ObjectNode friendNode = objectMapper.createObjectNode();
            friendNode.put("id", friendId);
            Location loc = friendLocations.get(friendId);
            if (loc != null) {
                friendNode.put("x", loc.x());
                friendNode.put("y", loc.y());
                friendNode.put("online", true);
            } else {
                friendNode.putNull("x");
                friendNode.putNull("y");
                friendNode.put("online", false);
            }
            friendsArray.add(friendNode);
        }
        initMessage.set("friends", friendsArray);

        userSession.getSession().sendMessage(new TextMessage(objectMapper.writeValueAsString(initMessage)));
        log.info("[{}] INIT 메시지 전송 완료: userId={}", instanceId, userId);

        userEventService.publishUserConnected(userId);
    }

    @Override
    protected void handleTextMessage(WebSocketSession session, TextMessage message) throws Exception {
        String userId = (String) session.getAttributes().get("userId");
        if (userId == null) {
            log.warn("[{}] userId가 없는 세션에서 메시지 수신", instanceId);
            return;
        }

        JsonNode jsonNode = objectMapper.readTree(message.getPayload());
        String type = jsonNode.path("type").asText();

        switch (type) {
            case "LOCATION_UPDATE" -> handleLocationUpdate(userId, jsonNode);
            case "ADD_FRIEND" -> handleAddFriend(userId, jsonNode);
            case "REMOVE_FRIEND" -> handleRemoveFriend(userId, jsonNode);
            default -> log.warn("[{}] 알 수 없는 메시지 타입: type={}, userId={}", instanceId, type, userId);
        }
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) throws Exception {
        String userId = (String) session.getAttributes().get("userId");
        if (userId == null) {
            return;
        }

        log.info("[{}] 연결 종료: userId={}, status={}", instanceId, userId, status);

        UserSession currentSession = sessions.get(userId);
        if (currentSession != null && currentSession.getSession() != session) {
            log.info("[{}] 이미 교체된 세션의 종료 이벤트 무시: userId={}", instanceId, userId);
            return;
        }

        sessions.remove(userId);

        List<String> channels = userSubscriptions.remove(userId);
        ConcurrentHashMap<String, List<MessageListener>> channelListeners = userChannelListeners.remove(userId);
        if (channels != null && channelListeners != null) {
            for (String channel : channels) {
                List<MessageListener> listeners = channelListeners.get(channel);
                if (listeners != null) {
                    redisPubSubManager.unsubscribe(channel, listeners);
                }
            }
        }
        userNodeListeners.remove(userId);

        locationCacheService.removeLocation(userId);

        userEventService.publishUserDisconnected(userId);

        log.info("[{}] 리소스 정리 완료: userId={}", instanceId, userId);
    }

    /**
     * 현재 연결된 세션 맵을 반환한다.
     */
    public Map<String, UserSession> getSessions() {
        return sessions;
    }

    /**
     * 특정 사용자의 노드 식별 가능한 리스너 콜백을 반환한다.
     *
     * @param userId 사용자 ID
     * @return BiConsumer 콜백, 세션이 없으면 null
     */
    public BiConsumer<String, Message> getNodeListener(String userId) {
        return userNodeListeners.get(userId);
    }

    /**
     * 친구 채널을 subscribe하고 리스너를 추적한다.
     *
     * @param userId 구독하는 사용자 ID
     * @param friendId 구독 대상 친구 ID
     * @param listeners subscribeWithNodeInfo에서 반환된 리스너 목록
     */
    public void trackSubscription(String userId, String friendId, List<MessageListener> listeners) {
        String channel = CHANNEL_PREFIX + friendId;
        userSubscriptions.computeIfAbsent(userId, k -> new ArrayList<>()).add(channel);
        userChannelListeners.computeIfAbsent(userId, k -> new ConcurrentHashMap<>()).put(channel, listeners);
    }

    /**
     * 특정 사용자의 특정 채널에 대한 추적 중인 리스너 목록을 반환한다.
     *
     * @param userId 사용자 ID
     * @param friendId 친구 ID
     * @return 리스너 목록, 없으면 빈 리스트
     */
    public List<MessageListener> getTrackedListeners(String userId, String friendId) {
        String channel = CHANNEL_PREFIX + friendId;
        ConcurrentHashMap<String, List<MessageListener>> channelMap = userChannelListeners.get(userId);
        if (channelMap == null) {
            return Collections.emptyList();
        }
        return channelMap.getOrDefault(channel, Collections.emptyList());
    }

    /**
     * 특정 사용자의 구독 채널 목록에서 채널을 제거하고 추적 리스너도 정리한다.
     *
     * @param userId 사용자 ID
     * @param friendId 친구 ID
     */
    public void removeTrackedSubscription(String userId, String friendId) {
        String channel = CHANNEL_PREFIX + friendId;
        List<String> channels = userSubscriptions.get(userId);
        if (channels != null) {
            channels.remove(channel);
        }
        ConcurrentHashMap<String, List<MessageListener>> channelMap = userChannelListeners.get(userId);
        if (channelMap != null) {
            channelMap.remove(channel);
        }
    }

    private void handleLocationUpdate(String userId, JsonNode jsonNode) {
        int x = jsonNode.path("x").asInt();
        int y = jsonNode.path("y").asInt();
        long timestamp = System.currentTimeMillis();

        UserSession userSession = sessions.get(userId);
        if (userSession == null) {
            log.warn("[{}] 세션을 찾을 수 없습니다: userId={}", instanceId, userId);
            return;
        }
        userSession.updateLocation(x, y, timestamp);

        locationHistoryService.saveLocation(userId, x, y, instanceId);

        try {
            locationCacheService.setLocation(userId, x, y, timestamp);
        } catch (Exception e) {
            log.error("[{}] Redis 캐시 갱신 실패: userId={}, x={}, y={}", instanceId, userId, x, y, e);
            return;
        }

        try {
            String channel = CHANNEL_PREFIX + userId;

            LocationUpdateMessage message = new LocationUpdateMessage(userId, x, y, timestamp);
            message.addPath(PropagationPathBuilder.receive(instanceId));

            String targetNode = redisPubSubManager.getTargetNode(channel);
            if (targetNode != null) {
                message.addPath(PropagationPathBuilder.publish(targetNode));
            }

            redisPubSubManager.publish(channel, message);
        } catch (Exception e) {
            log.error("[{}] Pub/Sub 발행 실패: userId={}", instanceId, userId, e);
        }

        log.debug("[{}] LOCATION_UPDATE 처리 완료: userId={}, x={}, y={}", instanceId, userId, x, y);
    }

    private void handleAddFriend(String userId, JsonNode jsonNode) {
        String friendId = jsonNode.path("friendId").asText();
        if (friendId.isEmpty()) {
            log.warn("[{}] ADD_FRIEND: friendId가 비어있습니다: userId={}", instanceId, userId);
            return;
        }

        try {
            try {
                friendService.addFriendDbOnly(userId, friendId);
            } catch (IllegalStateException e) {
                log.info("[{}] ADD_FRIEND: 이미 친구 관계 존재: userId={}, friendId={}", instanceId, userId, friendId);
            }

            publishFriendNotify("FRIEND_ADDED", userId, friendId);
            publishFriendNotify("FRIEND_ADDED", friendId, userId);

            log.info("[{}] ADD_FRIEND 처리 완료: userId={}, friendId={}", instanceId, userId, friendId);
        } catch (IllegalArgumentException e) {
            log.warn("[{}] ADD_FRIEND 실패: userId={}, friendId={}, 사유={}", instanceId, userId, friendId, e.getMessage());
            sendErrorResponse(userId, e.getMessage());
        }
    }

    private void handleRemoveFriend(String userId, JsonNode jsonNode) {
        String friendId = jsonNode.path("friendId").asText();
        if (friendId.isEmpty()) {
            log.warn("[{}] REMOVE_FRIEND: friendId가 비어있습니다: userId={}", instanceId, userId);
            return;
        }

        try {
            friendService.removeFriendDbOnly(userId, friendId);

            publishFriendNotify("FRIEND_REMOVED", userId, friendId);
            publishFriendNotify("FRIEND_REMOVED", friendId, userId);

            log.info("[{}] REMOVE_FRIEND 처리 완료: userId={}, friendId={}", instanceId, userId, friendId);
        } catch (Exception e) {
            log.error("[{}] REMOVE_FRIEND 실패: userId={}, friendId={}", instanceId, userId, friendId, e);
            sendErrorResponse(userId, "친구 제거에 실패했습니다");
        }
    }

    private void sendFriendActionResponse(String userId, String type, String friendId) {
        try {
            UserSession userSession = sessions.get(userId);
            if (userSession == null || !userSession.getSession().isOpen()) {
                return;
            }

            ObjectNode response = objectMapper.createObjectNode();
            response.put("type", type);
            response.put("friendId", friendId);

            userSession.getSession().sendMessage(
                    new TextMessage(objectMapper.writeValueAsString(response)));
        } catch (IOException e) {
            log.error("[{}] 친구 액션 응답 전송 실패: userId={}", instanceId, userId, e);
        }
    }

    private void sendErrorResponse(String userId, String message) {
        try {
            UserSession userSession = sessions.get(userId);
            if (userSession == null || !userSession.getSession().isOpen()) {
                return;
            }

            ObjectNode response = objectMapper.createObjectNode();
            response.put("type", "ERROR");
            response.put("message", message);

            userSession.getSession().sendMessage(
                    new TextMessage(objectMapper.writeValueAsString(response)));
        } catch (IOException e) {
            log.error("[{}] 에러 응답 전송 실패: userId={}", instanceId, userId, e);
        }
    }

    private BiConsumer<String, Message> createNodeAwareListener(String subscriberUserId) {
        return (String redisNodeId, Message message) -> {
            try {
                String json = new String(message.getBody());
                LocationUpdateMessage locationMessage = objectMapper.readValue(json, LocationUpdateMessage.class);

                String senderId = locationMessage.getUserId();
                if (senderId.equals(subscriberUserId)) {
                    return;
                }

                locationMessage.addPath(PropagationPathBuilder.subscribeReceive(redisNodeId));

                UserSession subscriberSession = sessions.get(subscriberUserId);
                if (subscriberSession == null || !subscriberSession.getSession().isOpen()) {
                    return;
                }

                double distance = DistanceCalculator.calculate(
                        subscriberSession.getX(), subscriberSession.getY(),
                        locationMessage.getX(), locationMessage.getY());
                boolean inRange = DistanceCalculator.isInRange(
                        subscriberSession.getX(), subscriberSession.getY(),
                        locationMessage.getX(), locationMessage.getY(), searchRadius);

                locationMessage.addPath(PropagationPathBuilder.deliver(instanceId));

                ObjectNode friendLocationMsg = objectMapper.createObjectNode();
                friendLocationMsg.put("type", "FRIEND_LOCATION");
                friendLocationMsg.put("friendId", senderId);
                friendLocationMsg.put("x", locationMessage.getX());
                friendLocationMsg.put("y", locationMessage.getY());
                friendLocationMsg.put("timestamp", locationMessage.getTimestamp());
                friendLocationMsg.put("distance", distance);
                friendLocationMsg.put("inRange", inRange);
                friendLocationMsg.set("path", objectMapper.valueToTree(locationMessage.getPath()));

                subscriberSession.getSession().sendMessage(
                        new TextMessage(objectMapper.writeValueAsString(friendLocationMsg)));

                broadcastPropagationLog(senderId, redisNodeId,
                        message.getChannel() != null ? new String(message.getChannel()) : CHANNEL_PREFIX + senderId,
                        subscriberUserId, distance, inRange);

            } catch (IOException e) {
                log.error("[{}] Pub/Sub 메시지 처리 실패: subscriberUserId={}", instanceId, subscriberUserId, e);
            }
        };
    }

    private void broadcastPropagationLog(String sourceUser, String redisNode, String channel,
                                          String subscriberUserId, double distance, boolean inRange) {
        try {
            ObjectNode logMsg = objectMapper.createObjectNode();
            logMsg.put("type", "PROPAGATION_LOG");
            logMsg.put("sourceUser", sourceUser);
            logMsg.put("wsServer", instanceId);
            logMsg.put("redisNode", redisNode);
            logMsg.put("channel", channel);

            ArrayNode subscribers = objectMapper.createArrayNode();
            ObjectNode subscriberInfo = objectMapper.createObjectNode();
            subscriberInfo.put("userId", subscriberUserId);
            subscriberInfo.put("wsServer", instanceId);
            subscriberInfo.put("distance", Math.round(distance));
            subscriberInfo.put("inRange", inRange);
            subscriberInfo.put("sent", true);
            subscribers.add(subscriberInfo);
            logMsg.set("subscribers", subscribers);

            String logJson = objectMapper.writeValueAsString(logMsg);
            TextMessage textMessage = new TextMessage(logJson);

            for (UserSession session : sessions.values()) {
                try {
                    if (session.getSession().isOpen()) {
                        session.getSession().sendMessage(textMessage);
                    }
                } catch (IOException e) {
                    log.warn("[{}] PROPAGATION_LOG 전송 실패: userId={}", instanceId, session.getUserId(), e);
                }
            }
        } catch (Exception e) {
            log.error("[{}] PROPAGATION_LOG 생성 실패", instanceId, e);
        }
    }

    /**
     * Redis Pub/Sub를 통해 친구 알림을 발행한다.
     *
     * @param type 알림 타입 (FRIEND_ADDED, FRIEND_REMOVED)
     * @param targetUserId 알림을 받을 사용자 ID
     * @param friendUserId 친구 사용자 ID
     */
    public void publishFriendNotify(String type, String targetUserId, String friendUserId) {
        try {
            ObjectNode notification = objectMapper.createObjectNode();
            notification.put("type", type);
            notification.put("targetUserId", targetUserId);
            notification.put("friendUserId", friendUserId);
            redisPubSubManager.publishToFixedNode(
                    FRIEND_NOTIFY_CHANNEL, objectMapper.writeValueAsString(notification));
        } catch (Exception e) {
            log.error("[{}] friend-notify 발행 실패: type={}, target={}, friend={}",
                    instanceId, type, targetUserId, friendUserId, e);
        }
    }

    private void handleFriendAddedNotification(String targetUserId, String friendUserId) {
        BiConsumer<String, Message> callback = userNodeListeners.get(targetUserId);
        if (callback == null) {
            return;
        }

        String channel = CHANNEL_PREFIX + friendUserId;
        ConcurrentHashMap<String, List<MessageListener>> channelMap = userChannelListeners.get(targetUserId);
        if (channelMap != null && channelMap.containsKey(channel)) {
            log.debug("[{}] 이미 구독 중: userId={}, channel={}", instanceId, targetUserId, channel);
        } else {
            List<MessageListener> listeners = redisPubSubManager.subscribeWithNodeInfo(channel, callback);
            trackSubscription(targetUserId, friendUserId, listeners);
            log.info("[{}] friend-notify로 구독 설정: userId={}, friendId={}", instanceId, targetUserId, friendUserId);
        }

        sendFriendAddedWithLocation(targetUserId, friendUserId);
    }

    private void handleFriendRemovedNotification(String targetUserId, String friendUserId) {
        List<MessageListener> listeners = getTrackedListeners(targetUserId, friendUserId);
        if (!listeners.isEmpty()) {
            String channel = CHANNEL_PREFIX + friendUserId;
            redisPubSubManager.unsubscribe(channel, listeners);
            removeTrackedSubscription(targetUserId, friendUserId);
            log.info("[{}] friend-notify로 구독 해제: userId={}, friendId={}", instanceId, targetUserId, friendUserId);
        }

        sendFriendActionResponse(targetUserId, "FRIEND_REMOVED", friendUserId);
    }

    private void sendFriendAddedWithLocation(String targetUserId, String friendUserId) {
        try {
            UserSession userSession = sessions.get(targetUserId);
            if (userSession == null || !userSession.getSession().isOpen()) {
                return;
            }

            ObjectNode response = objectMapper.createObjectNode();
            response.put("type", "FRIEND_ADDED");
            response.put("friendId", friendUserId);

            Optional<Location> loc = locationCacheService.getLocation(friendUserId);
            if (loc.isPresent()) {
                int friendX = loc.get().x();
                int friendY = loc.get().y();
                response.put("x", friendX);
                response.put("y", friendY);

                double distance = DistanceCalculator.calculate(
                        userSession.getX(), userSession.getY(), friendX, friendY);
                boolean inRange = DistanceCalculator.isInRange(
                        userSession.getX(), userSession.getY(), friendX, friendY, searchRadius);
                response.put("distance", distance);
                response.put("inRange", inRange);
            } else {
                response.put("x", 0);
                response.put("y", 0);
                response.put("distance", 0);
                response.put("inRange", false);
            }

            userSession.getSession().sendMessage(
                    new TextMessage(objectMapper.writeValueAsString(response)));
        } catch (IOException e) {
            log.error("[{}] FRIEND_ADDED 응답 전송 실패: userId={}", instanceId, targetUserId, e);
        }
    }

    private String extractUserIdFromQuery(WebSocketSession session) {
        if (session.getUri() == null || session.getUri().getQuery() == null) {
            return null;
        }
        for (String param : session.getUri().getQuery().split("&")) {
            String[] kv = param.split("=", 2);
            if (kv.length == 2 && "userId".equals(kv[0])) {
                return URLDecoder.decode(kv[1], StandardCharsets.UTF_8);
            }
        }
        return null;
    }

    /**
     * 주기적으로 모든 세션에 ping을 보내 dead session을 감지하고 정리한다.
     */
    @Scheduled(fixedRate = 15000)
    public void cleanupDeadSessions() {
        PingMessage ping = new PingMessage(ByteBuffer.wrap("ping".getBytes()));
        for (UserSession userSession : sessions.values()) {
            WebSocketSession ws = userSession.getSession();
            try {
                if (!ws.isOpen()) {
                    throw new IOException("세션이 이미 닫혀있음");
                }
                ws.sendMessage(ping);
            } catch (Exception e) {
                log.info("[{}] dead session 감지, 정리 시작: userId={}", instanceId, userSession.getUserId());
                try {
                    afterConnectionClosed(ws, CloseStatus.SESSION_NOT_RELIABLE);
                } catch (Exception ex) {
                    log.error("[{}] dead session 정리 실패: userId={}", instanceId, userSession.getUserId(), ex);
                }
            }
        }
    }

    private void broadcastUserList(String userListJson) {
        TextMessage textMessage = new TextMessage(userListJson);
        for (UserSession userSession : sessions.values()) {
            try {
                if (userSession.getSession().isOpen()) {
                    userSession.getSession().sendMessage(textMessage);
                }
            } catch (IOException e) {
                log.warn("[{}] USER_LIST 전송 실패: userId={}", instanceId, userSession.getUserId(), e);
            }
        }
    }
}
