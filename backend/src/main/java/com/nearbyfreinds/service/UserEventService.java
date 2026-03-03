package com.nearbyfreinds.service;

import java.io.IOException;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.nearbyfreinds.pubsub.RedisPubSubManager;
import com.nearbyfreinds.websocket.UserSession;

import jakarta.annotation.PostConstruct;

@Service
public class UserEventService {

    private static final Logger log = LoggerFactory.getLogger(UserEventService.class);
    private static final String SYSTEM_USER_EVENTS_CHANNEL = "system:user-events";
    private static final String TYPE_USER_CONNECTED = "USER_CONNECTED";
    private static final String TYPE_USER_DISCONNECTED = "USER_DISCONNECTED";

    private final RedisPubSubManager redisPubSubManager;
    private final ObjectMapper objectMapper;
    private final ConcurrentHashMap<String, UserInfo> allConnectedUsers = new ConcurrentHashMap<>();

    @Value("${app.instance-id}")
    private String instanceId;

    private volatile UserListBroadcaster broadcaster;

    public UserEventService(RedisPubSubManager redisPubSubManager, ObjectMapper objectMapper) {
        this.redisPubSubManager = redisPubSubManager;
        this.objectMapper = objectMapper;
    }

    /**
     * USER_LIST 브로드캐스트를 위한 콜백 인터페이스를 설정한다.
     *
     * @param broadcaster 로컬 세션에 USER_LIST를 전송하는 콜백
     */
    public void setBroadcaster(UserListBroadcaster broadcaster) {
        this.broadcaster = broadcaster;
    }

    @PostConstruct
    void subscribeToUserEvents() {
        redisPubSubManager.subscribeOnFixedNode(SYSTEM_USER_EVENTS_CHANNEL, (message, pattern) -> {
            try {
                String json = new String(message.getBody());
                JsonNode event = objectMapper.readTree(json);
                String type = event.path("type").asText();
                String userId = event.path("userId").asText();
                String wsServer = event.path("wsServer").asText();

                if (TYPE_USER_CONNECTED.equals(type)) {
                    allConnectedUsers.put(userId, new UserInfo(userId, wsServer));
                    log.debug("접속자 추가: userId={}, wsServer={}", userId, wsServer);
                } else if (TYPE_USER_DISCONNECTED.equals(type)) {
                    allConnectedUsers.remove(userId);
                    log.debug("접속자 제거: userId={}, wsServer={}", userId, wsServer);
                }

                broadcastUserList();
            } catch (Exception e) {
                log.error("[{}] 사용자 이벤트 처리 실패", instanceId, e);
            }
        });
        log.info("[{}] 시스템 이벤트 채널 구독 완료: channel={}", instanceId, SYSTEM_USER_EVENTS_CHANNEL);
    }

    /**
     * 사용자 접속 이벤트를 시스템 채널에 발행한다.
     *
     * @param userId 접속한 사용자 ID
     */
    public void publishUserConnected(String userId) {
        try {
            ObjectNode event = objectMapper.createObjectNode();
            event.put("type", TYPE_USER_CONNECTED);
            event.put("userId", userId);
            event.put("wsServer", instanceId);
            redisPubSubManager.publishToFixedNode(SYSTEM_USER_EVENTS_CHANNEL, objectMapper.writeValueAsString(event));
        } catch (Exception e) {
            log.error("[{}] 접속 이벤트 발행 실패: userId={}", instanceId, userId, e);
        }
    }

    /**
     * 사용자 해제 이벤트를 시스템 채널에 발행한다.
     *
     * @param userId 해제된 사용자 ID
     */
    public void publishUserDisconnected(String userId) {
        try {
            ObjectNode event = objectMapper.createObjectNode();
            event.put("type", TYPE_USER_DISCONNECTED);
            event.put("userId", userId);
            event.put("wsServer", instanceId);
            redisPubSubManager.publishToFixedNode(SYSTEM_USER_EVENTS_CHANNEL, objectMapper.writeValueAsString(event));
        } catch (Exception e) {
            log.error("[{}] 해제 이벤트 발행 실패: userId={}", instanceId, userId, e);
        }
    }

    /**
     * 현재 전체 접속자 목록을 USER_LIST JSON 문자열로 생성한다.
     *
     * @return USER_LIST JSON 문자열
     */
    public String buildUserListMessage() throws IOException {
        ObjectNode message = objectMapper.createObjectNode();
        message.put("type", "USER_LIST");

        ArrayNode usersArray = objectMapper.createArrayNode();
        for (UserInfo userInfo : allConnectedUsers.values()) {
            ObjectNode userNode = objectMapper.createObjectNode();
            userNode.put("id", userInfo.userId());
            userNode.put("online", true);
            usersArray.add(userNode);
        }
        message.set("users", usersArray);

        return objectMapper.writeValueAsString(message);
    }

    /**
     * 전체 접속자 목록을 반환한다.
     *
     * @return userId -> UserInfo 맵
     */
    public Map<String, UserInfo> getAllConnectedUsers() {
        return Map.copyOf(allConnectedUsers);
    }

    private void broadcastUserList() {
        if (broadcaster == null) {
            return;
        }
        try {
            String userListJson = buildUserListMessage();
            broadcaster.broadcast(userListJson);
        } catch (Exception e) {
            log.error("[{}] USER_LIST 브로드캐스트 실패", instanceId, e);
        }
    }

    public record UserInfo(String userId, String wsServer) {}

    @FunctionalInterface
    public interface UserListBroadcaster {
        void broadcast(String userListJson);
    }
}
