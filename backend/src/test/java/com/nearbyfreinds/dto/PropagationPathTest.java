package com.nearbyfreinds.dto;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

class PropagationPathTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    @DisplayName("LocationUpdateMessage 생성 시 빈 path 배열로 시작한다")
    void newMessageHasEmptyPath() {
        // given
        LocationUpdateMessage message = new LocationUpdateMessage("user-1", 100, 200, System.currentTimeMillis());

        // then
        assertThat(message.getPath()).isNotNull();
        assertThat(message.getPath()).isEmpty();
    }

    @Test
    @DisplayName("addPath 호출 시 path 배열에 올바른 PropagationPath 객체가 추가된다")
    void addPathAppendsEntry() {
        // given
        LocationUpdateMessage message = new LocationUpdateMessage("user-1", 100, 200, System.currentTimeMillis());
        PropagationPath entry = PropagationPathBuilder.receive("ws-1");

        // when
        message.addPath(entry);

        // then
        assertThat(message.getPath()).hasSize(1);
        assertThat(message.getPath().get(0).node()).isEqualTo("ws-1");
        assertThat(message.getPath().get(0).action()).isEqualTo(PathAction.RECEIVE);
    }

    @Test
    @DisplayName("전체 4단계 경로 추가 후 path 배열 크기가 4이다")
    void fullFourStepPathHasSize4() {
        // given
        LocationUpdateMessage message = new LocationUpdateMessage("user-3", 200, 400, System.currentTimeMillis());

        // when
        message.addPath(PropagationPathBuilder.receive("ws-2"));
        message.addPath(PropagationPathBuilder.publish("redis-pubsub-1"));
        message.addPath(PropagationPathBuilder.subscribeReceive("redis-pubsub-1"));
        message.addPath(PropagationPathBuilder.deliver("ws-1"));

        // then
        assertThat(message.getPath()).hasSize(4);
    }

    @Test
    @DisplayName("각 PropagationPath의 node, action, ts 값이 올바르다")
    void eachStepHasCorrectValues() {
        // given
        LocationUpdateMessage message = new LocationUpdateMessage("user-3", 200, 400, System.currentTimeMillis());

        // when
        message.addPath(PropagationPathBuilder.receive("ws-2"));
        message.addPath(PropagationPathBuilder.publish("redis-pubsub-1"));
        message.addPath(PropagationPathBuilder.subscribeReceive("redis-pubsub-1"));
        message.addPath(PropagationPathBuilder.deliver("ws-1"));

        // then
        List<PropagationPath> path = message.getPath();

        assertThat(path.get(0).node()).isEqualTo("ws-2");
        assertThat(path.get(0).action()).isEqualTo(PathAction.RECEIVE);
        assertThat(path.get(0).ts()).isPositive();

        assertThat(path.get(1).node()).isEqualTo("redis-pubsub-1");
        assertThat(path.get(1).action()).isEqualTo(PathAction.PUBLISH);
        assertThat(path.get(1).ts()).isPositive();

        assertThat(path.get(2).node()).isEqualTo("redis-pubsub-1");
        assertThat(path.get(2).action()).isEqualTo(PathAction.SUBSCRIBE_RECEIVE);
        assertThat(path.get(2).ts()).isPositive();

        assertThat(path.get(3).node()).isEqualTo("ws-1");
        assertThat(path.get(3).action()).isEqualTo(PathAction.DELIVER);
        assertThat(path.get(3).ts()).isPositive();
    }

    @Test
    @DisplayName("JSON 직렬화/역직렬화 후 path 배열이 보존된다")
    void jsonSerializationPreservesPath() throws JsonProcessingException {
        // given
        LocationUpdateMessage message = new LocationUpdateMessage("user-3", 200, 400, 1234567890L);
        message.addPath(PropagationPathBuilder.receive("ws-2"));
        message.addPath(PropagationPathBuilder.publish("redis-pubsub-1"));

        // when
        String json = objectMapper.writeValueAsString(message);
        LocationUpdateMessage deserialized = objectMapper.readValue(json, LocationUpdateMessage.class);

        // then
        assertThat(deserialized.getUserId()).isEqualTo("user-3");
        assertThat(deserialized.getX()).isEqualTo(200);
        assertThat(deserialized.getY()).isEqualTo(400);
        assertThat(deserialized.getTimestamp()).isEqualTo(1234567890L);
        assertThat(deserialized.getPath()).hasSize(2);
        assertThat(deserialized.getPath().get(0).node()).isEqualTo("ws-2");
        assertThat(deserialized.getPath().get(0).action()).isEqualTo(PathAction.RECEIVE);
        assertThat(deserialized.getPath().get(1).node()).isEqualTo("redis-pubsub-1");
        assertThat(deserialized.getPath().get(1).action()).isEqualTo(PathAction.PUBLISH);
    }

    @Test
    @DisplayName("JSON 직렬화 시 action 값이 문자열로 표현된다")
    void jsonActionSerializesAsString() throws JsonProcessingException {
        // given
        PropagationPath entry = new PropagationPath("ws-1", PathAction.RECEIVE, 1234567890L);

        // when
        String json = objectMapper.writeValueAsString(entry);

        // then
        assertThat(json).contains("\"action\":\"receive\"");
    }

    @Test
    @DisplayName("ts 값이 단계별로 단조증가한다")
    void timestampsAreMonotonicallyIncreasing() throws InterruptedException {
        // given
        LocationUpdateMessage message = new LocationUpdateMessage("user-3", 200, 400, System.currentTimeMillis());

        // when
        message.addPath(PropagationPathBuilder.receive("ws-2"));
        Thread.sleep(1);
        message.addPath(PropagationPathBuilder.publish("redis-pubsub-1"));
        Thread.sleep(1);
        message.addPath(PropagationPathBuilder.subscribeReceive("redis-pubsub-1"));
        Thread.sleep(1);
        message.addPath(PropagationPathBuilder.deliver("ws-1"));

        // then
        List<PropagationPath> path = message.getPath();
        for (int i = 1; i < path.size(); i++) {
            assertThat(path.get(i).ts()).isGreaterThanOrEqualTo(path.get(i - 1).ts());
        }
    }

    @Test
    @DisplayName("INSTANCE_ID가 node 값에 올바르게 반영된다")
    void instanceIdReflectedInNode() {
        // given
        String instanceId = "ws-2";

        // when
        PropagationPath receiveEntry = PropagationPathBuilder.receive(instanceId);
        PropagationPath deliverEntry = PropagationPathBuilder.deliver(instanceId);

        // then
        assertThat(receiveEntry.node()).isEqualTo("ws-2");
        assertThat(deliverEntry.node()).isEqualTo("ws-2");
    }

    @Test
    @DisplayName("전체 4단계 JSON 직렬화/역직렬화가 올바르게 동작한다")
    void fullPathJsonRoundTrip() throws JsonProcessingException {
        // given
        LocationUpdateMessage message = new LocationUpdateMessage("user-3", 200, 400, 1234567890L);
        message.addPath(new PropagationPath("ws-2", PathAction.RECEIVE, 1234567890L));
        message.addPath(new PropagationPath("redis-pubsub-1", PathAction.PUBLISH, 1234567891L));
        message.addPath(new PropagationPath("redis-pubsub-1", PathAction.SUBSCRIBE_RECEIVE, 1234567892L));
        message.addPath(new PropagationPath("ws-1", PathAction.DELIVER, 1234567893L));

        // when
        String json = objectMapper.writeValueAsString(message);
        LocationUpdateMessage deserialized = objectMapper.readValue(json, LocationUpdateMessage.class);

        // then
        assertThat(deserialized.getPath()).hasSize(4);
        assertThat(deserialized.getPath().get(0).action()).isEqualTo(PathAction.RECEIVE);
        assertThat(deserialized.getPath().get(1).action()).isEqualTo(PathAction.PUBLISH);
        assertThat(deserialized.getPath().get(2).action()).isEqualTo(PathAction.SUBSCRIBE_RECEIVE);
        assertThat(deserialized.getPath().get(3).action()).isEqualTo(PathAction.DELIVER);
    }
}
