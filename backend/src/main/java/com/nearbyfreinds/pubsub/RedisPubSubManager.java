package com.nearbyfreinds.pubsub;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BiConsumer;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.connection.MessageListener;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.listener.ChannelTopic;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;
import org.springframework.stereotype.Component;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nearbyfreinds.dto.LocationUpdateMessage;
import com.nearbyfreinds.hash.ConsistentHashRing;

@Component
public class RedisPubSubManager {

    private static final Logger log = LoggerFactory.getLogger(RedisPubSubManager.class);

    private final ConsistentHashRing<String> hashRing;
    private final Map<String, StringRedisTemplate> templateByNode;
    private final Map<String, RedisMessageListenerContainer> containerByNode;
    private final Set<String> subscribedChannels = ConcurrentHashMap.newKeySet();
    private final ObjectMapper objectMapper;

    public RedisPubSubManager(
            ConsistentHashRing<String> hashRing,
            Map<String, StringRedisTemplate> pubsubTemplates,
            Map<String, RedisMessageListenerContainer> pubsubContainers,
            ObjectMapper objectMapper) {
        this.hashRing = hashRing;
        this.objectMapper = objectMapper;
        this.templateByNode = new LinkedHashMap<>(pubsubTemplates);
        this.containerByNode = new LinkedHashMap<>(pubsubContainers);
    }

    /**
     * Consistent Hash Ring으로 대상 Redis 노드를 결정한 후 해당 노드에만 메시지를 publish한다.
     *
     * @param channel 채널명
     * @param message 위치 메시지
     */
    public void publish(String channel, LocationUpdateMessage message) {
        String targetNode = hashRing.getNode(channel);
        if (targetNode == null) {
            log.error("Hash Ring이 비어있어 publish할 수 없습니다: channel={}", channel);
            return;
        }

        StringRedisTemplate template = templateByNode.get(targetNode);
        if (template == null) {
            log.error("대상 노드의 RedisTemplate을 찾을 수 없습니다: node={}, channel={}", targetNode, channel);
            return;
        }

        try {
            String json = objectMapper.writeValueAsString(message);
            template.convertAndSend(channel, json);
            log.debug("메시지 publish 완료: channel={}, node={}", channel, targetNode);
        } catch (JsonProcessingException e) {
            log.error("메시지 직렬화 실패: channel={}", channel, e);
        } catch (Exception e) {
            log.error("메시지 publish 실패: channel={}, node={}", channel, targetNode, e);
        }
    }

    /**
     * 모든 Redis Pub/Sub 노드에서 해당 채널을 subscribe한다.
     *
     * @param channel 채널명
     * @param listener 메시지 수신 리스너
     */
    public void subscribe(String channel, MessageListener listener) {
        ChannelTopic topic = new ChannelTopic(channel);
        for (RedisMessageListenerContainer container : containerByNode.values()) {
            container.addMessageListener(listener, topic);
        }
        subscribedChannels.add(channel);
        log.debug("모든 노드에서 subscribe 완료: channel={}", channel);
    }

    /**
     * 모든 Redis Pub/Sub 노드에서 해당 채널을 subscribe하며, 노드별 리스너를 생성한다.
     * 리스너 팩토리에 Redis 노드 ID를 전달하여 메시지 출처를 식별할 수 있다.
     *
     * @param channel 채널명
     * @param listenerFactory (redisNodeId, message) 콜백을 받아 노드별 리스너를 생성하는 팩토리
     * @return 노드별로 생성된 리스너 목록
     */
    public List<MessageListener> subscribeWithNodeInfo(String channel, BiConsumer<String, org.springframework.data.redis.connection.Message> listenerFactory) {
        ChannelTopic topic = new ChannelTopic(channel);
        List<MessageListener> listeners = new ArrayList<>();
        for (Map.Entry<String, RedisMessageListenerContainer> entry : containerByNode.entrySet()) {
            String nodeId = entry.getKey();
            RedisMessageListenerContainer container = entry.getValue();
            MessageListener nodeListener = (message, pattern) -> listenerFactory.accept(nodeId, message);
            container.addMessageListener(nodeListener, topic);
            listeners.add(nodeListener);
        }
        subscribedChannels.add(channel);
        log.debug("모든 노드에서 노드 식별 subscribe 완료: channel={}", channel);
        return listeners;
    }

    /**
     * 모든 Redis Pub/Sub 노드에서 해당 채널의 모든 리스너를 unsubscribe한다.
     *
     * @param channel 채널명
     * @deprecated 다중 사용자 환경에서 다른 사용자의 리스너도 제거됨. unsubscribe(channel, listeners) 사용 권장.
     */
    @Deprecated
    public void unsubscribe(String channel) {
        ChannelTopic topic = new ChannelTopic(channel);
        for (RedisMessageListenerContainer container : containerByNode.values()) {
            container.removeMessageListener(null, topic);
        }
        subscribedChannels.remove(channel);
        log.debug("모든 노드에서 unsubscribe 완료: channel={}", channel);
    }

    /**
     * 모든 Redis Pub/Sub 노드에서 특정 리스너들만 unsubscribe한다.
     *
     * @param channel 채널명
     * @param listeners 제거할 리스너 목록 (subscribeWithNodeInfo 반환값)
     */
    public void unsubscribe(String channel, List<MessageListener> listeners) {
        ChannelTopic topic = new ChannelTopic(channel);
        for (RedisMessageListenerContainer container : containerByNode.values()) {
            for (MessageListener listener : listeners) {
                container.removeMessageListener(listener, topic);
            }
        }
        log.debug("특정 리스너 unsubscribe 완료: channel={}, listenerCount={}", channel, listeners.size());
    }

    /**
     * Hash Ring으로 채널의 대상 Redis Pub/Sub 노드를 조회한다.
     *
     * @param channel 채널명
     * @return 대상 노드 ID, Ring이 비어있으면 null
     */
    public String getTargetNode(String channel) {
        return hashRing.getNode(channel);
    }

    /**
     * 현재 구독 중인 채널 목록을 반환한다.
     *
     * @return 구독 중인 채널 Set
     */
    public Set<String> getSubscribedChannels() {
        return Set.copyOf(subscribedChannels);
    }

    /**
     * 고정 노드(첫 번째 노드)에 문자열 메시지를 publish한다.
     * Hash Ring과 무관하게 항상 동일한 노드를 사용한다.
     *
     * @param channel 채널명
     * @param jsonMessage JSON 문자열 메시지
     */
    public void publishToFixedNode(String channel, String jsonMessage) {
        Map.Entry<String, StringRedisTemplate> firstEntry = templateByNode.entrySet().iterator().next();
        StringRedisTemplate template = firstEntry.getValue();
        template.convertAndSend(channel, jsonMessage);
        log.debug("고정 노드에 메시지 publish 완료: channel={}, node={}", channel, firstEntry.getKey());
    }

    /**
     * 고정 노드(첫 번째 노드)에서 해당 채널을 subscribe한다.
     *
     * @param channel 채널명
     * @param listener 메시지 수신 리스너
     */
    public void subscribeOnFixedNode(String channel, MessageListener listener) {
        Map.Entry<String, RedisMessageListenerContainer> firstEntry = containerByNode.entrySet().iterator().next();
        firstEntry.getValue().addMessageListener(listener, new ChannelTopic(channel));
        log.debug("고정 노드에서 subscribe 완료: channel={}, node={}", channel, firstEntry.getKey());
    }

    /**
     * 설정에 등록된 모든 Redis Pub/Sub 노드 ID 목록을 반환한다.
     *
     * @return 등록된 노드 ID Set
     */
    public Set<String> getKnownNodes() {
        return Set.copyOf(templateByNode.keySet());
    }

    /**
     * Redis 노드별 구독 채널 목록을 반환한다.
     *
     * @return 노드ID -> 채널 목록 맵
     */
    public Map<String, List<String>> getChannelsByNode() {
        Map<String, List<String>> result = new HashMap<>();
        for (String nodeId : templateByNode.keySet()) {
            result.put(nodeId, new ArrayList<>());
        }

        for (String channel : subscribedChannels) {
            String targetNode = hashRing.getNode(channel);
            if (targetNode != null && result.containsKey(targetNode)) {
                result.get(targetNode).add(channel);
            }
        }
        return result;
    }
}
