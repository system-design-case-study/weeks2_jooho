package com.nearbyfreinds.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import java.net.URI;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketHttpHeaders;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.client.standard.StandardWebSocketClient;
import org.springframework.web.socket.handler.TextWebSocketHandler;
import org.testcontainers.containers.CassandraContainer;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nearbyfreinds.domain.cassandra.LocationHistory;
import com.nearbyfreinds.dto.Location;
import com.nearbyfreinds.repository.cassandra.LocationHistoryRepository;
import com.nearbyfreinds.repository.jpa.FriendshipRepository;
import com.nearbyfreinds.service.LocationCacheService;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
class LocationPipelineIntegrationTest {

    private static final int REDIS_PORT = 6379;
    private static final String LOCATION_CACHE_KEY_PREFIX = "location:user:";

    @Container
    static final GenericContainer<?> redisCache = new GenericContainer<>(DockerImageName.parse("redis:7-alpine"))
            .withExposedPorts(REDIS_PORT);

    @Container
    static final GenericContainer<?> redisPubSub1 = new GenericContainer<>(DockerImageName.parse("redis:7-alpine"))
            .withExposedPorts(REDIS_PORT);

    @Container
    static final GenericContainer<?> redisPubSub2 = new GenericContainer<>(DockerImageName.parse("redis:7-alpine"))
            .withExposedPorts(REDIS_PORT);

    @Container
    static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>(DockerImageName.parse("postgres:16-alpine"))
            .withDatabaseName("nearbyfreinds")
            .withUsername("nearbyfreinds")
            .withPassword("nearbyfreinds")
            .withInitScript("schema.sql");

    @SuppressWarnings("resource")
    @Container
    static final CassandraContainer<?> cassandra = new CassandraContainer<>(DockerImageName.parse("cassandra:4.1"))
            .withInitScript("cassandra-init.cql");

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        registry.add("app.redis-cache-host", redisCache::getHost);
        registry.add("app.redis-cache-port", () -> redisCache.getMappedPort(REDIS_PORT));

        String pubsubNode1 = redisPubSub1.getHost() + ":" + redisPubSub1.getMappedPort(REDIS_PORT);
        String pubsubNode2 = redisPubSub2.getHost() + ":" + redisPubSub2.getMappedPort(REDIS_PORT);
        registry.add("app.redis-pubsub-nodes", () -> pubsubNode1 + "," + pubsubNode2);

        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);

        registry.add("spring.cassandra.contact-points", cassandra::getHost);
        registry.add("spring.cassandra.port", () -> cassandra.getMappedPort(9042));
        registry.add("spring.cassandra.local-datacenter", () -> "datacenter1");
        registry.add("spring.cassandra.keyspace-name", () -> "nearby_friends");

        registry.add("spring.jpa.hibernate.ddl-auto", () -> "validate");
        registry.add("app.instance-id", () -> "ws-test-1");
        registry.add("app.search-radius", () -> "200");
    }

    @LocalServerPort
    private int port;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private FriendshipRepository friendshipRepository;

    @Autowired
    private LocationHistoryRepository locationHistoryRepository;

    @Autowired
    private LocationCacheService locationCacheService;

    @Autowired
    @Qualifier("cacheRedisTemplate")
    private StringRedisTemplate cacheRedisTemplate;

    private WebSocketSession sessionA;
    private WebSocketSession sessionB;
    private final CopyOnWriteArrayList<JsonNode> messagesA = new CopyOnWriteArrayList<>();
    private final CopyOnWriteArrayList<JsonNode> messagesB = new CopyOnWriteArrayList<>();
    private CountDownLatch initLatchA;
    private CountDownLatch initLatchB;

    @BeforeEach
    void setUp() {
        messagesA.clear();
        messagesB.clear();
        initLatchA = new CountDownLatch(1);
        initLatchB = new CountDownLatch(1);
    }

    @AfterEach
    void tearDown() throws Exception {
        if (sessionA != null && sessionA.isOpen()) {
            sessionA.close();
        }
        if (sessionB != null && sessionB.isOpen()) {
            sessionB.close();
        }
        friendshipRepository.deleteAll();
    }

    @Test
    @DisplayName("WebSocket 연결 성공 + INIT 메시지 수신")
    void webSocketConnectionAndInitMessage() throws Exception {
        // given
        // when
        sessionA = connectClient(messagesA, initLatchA);

        // then
        assertThat(initLatchA.await(5, TimeUnit.SECONDS)).isTrue();
        JsonNode initMsg = findMessage(messagesA, "INIT");
        assertThat(initMsg).isNotNull();
        assertThat(initMsg.get("userId").asText()).startsWith("user-");
        assertThat(initMsg.has("x")).isTrue();
        assertThat(initMsg.has("y")).isTrue();
        assertThat(initMsg.get("searchRadius").asInt()).isEqualTo(200);
    }

    @Test
    @DisplayName("LOCATION_UPDATE 전송 후 Cassandra에 이력 레코드 존재")
    void locationUpdateSavedToCassandra() throws Exception {
        // given
        sessionA = connectClient(messagesA, initLatchA);
        assertThat(initLatchA.await(5, TimeUnit.SECONDS)).isTrue();
        String userIdA = findMessage(messagesA, "INIT").get("userId").asText();

        // when
        sendLocationUpdate(sessionA, 500, 300);

        // then
        await().atMost(Duration.ofSeconds(3)).untilAsserted(() -> {
            List<LocationHistory> history = locationHistoryRepository.findByUserId(userIdA, 10);
            assertThat(history).isNotEmpty();
            LocationHistory latest = history.get(0);
            assertThat(latest.getX()).isEqualTo(500);
            assertThat(latest.getY()).isEqualTo(300);
            assertThat(latest.getWsServer()).isEqualTo("ws-test-1");
        });
    }

    @Test
    @DisplayName("LOCATION_UPDATE 전송 후 Redis Cache에 위치 데이터 존재 + TTL 설정")
    void locationUpdateSavedToRedisCache() throws Exception {
        // given
        sessionA = connectClient(messagesA, initLatchA);
        assertThat(initLatchA.await(5, TimeUnit.SECONDS)).isTrue();
        String userIdA = findMessage(messagesA, "INIT").get("userId").asText();

        // when
        sendLocationUpdate(sessionA, 500, 300);

        // then
        await().atMost(Duration.ofSeconds(2)).untilAsserted(() -> {
            Optional<Location> cached = locationCacheService.getLocation(userIdA);
            assertThat(cached).isPresent();
            assertThat(cached.get().x()).isEqualTo(500);
            assertThat(cached.get().y()).isEqualTo(300);
        });

        Long ttl = cacheRedisTemplate.getExpire(LOCATION_CACHE_KEY_PREFIX + userIdA, TimeUnit.SECONDS);
        assertThat(ttl).isNotNull();
        assertThat(ttl).isGreaterThan(0).isLessThanOrEqualTo(600);
    }

    @Test
    @DisplayName("Pub/Sub를 통한 친구 간 위치 전파 성공")
    void pubSubFriendLocationPropagation() throws Exception {
        // given
        sessionA = connectClient(messagesA, initLatchA);
        assertThat(initLatchA.await(5, TimeUnit.SECONDS)).isTrue();
        String userIdA = findMessage(messagesA, "INIT").get("userId").asText();

        sessionB = connectClient(messagesB, initLatchB);
        assertThat(initLatchB.await(5, TimeUnit.SECONDS)).isTrue();

        setupFriendshipViaWebSocket(sessionB, userIdA);
        waitForSubscriptionReady();

        // when
        sendLocationUpdate(sessionA, 500, 300);

        // then
        await().atMost(Duration.ofSeconds(5)).untilAsserted(() -> {
            JsonNode friendLocation = findMessage(messagesB, "FRIEND_LOCATION");
            assertThat(friendLocation).isNotNull();
            assertThat(friendLocation.get("friendId").asText()).isEqualTo(userIdA);
            assertThat(friendLocation.get("x").asInt()).isEqualTo(500);
            assertThat(friendLocation.get("y").asInt()).isEqualTo(300);
        });
    }

    @Test
    @DisplayName("반경 내 친구에게 FRIEND_LOCATION 메시지 전달 확인 (inRange: true)")
    void friendLocationInRange() throws Exception {
        // given
        sessionA = connectClient(messagesA, initLatchA);
        assertThat(initLatchA.await(5, TimeUnit.SECONDS)).isTrue();
        String userIdA = findMessage(messagesA, "INIT").get("userId").asText();

        sessionB = connectClient(messagesB, initLatchB);
        assertThat(initLatchB.await(5, TimeUnit.SECONDS)).isTrue();

        sendLocationUpdate(sessionB, 200, 200);
        setupFriendshipViaWebSocket(sessionB, userIdA);
        waitForSubscriptionReady();

        // when
        sendLocationUpdate(sessionA, 100, 100);

        // then
        await().atMost(Duration.ofSeconds(5)).untilAsserted(() -> {
            JsonNode friendLocation = findMessage(messagesB, "FRIEND_LOCATION");
            assertThat(friendLocation).isNotNull();
            assertThat(friendLocation.get("inRange").asBoolean()).isTrue();
            double distance = friendLocation.get("distance").asDouble();
            assertThat(distance).isCloseTo(141.42, org.assertj.core.data.Offset.offset(1.0));
        });
    }

    @Test
    @DisplayName("반경 밖 친구에게는 inRange: false 확인")
    void friendLocationOutOfRange() throws Exception {
        // given
        sessionA = connectClient(messagesA, initLatchA);
        assertThat(initLatchA.await(5, TimeUnit.SECONDS)).isTrue();
        String userIdA = findMessage(messagesA, "INIT").get("userId").asText();

        sessionB = connectClient(messagesB, initLatchB);
        assertThat(initLatchB.await(5, TimeUnit.SECONDS)).isTrue();

        sendLocationUpdate(sessionB, 200, 200);
        setupFriendshipViaWebSocket(sessionB, userIdA);
        waitForSubscriptionReady();

        // when
        sendLocationUpdate(sessionA, 900, 900);

        // then
        await().atMost(Duration.ofSeconds(5)).untilAsserted(() -> {
            JsonNode friendLocation = findMessage(messagesB, "FRIEND_LOCATION");
            assertThat(friendLocation).isNotNull();
            assertThat(friendLocation.get("inRange").asBoolean()).isFalse();
            double distance = friendLocation.get("distance").asDouble();
            assertThat(distance).isGreaterThan(200.0);
        });
    }

    @Test
    @DisplayName("전파 경로 path 배열에 4단계 메타데이터 포함 + timestamp 단조 증가")
    void propagationPathMetadata() throws Exception {
        // given
        sessionA = connectClient(messagesA, initLatchA);
        assertThat(initLatchA.await(5, TimeUnit.SECONDS)).isTrue();
        String userIdA = findMessage(messagesA, "INIT").get("userId").asText();

        sessionB = connectClient(messagesB, initLatchB);
        assertThat(initLatchB.await(5, TimeUnit.SECONDS)).isTrue();

        sendLocationUpdate(sessionB, 200, 200);
        setupFriendshipViaWebSocket(sessionB, userIdA);
        waitForSubscriptionReady();

        // when
        sendLocationUpdate(sessionA, 100, 100);

        // then
        await().atMost(Duration.ofSeconds(5)).untilAsserted(() -> {
            JsonNode friendLocation = findMessage(messagesB, "FRIEND_LOCATION");
            assertThat(friendLocation).isNotNull();
            assertThat(friendLocation.has("path")).isTrue();

            JsonNode path = friendLocation.get("path");
            assertThat(path.isArray()).isTrue();
            assertThat(path.size()).isGreaterThanOrEqualTo(4);

            assertThat(path.get(0).get("action").asText()).isEqualTo("receive");
            assertThat(path.get(0).get("node").asText()).startsWith("ws-");

            assertThat(path.get(1).get("action").asText()).isEqualTo("publish");

            assertThat(path.get(2).get("action").asText()).isEqualTo("subscribe_receive");

            assertThat(path.get(3).get("action").asText()).isEqualTo("deliver");
            assertThat(path.get(3).get("node").asText()).startsWith("ws-");

            long prevTs = 0;
            for (int i = 0; i < path.size(); i++) {
                long ts = path.get(i).get("ts").asLong();
                assertThat(ts).isGreaterThanOrEqualTo(prevTs);
                prevTs = ts;
            }
        });
    }

    @Test
    @DisplayName("전체 파이프라인 통합: 연결 -> 위치 업데이트 -> 3개 저장소 갱신 -> Pub/Sub 전파 -> 거리 필터링")
    void fullPipelineEndToEnd() throws Exception {
        // given
        sessionA = connectClient(messagesA, initLatchA);
        assertThat(initLatchA.await(5, TimeUnit.SECONDS)).isTrue();
        String userIdA = findMessage(messagesA, "INIT").get("userId").asText();

        sessionB = connectClient(messagesB, initLatchB);
        assertThat(initLatchB.await(5, TimeUnit.SECONDS)).isTrue();

        sendLocationUpdate(sessionB, 200, 200);
        setupFriendshipViaWebSocket(sessionB, userIdA);
        waitForSubscriptionReady();

        // when
        sendLocationUpdate(sessionA, 100, 100);

        // then
        await().atMost(Duration.ofSeconds(5)).untilAsserted(() -> {
            List<LocationHistory> history = locationHistoryRepository.findByUserId(userIdA, 10);
            assertThat(history).isNotEmpty();
            assertThat(history.get(0).getX()).isEqualTo(100);
            assertThat(history.get(0).getY()).isEqualTo(100);
        });

        Optional<Location> cached = locationCacheService.getLocation(userIdA);
        assertThat(cached).isPresent();
        assertThat(cached.get().x()).isEqualTo(100);
        assertThat(cached.get().y()).isEqualTo(100);

        Long ttl = cacheRedisTemplate.getExpire(LOCATION_CACHE_KEY_PREFIX + userIdA, TimeUnit.SECONDS);
        assertThat(ttl).isNotNull().isGreaterThan(0);

        await().atMost(Duration.ofSeconds(5)).untilAsserted(() -> {
            JsonNode friendLocation = findMessage(messagesB, "FRIEND_LOCATION");
            assertThat(friendLocation).isNotNull();
            assertThat(friendLocation.get("friendId").asText()).isEqualTo(userIdA);
            assertThat(friendLocation.get("x").asInt()).isEqualTo(100);
            assertThat(friendLocation.get("y").asInt()).isEqualTo(100);
            assertThat(friendLocation.get("inRange").asBoolean()).isTrue();

            JsonNode path = friendLocation.get("path");
            assertThat(path).isNotNull();
            assertThat(path.size()).isGreaterThanOrEqualTo(4);
        });
    }

    private WebSocketSession connectClient(CopyOnWriteArrayList<JsonNode> messages,
                                           CountDownLatch initLatch) throws Exception {
        StandardWebSocketClient client = new StandardWebSocketClient();
        URI uri = URI.create("ws://localhost:" + port + "/ws/location");

        return client.execute(new TextWebSocketHandler() {
            @Override
            protected void handleTextMessage(WebSocketSession session, TextMessage message) {
                try {
                    JsonNode json = objectMapper.readTree(message.getPayload());
                    messages.add(json);
                    if ("INIT".equals(json.path("type").asText())) {
                        initLatch.countDown();
                    }
                } catch (Exception e) {
                    throw new RuntimeException("메시지 파싱 실패", e);
                }
            }
        }, new WebSocketHttpHeaders(), uri).get(5, TimeUnit.SECONDS);
    }

    private JsonNode findMessage(CopyOnWriteArrayList<JsonNode> messages, String type) {
        for (int i = messages.size() - 1; i >= 0; i--) {
            JsonNode msg = messages.get(i);
            if (type.equals(msg.path("type").asText())) {
                return msg;
            }
        }
        return null;
    }

    private void setupFriendshipViaWebSocket(WebSocketSession session, String friendId) throws Exception {
        String addFriendJson = objectMapper.writeValueAsString(
                new java.util.LinkedHashMap<>() {{
                    put("type", "ADD_FRIEND");
                    put("friendId", friendId);
                }});
        session.sendMessage(new TextMessage(addFriendJson));
        Thread.sleep(500);
    }

    private void sendLocationUpdate(WebSocketSession session, int x, int y) throws Exception {
        String updateJson = objectMapper.writeValueAsString(
                new java.util.LinkedHashMap<>() {{
                    put("type", "LOCATION_UPDATE");
                    put("x", x);
                    put("y", y);
                }});
        session.sendMessage(new TextMessage(updateJson));
        Thread.sleep(200);
    }

    private void waitForSubscriptionReady() throws InterruptedException {
        Thread.sleep(500);
    }
}
