package com.nearbyfreinds.controller;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.Cursor;
import org.springframework.data.redis.core.ScanOptions;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nearbyfreinds.dto.Location;
import com.nearbyfreinds.hash.ConsistentHashRing;
import com.nearbyfreinds.hash.VirtualNodeInfo;
import com.nearbyfreinds.pubsub.RedisPubSubManager;
import com.nearbyfreinds.websocket.LocationWebSocketHandler;
import com.nearbyfreinds.websocket.UserSession;

@RestController
@RequestMapping("/api/system")
public class SystemController {

    private static final Logger log = LoggerFactory.getLogger(SystemController.class);
    private static final String CACHE_KEY_PREFIX = "location:user:";
    private static final String CACHE_KEY_PATTERN = "location:user:*";

    private final ConsistentHashRing<String> hashRing;
    private final LocationWebSocketHandler webSocketHandler;
    private final RedisPubSubManager pubSubManager;
    private final StringRedisTemplate cacheRedisTemplate;
    private final ObjectMapper objectMapper;

    @Value("${app.instance-id}")
    private String instanceId;

    public SystemController(
            ConsistentHashRing<String> hashRing,
            LocationWebSocketHandler webSocketHandler,
            RedisPubSubManager pubSubManager,
            @Qualifier("cacheRedisTemplate") StringRedisTemplate cacheRedisTemplate,
            ObjectMapper objectMapper) {
        this.hashRing = hashRing;
        this.webSocketHandler = webSocketHandler;
        this.pubSubManager = pubSubManager;
        this.cacheRedisTemplate = cacheRedisTemplate;
        this.objectMapper = objectMapper;
    }

    /**
     * Hash Ring 현재 상태를 반환한다.
     */
    @GetMapping("/hash-ring")
    public Map<String, Object> getHashRing() {
        List<VirtualNodeInfo<String>> ringState = hashRing.getRingState();

        List<Map<String, Object>> nodes = new ArrayList<>();
        for (VirtualNodeInfo<String> info : ringState) {
            Map<String, Object> node = new LinkedHashMap<>();
            node.put("position", info.position());
            node.put("server", info.physicalNode());
            node.put("virtual", info.virtualIndex() != 0);
            node.put("virtualIndex", info.virtualIndex());
            nodes.add(node);
        }

        Map<String, String> channelMapping = new HashMap<>();
        Set<String> subscribedChannels = pubSubManager.getSubscribedChannels();
        for (String channel : subscribedChannels) {
            String targetNode = pubSubManager.getTargetNode(channel);
            if (targetNode != null) {
                channelMapping.put(channel, targetNode);
            }
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("nodes", nodes);
        result.put("channelMapping", channelMapping);
        return result;
    }

    /**
     * 현재 WS서버의 연결 사용자 목록을 반환한다.
     */
    @GetMapping("/connections")
    public Map<String, Object> getConnections() {
        Map<String, UserSession> sessions = webSocketHandler.getSessions();

        List<Map<String, Object>> users = new ArrayList<>();
        for (UserSession session : sessions.values()) {
            Map<String, Object> user = new LinkedHashMap<>();
            user.put("userId", session.getUserId());
            user.put("x", session.getX());
            user.put("y", session.getY());
            users.add(user);
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("serverId", instanceId);
        result.put("users", users);
        result.put("totalConnections", users.size());
        return result;
    }

    /**
     * Redis 노드별 구독 채널 목록을 반환한다.
     */
    @GetMapping("/channels")
    public Map<String, Object> getChannels() {
        Map<String, List<String>> channelsByNode = pubSubManager.getChannelsByNode();
        List<String> hashRingNodes = hashRing.getAllNodes();

        Map<String, Object> result = new LinkedHashMap<>();
        for (Map.Entry<String, List<String>> entry : channelsByNode.entrySet()) {
            Map<String, Object> nodeInfo = new LinkedHashMap<>();
            nodeInfo.put("channels", entry.getValue());
            nodeInfo.put("subscriberCount", entry.getValue().size());
            nodeInfo.put("inHashRing", hashRingNodes.contains(entry.getKey()));
            result.put(entry.getKey(), nodeInfo);
        }
        return result;
    }

    /**
     * Hash Ring에서 노드를 제거한다. 채널이 나머지 살아있는 노드로 재매핑된다.
     *
     * @param node 제거할 노드 ID (예: redis-pubsub-1:6380)
     */
    @PostMapping("/remove-node")
    public Map<String, Object> removeNode(@RequestParam String node) {
        List<String> allNodes = hashRing.getAllNodes();
        boolean existed = allNodes.contains(node);

        Map<String, Object> result = new LinkedHashMap<>();
        if (!existed) {
            result.put("success", false);
            result.put("message", "Hash Ring에 존재하지 않는 노드입니다: " + node);
            return result;
        }

        hashRing.removeNode(node);
        log.info("Hash Ring에서 노드 제거: {}", node);

        result.put("success", true);
        result.put("removedNode", node);
        result.put("remainingNodes", hashRing.getAllNodes());
        return result;
    }

    /**
     * Hash Ring에 노드를 추가한다.
     *
     * @param node 추가할 노드 ID (예: redis-pubsub-1:6380)
     */
    @PostMapping("/add-node")
    public Map<String, Object> addNode(@RequestParam String node) {
        List<String> allNodes = hashRing.getAllNodes();
        boolean alreadyExists = allNodes.contains(node);

        Map<String, Object> result = new LinkedHashMap<>();
        if (alreadyExists) {
            result.put("success", false);
            result.put("message", "Hash Ring에 이미 존재하는 노드입니다: " + node);
            return result;
        }

        if (!pubSubManager.getKnownNodes().contains(node)) {
            result.put("success", false);
            result.put("message", "알 수 없는 Redis 노드입니다: " + node);
            return result;
        }

        hashRing.addNode(node);
        log.info("Hash Ring에 노드 추가: {}", node);

        result.put("success", true);
        result.put("addedNode", node);
        result.put("allNodes", hashRing.getAllNodes());
        return result;
    }

    /**
     * Redis 캐시의 위치 데이터를 SCAN으로 조회하여 반환한다.
     */
    @GetMapping("/cache")
    public List<Map<String, Object>> getCache() {
        List<Map<String, Object>> result = new ArrayList<>();
        ScanOptions options = ScanOptions.scanOptions()
                .match(CACHE_KEY_PATTERN)
                .count(100)
                .build();

        try (Cursor<String> cursor = cacheRedisTemplate.scan(options)) {
            while (cursor.hasNext()) {
                String key = cursor.next();
                String userId = key.substring(CACHE_KEY_PREFIX.length());
                String value = cacheRedisTemplate.opsForValue().get(key);
                if (value == null) {
                    continue;
                }

                Location location = objectMapper.readValue(value, Location.class);
                Long ttl = cacheRedisTemplate.getExpire(key, TimeUnit.SECONDS);

                Map<String, Object> entry = new LinkedHashMap<>();
                entry.put("userId", userId);
                entry.put("x", location.x());
                entry.put("y", location.y());
                entry.put("timestamp", location.timestamp());
                entry.put("ttl", ttl != null ? ttl : -1);
                result.add(entry);
            }
        } catch (Exception e) {
            log.error("캐시 SCAN 실패", e);
        }

        return result;
    }
}
