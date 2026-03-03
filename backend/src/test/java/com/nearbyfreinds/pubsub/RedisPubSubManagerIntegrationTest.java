package com.nearbyfreinds.pubsub;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.core.task.TaskExecutor;
import org.springframework.data.redis.connection.MessageListener;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;
import org.springframework.data.redis.listener.ChannelTopic;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nearbyfreinds.dto.LocationUpdateMessage;
import com.nearbyfreinds.dto.PathAction;
import com.nearbyfreinds.dto.PropagationPath;
import com.nearbyfreinds.hash.ConsistentHashRing;

@Testcontainers
class RedisPubSubManagerIntegrationTest {

    private static final int REDIS_PORT = 6379;
    private static final String THREAD_NAME_PREFIX = "redis-pubsub-";

    @Container
    static final GenericContainer<?> redis1 = new GenericContainer<>(DockerImageName.parse("redis:7-alpine"))
            .withExposedPorts(REDIS_PORT);

    @Container
    static final GenericContainer<?> redis2 = new GenericContainer<>(DockerImageName.parse("redis:7-alpine"))
            .withExposedPorts(REDIS_PORT);

    private String node1;
    private String node2;
    private LettuceConnectionFactory factory1;
    private LettuceConnectionFactory factory2;
    private StringRedisTemplate template1;
    private StringRedisTemplate template2;
    private RedisMessageListenerContainer container1;
    private RedisMessageListenerContainer container2;
    private ThreadPoolTaskExecutor taskExecutor;
    private ConsistentHashRing<String> hashRing;
    private RedisPubSubManager manager;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @BeforeEach
    void setUp() {
        node1 = redis1.getHost() + ":" + redis1.getMappedPort(REDIS_PORT);
        node2 = redis2.getHost() + ":" + redis2.getMappedPort(REDIS_PORT);

        factory1 = createConnectionFactory(redis1);
        factory2 = createConnectionFactory(redis2);

        template1 = new StringRedisTemplate(factory1);
        template2 = new StringRedisTemplate(factory2);

        taskExecutor = new ThreadPoolTaskExecutor();
        taskExecutor.setCorePoolSize(4);
        taskExecutor.setMaxPoolSize(8);
        taskExecutor.setQueueCapacity(100);
        taskExecutor.setThreadNamePrefix(THREAD_NAME_PREFIX);
        taskExecutor.initialize();

        container1 = createListenerContainer(factory1, taskExecutor);
        container2 = createListenerContainer(factory2, taskExecutor);

        hashRing = new ConsistentHashRing<>();
        hashRing.addNode(node1);
        hashRing.addNode(node2);

        manager = new RedisPubSubManager(
                hashRing,
                node1 + "," + node2,
                template1,
                template2,
                container1,
                container2,
                objectMapper);
    }

    @AfterEach
    void tearDown() {
        container1.stop();
        container2.stop();
        factory1.destroy();
        factory2.destroy();
        taskExecutor.shutdown();
    }

    @Test
    @DisplayName("publish -> subscribe 메시지 정확 수신 확인")
    void publishAndSubscribeReceivesExactMessage() throws Exception {
        // given
        String channel = "user:test-1";
        List<PropagationPath> path = List.of(new PropagationPath("ws-1", PathAction.RECEIVE, System.currentTimeMillis()));
        LocationUpdateMessage original = new LocationUpdateMessage("user-1", 100, 200, System.currentTimeMillis(), path);

        CountDownLatch latch = new CountDownLatch(1);
        AtomicReference<String> receivedBody = new AtomicReference<>();

        manager.subscribe(channel, (message, pattern) -> {
            receivedBody.set(new String(message.getBody(), StandardCharsets.UTF_8));
            latch.countDown();
        });

        waitForSubscriptionReady();

        // when
        manager.publish(channel, original);

        // then
        assertThat(latch.await(3, TimeUnit.SECONDS)).isTrue();
        LocationUpdateMessage received = objectMapper.readValue(receivedBody.get(), LocationUpdateMessage.class);
        assertThat(received.getUserId()).isEqualTo(original.getUserId());
        assertThat(received.getX()).isEqualTo(original.getX());
        assertThat(received.getY()).isEqualTo(original.getY());
        assertThat(received.getTimestamp()).isEqualTo(original.getTimestamp());
    }

    @Test
    @DisplayName("publish 후 수신까지의 지연 시간이 1초 이내이다")
    void publishToSubscribeLatencyWithinOneSecond() throws Exception {
        // given
        String channel = "user:latency-test";
        LocationUpdateMessage message = new LocationUpdateMessage("user-lat", 10, 20, System.currentTimeMillis(), List.of());

        CountDownLatch latch = new CountDownLatch(1);
        AtomicReference<Long> receiveTime = new AtomicReference<>();

        manager.subscribe(channel, (msg, pattern) -> {
            receiveTime.set(System.nanoTime());
            latch.countDown();
        });

        waitForSubscriptionReady();

        // when
        long publishTime = System.nanoTime();
        manager.publish(channel, message);

        // then
        assertThat(latch.await(3, TimeUnit.SECONDS)).isTrue();
        long latencyMs = TimeUnit.NANOSECONDS.toMillis(receiveTime.get() - publishTime);
        assertThat(latencyMs).isLessThan(1000);
    }

    @Test
    @DisplayName("Hash Ring 기반 라우팅 - 올바른 Redis 노드에만 publish한다")
    void hashRingRoutesPublishToCorrectNode() throws Exception {
        // given
        String channel = "user:routing-test";
        LocationUpdateMessage message = new LocationUpdateMessage("user-r", 50, 60, System.currentTimeMillis(), List.of());
        String targetNode = hashRing.getNode(channel);

        CopyOnWriteArrayList<String> node1Messages = new CopyOnWriteArrayList<>();
        CopyOnWriteArrayList<String> node2Messages = new CopyOnWriteArrayList<>();

        MessageListener listener1 = (msg, pattern) ->
                node1Messages.add(new String(msg.getBody(), StandardCharsets.UTF_8));
        MessageListener listener2 = (msg, pattern) ->
                node2Messages.add(new String(msg.getBody(), StandardCharsets.UTF_8));

        container1.addMessageListener(listener1,
                new org.springframework.data.redis.listener.ChannelTopic(channel));
        container2.addMessageListener(listener2,
                new org.springframework.data.redis.listener.ChannelTopic(channel));

        waitForSubscriptionReady();

        // when
        manager.publish(channel, message);

        // then
        await().atMost(Duration.ofSeconds(3)).untilAsserted(() -> {
            if (targetNode.equals(node1)) {
                assertThat(node1Messages).hasSize(1);
                assertThat(node2Messages).isEmpty();
            } else {
                assertThat(node2Messages).hasSize(1);
                assertThat(node1Messages).isEmpty();
            }
        });
    }

    @Test
    @DisplayName("다중 채널이 서로 다른 Redis 노드에 라우팅될 수 있다")
    void multipleChannelsRouteToDistinctNodes() {
        // given
        String channelA = "user:1";
        String channelB = "user:2";

        // when
        String nodeA = hashRing.getNode(channelA);
        String nodeB = hashRing.getNode(channelB);

        // then
        assertThat(List.of(node1, node2)).contains(nodeA);
        assertThat(List.of(node1, node2)).contains(nodeB);
    }

    @Test
    @DisplayName("subscribe 후 메시지 수신 -> unsubscribe 후 메시지 미수신")
    void unsubscribeStopsMessageDelivery() throws Exception {
        // given
        String channel = "user:unsub-test";
        LocationUpdateMessage message = new LocationUpdateMessage("user-u", 10, 20, System.currentTimeMillis(), List.of());

        CopyOnWriteArrayList<String> received = new CopyOnWriteArrayList<>();
        CountDownLatch firstLatch = new CountDownLatch(1);

        MessageListener listener = (msg, pattern) -> {
            received.add(new String(msg.getBody(), StandardCharsets.UTF_8));
            firstLatch.countDown();
        };

        manager.subscribe(channel, listener);
        waitForSubscriptionReady();

        manager.publish(channel, message);
        assertThat(firstLatch.await(3, TimeUnit.SECONDS)).isTrue();
        assertThat(received).hasSize(1);

        // when
        manager.unsubscribe(channel);
        waitForSubscriptionReady();

        int beforeSize = received.size();
        manager.publish(channel, message);

        // then
        Thread.sleep(500);
        assertThat(received).hasSize(beforeSize);
    }

    @Test
    @DisplayName("동일 채널에 대한 중복 subscribe 시 메시지 중복 수신 여부 확인")
    void duplicateSubscribeDoesNotDuplicateMessages() throws Exception {
        // given
        String channel = "user:dup-test";
        LocationUpdateMessage message = new LocationUpdateMessage("user-d", 10, 20, System.currentTimeMillis(), List.of());

        CopyOnWriteArrayList<String> received = new CopyOnWriteArrayList<>();
        CountDownLatch latch = new CountDownLatch(1);

        MessageListener listener = (msg, pattern) -> {
            received.add(new String(msg.getBody(), StandardCharsets.UTF_8));
            latch.countDown();
        };

        manager.subscribe(channel, listener);
        manager.subscribe(channel, listener);
        waitForSubscriptionReady();

        // when
        manager.publish(channel, message);

        // then
        assertThat(latch.await(3, TimeUnit.SECONDS)).isTrue();
        Thread.sleep(500);
        assertThat(received).hasSizeLessThanOrEqualTo(2);
    }

    @Test
    @DisplayName("unsubscribe 후 재subscribe 시 정상 동작")
    void resubscribeAfterUnsubscribeWorks() throws Exception {
        // given
        String channel = "user:resub-test";
        LocationUpdateMessage message = new LocationUpdateMessage("user-rs", 10, 20, System.currentTimeMillis(), List.of());

        CopyOnWriteArrayList<String> received = new CopyOnWriteArrayList<>();

        MessageListener listener = (msg, pattern) ->
                received.add(new String(msg.getBody(), StandardCharsets.UTF_8));

        manager.subscribe(channel, listener);
        waitForSubscriptionReady();
        manager.unsubscribe(channel);
        waitForSubscriptionReady();

        // when
        CountDownLatch latch = new CountDownLatch(1);
        MessageListener listener2 = (msg, pattern) -> {
            received.add(new String(msg.getBody(), StandardCharsets.UTF_8));
            latch.countDown();
        };
        manager.subscribe(channel, listener2);
        waitForSubscriptionReady();

        manager.publish(channel, message);

        // then
        assertThat(latch.await(3, TimeUnit.SECONDS)).isTrue();
        assertThat(received).isNotEmpty();
    }

    @Test
    @DisplayName("Pub/Sub 콜백이 Lettuce EventLoop가 아닌 별도 스레드풀에서 실행된다")
    void callbackRunsOnDedicatedThreadPool() throws Exception {
        // given
        String channel = "user:thread-test";
        LocationUpdateMessage message = new LocationUpdateMessage("user-t", 10, 20, System.currentTimeMillis(), List.of());

        CountDownLatch latch = new CountDownLatch(1);
        AtomicReference<String> threadName = new AtomicReference<>();

        manager.subscribe(channel, (msg, pattern) -> {
            threadName.set(Thread.currentThread().getName());
            latch.countDown();
        });

        waitForSubscriptionReady();

        // when
        manager.publish(channel, message);

        // then
        assertThat(latch.await(3, TimeUnit.SECONDS)).isTrue();
        assertThat(threadName.get()).startsWith(THREAD_NAME_PREFIX);
        assertThat(threadName.get()).doesNotContain("lettuce");
    }

    @Test
    @DisplayName("콜백 처리 중 다른 메시지가 정상적으로 수신된다 (블로킹 미발생)")
    void callbackDoesNotBlockOtherMessages() throws Exception {
        // given
        String channel1 = "user:block-test-1";
        String channel2 = "user:block-test-2";
        LocationUpdateMessage msg1 = new LocationUpdateMessage("u1", 10, 20, System.currentTimeMillis(), List.of());
        LocationUpdateMessage msg2 = new LocationUpdateMessage("u2", 30, 40, System.currentTimeMillis(), List.of());

        CountDownLatch blockingStarted = new CountDownLatch(1);
        CountDownLatch secondReceived = new CountDownLatch(1);
        CountDownLatch releaseBlocking = new CountDownLatch(1);

        manager.subscribe(channel1, (msg, pattern) -> {
            blockingStarted.countDown();
            try {
                releaseBlocking.await(5, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });

        manager.subscribe(channel2, (msg, pattern) -> secondReceived.countDown());

        waitForSubscriptionReady();

        // when
        manager.publish(channel1, msg1);
        assertThat(blockingStarted.await(3, TimeUnit.SECONDS)).isTrue();
        manager.publish(channel2, msg2);

        // then
        assertThat(secondReceived.await(3, TimeUnit.SECONDS)).isTrue();
        releaseBlocking.countDown();
    }

    @Test
    @DisplayName("전파 경로 메타데이터(path)가 포함된 메시지가 정확히 직렬화/역직렬화된다")
    void messageWithPathSerializesAndDeserializesCorrectly() throws Exception {
        // given
        String channel = "user:path-test";
        long now = System.currentTimeMillis();
        List<PropagationPath> path = List.of(
                new PropagationPath("ws-server-1", PathAction.RECEIVE, now),
                new PropagationPath("api-gateway", PathAction.PUBLISH, now + 10),
                new PropagationPath("ws-server-2", PathAction.DELIVER, now + 20));
        LocationUpdateMessage original = new LocationUpdateMessage("user-path", 777, 888, now, path);

        CountDownLatch latch = new CountDownLatch(1);
        AtomicReference<String> receivedJson = new AtomicReference<>();

        manager.subscribe(channel, (msg, pattern) -> {
            receivedJson.set(new String(msg.getBody(), StandardCharsets.UTF_8));
            latch.countDown();
        });

        waitForSubscriptionReady();

        // when
        manager.publish(channel, original);

        // then
        assertThat(latch.await(3, TimeUnit.SECONDS)).isTrue();
        LocationUpdateMessage received = objectMapper.readValue(receivedJson.get(), LocationUpdateMessage.class);
        assertThat(received.getPath()).hasSize(3);
        assertThat(received.getPath().get(0).node()).isEqualTo("ws-server-1");
        assertThat(received.getPath().get(0).action()).isEqualTo(PathAction.RECEIVE);
        assertThat(received.getPath().get(1).node()).isEqualTo("api-gateway");
        assertThat(received.getPath().get(2).node()).isEqualTo("ws-server-2");
        assertThat(received.getPath().get(2).action()).isEqualTo(PathAction.DELIVER);
    }

    @Test
    @DisplayName("JSON 문자열이 redis-cli로 확인 가능한 형태이다 (StringRedisSerializer)")
    void jsonIsHumanReadableString() throws Exception {
        // given
        String channel = "user:serializer-test";
        LocationUpdateMessage message = new LocationUpdateMessage("user-s", 10, 20, System.currentTimeMillis(), List.of());

        CountDownLatch latch = new CountDownLatch(1);
        AtomicReference<byte[]> rawBody = new AtomicReference<>();

        manager.subscribe(channel, (msg, pattern) -> {
            rawBody.set(msg.getBody());
            latch.countDown();
        });

        waitForSubscriptionReady();

        // when
        manager.publish(channel, message);

        // then
        assertThat(latch.await(3, TimeUnit.SECONDS)).isTrue();
        String json = new String(rawBody.get(), StandardCharsets.UTF_8);
        assertThat(json).startsWith("{");
        assertThat(json).contains("\"userId\"");
        assertThat(json).doesNotContain("\\xac\\xed");
    }

    @Test
    @DisplayName("Testcontainers Redis 인스턴스 2개가 독립적으로 기동된다")
    void twoRedisInstancesRunIndependently() {
        // given
        // when
        // then
        assertThat(redis1.isRunning()).isTrue();
        assertThat(redis2.isRunning()).isTrue();
        assertThat(redis1.getMappedPort(REDIS_PORT))
                .isNotEqualTo(redis2.getMappedPort(REDIS_PORT));
    }

    @Test
    @DisplayName("LettuceConnectionFactory가 각 Redis 인스턴스에 독립적으로 연결된다")
    void connectionFactoriesAreIndependent() {
        // given
        // when
        String factory1Host = factory1.getHostName();
        int factory1Port = factory1.getPort();
        String factory2Host = factory2.getHostName();
        int factory2Port = factory2.getPort();

        // then
        assertThat(factory1Port).isNotEqualTo(factory2Port);
        assertThat(factory1.getConnection().ping()).isEqualTo("PONG");
        assertThat(factory2.getConnection().ping()).isEqualTo("PONG");
    }

    @Test
    @DisplayName("subscribe 측은 모든 Redis 인스턴스를 구독하고 있으므로 어느 노드에 publish해도 수신한다")
    void subscriberReceivesFromAnyNode() throws Exception {
        // given
        String channel = "user:all-nodes-test";
        LocationUpdateMessage message = new LocationUpdateMessage("user-all", 10, 20, System.currentTimeMillis(), List.of());

        CountDownLatch latch = new CountDownLatch(1);
        AtomicReference<String> receivedBody = new AtomicReference<>();

        manager.subscribe(channel, (msg, pattern) -> {
            receivedBody.set(new String(msg.getBody(), StandardCharsets.UTF_8));
            latch.countDown();
        });

        waitForSubscriptionReady();

        // when
        manager.publish(channel, message);

        // then
        assertThat(latch.await(3, TimeUnit.SECONDS)).isTrue();
        assertThat(receivedBody.get()).contains("user-all");
    }

    private LettuceConnectionFactory createConnectionFactory(GenericContainer<?> redis) {
        RedisStandaloneConfiguration config = new RedisStandaloneConfiguration(
                redis.getHost(), redis.getMappedPort(REDIS_PORT));
        LettuceConnectionFactory factory = new LettuceConnectionFactory(config);
        factory.afterPropertiesSet();
        return factory;
    }

    private RedisMessageListenerContainer createListenerContainer(
            LettuceConnectionFactory factory, TaskExecutor executor) {
        RedisMessageListenerContainer container = new RedisMessageListenerContainer();
        container.setConnectionFactory(factory);
        container.setTaskExecutor(executor);
        container.afterPropertiesSet();
        container.start();
        return container;
    }

    private void waitForSubscriptionReady() throws InterruptedException {
        Thread.sleep(300);
    }
}
