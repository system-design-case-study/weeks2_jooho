package com.nearbyfreinds.pubsub;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.BiConsumer;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.redis.connection.Message;
import org.springframework.data.redis.connection.MessageListener;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.listener.ChannelTopic;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nearbyfreinds.dto.LocationUpdateMessage;
import com.nearbyfreinds.hash.ConsistentHashRing;

class RedisPubSubManagerTest {

    private static final String NODE_1 = "redis-pubsub-1:6380";
    private static final String NODE_2 = "redis-pubsub-2:6381";

    private ConsistentHashRing<String> hashRing;
    private StringRedisTemplate pubsub1Template;
    private StringRedisTemplate pubsub2Template;
    private RedisMessageListenerContainer pubsub1Container;
    private RedisMessageListenerContainer pubsub2Container;
    private RedisPubSubManager manager;

    @BeforeEach
    void setUp() {
        hashRing = new ConsistentHashRing<>();
        hashRing.addNode(NODE_1);
        hashRing.addNode(NODE_2);

        pubsub1Template = mock(StringRedisTemplate.class);
        pubsub2Template = mock(StringRedisTemplate.class);
        pubsub1Container = mock(RedisMessageListenerContainer.class);
        pubsub2Container = mock(RedisMessageListenerContainer.class);

        manager = new RedisPubSubManager(
                hashRing,
                NODE_1 + "," + NODE_2,
                pubsub1Template,
                pubsub2Template,
                pubsub1Container,
                pubsub2Container,
                new ObjectMapper());
    }

    @Test
    @DisplayName("publish - Hash Ring으로 결정된 노드에만 메시지를 전송한다")
    void publishSendsToHashRingDeterminedNode() {
        // given
        String channel = "user:1";
        LocationUpdateMessage message = new LocationUpdateMessage("user-1", 100, 200, System.currentTimeMillis(), List.of());
        String targetNode = hashRing.getNode(channel);

        // when
        manager.publish(channel, message);

        // then
        if (NODE_1.equals(targetNode)) {
            verify(pubsub1Template).convertAndSend(eq(channel), anyString());
            verify(pubsub2Template, never()).convertAndSend(anyString(), anyString());
        } else {
            verify(pubsub2Template).convertAndSend(eq(channel), anyString());
            verify(pubsub1Template, never()).convertAndSend(anyString(), anyString());
        }
    }

    @Test
    @DisplayName("subscribe - 모든 Redis 노드에서 채널을 구독한다")
    void subscribeOnAllNodes() {
        // given
        String channel = "user:1";
        MessageListener listener = mock(MessageListener.class);

        // when
        manager.subscribe(channel, listener);

        // then
        ArgumentCaptor<ChannelTopic> topicCaptor1 = ArgumentCaptor.forClass(ChannelTopic.class);
        ArgumentCaptor<ChannelTopic> topicCaptor2 = ArgumentCaptor.forClass(ChannelTopic.class);
        verify(pubsub1Container).addMessageListener(eq(listener), topicCaptor1.capture());
        verify(pubsub2Container).addMessageListener(eq(listener), topicCaptor2.capture());
        assertThat(topicCaptor1.getValue().getTopic()).isEqualTo(channel);
        assertThat(topicCaptor2.getValue().getTopic()).isEqualTo(channel);
    }

    @Test
    @DisplayName("unsubscribe - 모든 Redis 노드에서 채널 구독을 해제한다")
    void unsubscribeFromAllNodes() {
        // given
        String channel = "user:1";
        MessageListener listener = mock(MessageListener.class);
        manager.subscribe(channel, listener);

        // when
        manager.unsubscribe(channel);

        // then
        ArgumentCaptor<ChannelTopic> topicCaptor1 = ArgumentCaptor.forClass(ChannelTopic.class);
        ArgumentCaptor<ChannelTopic> topicCaptor2 = ArgumentCaptor.forClass(ChannelTopic.class);
        verify(pubsub1Container).removeMessageListener(eq(null), topicCaptor1.capture());
        verify(pubsub2Container).removeMessageListener(eq(null), topicCaptor2.capture());
        assertThat(topicCaptor1.getValue().getTopic()).isEqualTo(channel);
        assertThat(topicCaptor2.getValue().getTopic()).isEqualTo(channel);
    }

    @Test
    @DisplayName("unsubscribe 후 subscribedChannels에서 채널이 제거된다")
    void unsubscribeRemovesFromSubscribedChannels() {
        // given
        String channel = "user:1";
        MessageListener listener = mock(MessageListener.class);
        manager.subscribe(channel, listener);
        assertThat(manager.getSubscribedChannels()).contains(channel);

        // when
        manager.unsubscribe(channel);

        // then
        assertThat(manager.getSubscribedChannels()).doesNotContain(channel);
    }

    @Test
    @DisplayName("여러 채널을 subscribe하면 동일한 Hash Ring 결과로 publish된다")
    void publishMultipleChannelsToCorrectNodes() {
        // given
        String channel1 = "user:1";
        String channel2 = "user:2";
        LocationUpdateMessage msg1 = new LocationUpdateMessage("user-1", 100, 200, System.currentTimeMillis(), List.of());
        LocationUpdateMessage msg2 = new LocationUpdateMessage("user-2", 300, 400, System.currentTimeMillis(), List.of());

        String node1 = hashRing.getNode(channel1);
        String node2 = hashRing.getNode(channel2);

        // when
        manager.publish(channel1, msg1);
        manager.publish(channel2, msg2);

        // then
        StringRedisTemplate expectedTemplate1 = NODE_1.equals(node1) ? pubsub1Template : pubsub2Template;
        StringRedisTemplate expectedTemplate2 = NODE_1.equals(node2) ? pubsub1Template : pubsub2Template;
        verify(expectedTemplate1).convertAndSend(eq(channel1), anyString());
        verify(expectedTemplate2).convertAndSend(eq(channel2), anyString());
    }

    @Test
    @DisplayName("subscribe는 subscribedChannels에 채널을 추가한다")
    void subscribeAddsToSubscribedChannels() {
        // given
        String channel = "user:1";
        MessageListener listener = mock(MessageListener.class);

        // when
        manager.subscribe(channel, listener);

        // then
        assertThat(manager.getSubscribedChannels()).contains(channel);
    }

    @Test
    @DisplayName("getChannelsByNode - 구독 채널을 Hash Ring 기반으로 노드별 그룹핑한다")
    void getChannelsByNode_groupsChannelsByHashRingNode() {
        // given
        MessageListener listener = mock(MessageListener.class);
        manager.subscribe("user:1", listener);
        manager.subscribe("user:2", listener);
        manager.subscribe("user:3", listener);

        // when
        Map<String, List<String>> result = manager.getChannelsByNode();

        // then
        assertThat(result).containsKeys(NODE_1, NODE_2);
        int totalChannels = result.get(NODE_1).size() + result.get(NODE_2).size();
        assertThat(totalChannels).isEqualTo(3);
    }

    @Test
    @DisplayName("getChannelsByNode - 구독 채널이 없으면 빈 리스트를 반환한다")
    void getChannelsByNode_returnsEmptyListsWhenNoSubscriptions() {
        // when
        Map<String, List<String>> result = manager.getChannelsByNode();

        // then
        assertThat(result).containsKeys(NODE_1, NODE_2);
        assertThat(result.get(NODE_1)).isEmpty();
        assertThat(result.get(NODE_2)).isEmpty();
    }

    @Test
    @DisplayName("subscribeWithNodeInfo - 노드별로 리스너를 분리하여 등록한다")
    void subscribeWithNodeInfo_registersPerNodeListeners() {
        // given
        String channel = "user:1";
        List<String> receivedNodeIds = new ArrayList<>();
        BiConsumer<String, Message> callback = (nodeId, msg) -> receivedNodeIds.add(nodeId);

        // when
        List<MessageListener> listeners = manager.subscribeWithNodeInfo(channel, callback);

        // then
        assertThat(listeners).hasSize(2);
        verify(pubsub1Container).addMessageListener(any(MessageListener.class), any(ChannelTopic.class));
        verify(pubsub2Container).addMessageListener(any(MessageListener.class), any(ChannelTopic.class));
        assertThat(manager.getSubscribedChannels()).contains(channel);
    }

    @Test
    @DisplayName("subscribeWithNodeInfo - 각 노드 리스너가 올바른 노드 ID를 전달한다")
    void subscribeWithNodeInfo_passesCorrectNodeId() {
        // given
        String channel = "user:1";
        List<String> receivedNodeIds = new ArrayList<>();
        BiConsumer<String, Message> callback = (nodeId, msg) -> receivedNodeIds.add(nodeId);

        manager.subscribeWithNodeInfo(channel, callback);

        // when
        ArgumentCaptor<MessageListener> listenerCaptor = ArgumentCaptor.forClass(MessageListener.class);
        verify(pubsub1Container).addMessageListener(listenerCaptor.capture(), any(ChannelTopic.class));

        Message mockMessage = mock(Message.class);
        listenerCaptor.getValue().onMessage(mockMessage, null);

        // then
        assertThat(receivedNodeIds).hasSize(1);
        assertThat(receivedNodeIds.get(0)).isEqualTo(NODE_1);
    }
}
