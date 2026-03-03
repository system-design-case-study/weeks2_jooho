package com.nearbyfreinds.hash;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ConsistentHashRingTest {

    private static final String NODE_1 = "redis-pubsub-1:6380";
    private static final String NODE_2 = "redis-pubsub-2:6381";
    private static final String NODE_3 = "redis-pubsub-3:6382";
    private static final int KEY_COUNT = 1000;

    private ConsistentHashRing<String> ring;

    @BeforeEach
    void setUp() {
        ring = new ConsistentHashRing<>();
    }

    @Test
    @DisplayName("노드 2개 추가 시 가상 노드 300개가 등록된다")
    void addTwoNodesCreates300VirtualNodes() {
        // given
        ring.addNode(NODE_1);
        ring.addNode(NODE_2);

        // then
        assertThat(ring.getVirtualNodeCount()).isEqualTo(300);
        assertThat(ring.getAllNodes()).containsExactlyInAnyOrder(NODE_1, NODE_2);
    }

    @Test
    @DisplayName("빈 Ring에서 getNode는 null을 반환한다")
    void emptyRingReturnsNull() {
        // when & then
        assertThat(ring.getNode("any-key")).isNull();
        assertThat(ring.getNodeForKey("any-key")).isNull();
    }

    // ── 1. 균등 분포 검증 ──

    @Test
    @DisplayName("1000개 랜덤 키에 대해 2개 노드의 분포 편차가 20% 이내이다")
    void keysAreDistributedEvenly() {
        // given
        ring.addNode(NODE_1);
        ring.addNode(NODE_2);

        // when
        Map<String, Integer> counts = new HashMap<>();
        counts.put(NODE_1, 0);
        counts.put(NODE_2, 0);

        for (int i = 0; i < KEY_COUNT; i++) {
            String key = "user:" + UUID.randomUUID();
            String node = ring.getNode(key);
            counts.merge(node, 1, Integer::sum);
        }

        // then
        assertThat(counts.get(NODE_1)).isBetween(400, 600);
        assertThat(counts.get(NODE_2)).isBetween(400, 600);
    }

    // ── 2. 노드 추가/제거 시 최소 재배치 검증 ──

    @Test
    @DisplayName("노드 추가 시 이동 키 수가 전체의 50% 미만이다")
    void nodeAdditionCausesMinimalRedistribution() {
        // given
        ring.addNode(NODE_1);
        ring.addNode(NODE_2);

        Map<String, String> beforeMapping = new HashMap<>();
        for (int i = 0; i < KEY_COUNT; i++) {
            String key = "user:" + i;
            beforeMapping.put(key, ring.getNode(key));
        }

        // when
        ring.addNode(NODE_3);

        int movedCount = 0;
        for (int i = 0; i < KEY_COUNT; i++) {
            String key = "user:" + i;
            if (!beforeMapping.get(key).equals(ring.getNode(key))) {
                movedCount++;
            }
        }

        // then
        double movedRatio = movedCount / (double) KEY_COUNT;
        assertThat(movedRatio).isLessThan(0.5);

        double expectedRatio = 1.0 / 3.0;
        assertThat(movedRatio).isBetween(expectedRatio * 0.5, expectedRatio * 1.5);
    }

    @Test
    @DisplayName("노드 제거 시 이동 키 수가 전체의 50% 미만이다")
    void nodeRemovalCausesMinimalRedistribution() {
        // given
        ring.addNode(NODE_1);
        ring.addNode(NODE_2);
        ring.addNode(NODE_3);

        Map<String, String> beforeMapping = new HashMap<>();
        for (int i = 0; i < KEY_COUNT; i++) {
            String key = "user:" + i;
            beforeMapping.put(key, ring.getNode(key));
        }

        // when
        ring.removeNode(NODE_3);

        int movedCount = 0;
        for (int i = 0; i < KEY_COUNT; i++) {
            String key = "user:" + i;
            if (!beforeMapping.get(key).equals(ring.getNode(key))) {
                movedCount++;
            }
        }

        // then
        double movedRatio = movedCount / (double) KEY_COUNT;
        assertThat(movedRatio).isLessThan(0.5);
    }

    @Test
    @DisplayName("노드 제거 후 getNode가 남은 노드만 반환한다")
    void removeNodeReturnsRemainingNodes() {
        // given
        ring.addNode(NODE_1);
        ring.addNode(NODE_2);

        // when
        ring.removeNode(NODE_1);

        // then
        assertThat(ring.getVirtualNodeCount()).isEqualTo(150);
        assertThat(ring.getAllNodes()).containsExactly(NODE_2);

        for (int i = 0; i < 100; i++) {
            String key = "user:" + i;
            assertThat(ring.getNode(key)).isEqualTo(NODE_2);
        }
    }

    // ── 3. Wrap-around 테스트 ──

    @Test
    @DisplayName("ceilingEntry가 null인 경우 firstEntry로 fallback한다")
    void wrapAroundFallsBackToFirstEntry() {
        // given
        ring.addNode(NODE_1);
        var state = ring.getRingState();
        long maxVirtualNodePosition = state.get(state.size() - 1).position();

        // when
        String keyBeyondMax = findKeyWithHashGreaterThan(maxVirtualNodePosition);
        String node = ring.getNode(keyBeyondMax);

        // then
        assertThat(node).isEqualTo(NODE_1);
        assertThat(ConsistentHashRing.hash(keyBeyondMax)).isGreaterThan(maxVirtualNodePosition);
    }

    @Test
    @DisplayName("Hash 공간의 경계값에서도 유효한 노드를 반환한다")
    void boundaryHashValuesReturnValidNode() {
        // given
        ring.addNode(NODE_1);
        ring.addNode(NODE_2);

        // when & then
        for (int i = 0; i < KEY_COUNT; i++) {
            String key = "boundary-test:" + i;
            assertThat(ring.getNode(key)).isIn(NODE_1, NODE_2);
        }
    }

    @Test
    @DisplayName("Ring의 마지막 가상 노드 이후 키가 첫 번째 가상 노드의 물리 노드에 매핑된다")
    void keyBeyondLastVirtualNodeMapsToFirstVirtualNode() {
        // given
        ring.addNode(NODE_1);
        ring.addNode(NODE_2);
        var state = ring.getRingState();
        long maxPosition = state.get(state.size() - 1).position();
        String expectedNode = state.get(0).physicalNode().toString();

        // when
        String keyBeyondMax = findKeyWithHashGreaterThan(maxPosition);
        String actualNode = ring.getNode(keyBeyondMax);

        // then
        assertThat(actualNode).isEqualTo(expectedNode);
    }

    // ── 4. 결정론적 매핑 보장 ──

    @Test
    @DisplayName("동일 키에 대해 1000회 반복 호출 시 항상 동일 노드를 반환한다")
    void sameKeyAlwaysReturnsSameNode() {
        // given
        ring.addNode(NODE_1);
        ring.addNode(NODE_2);
        String key = "user:42";

        // when
        String expected = ring.getNode(key);

        // then
        for (int i = 0; i < KEY_COUNT; i++) {
            assertThat(ring.getNode(key)).isEqualTo(expected);
        }
    }

    @Test
    @DisplayName("노드 추가 순서가 달라도 동일 키는 동일 노드에 매핑된다")
    void nodeOrderDoesNotAffectMapping() {
        // given
        ConsistentHashRing<String> ring1 = new ConsistentHashRing<>();
        ring1.addNode(NODE_1);
        ring1.addNode(NODE_2);
        ring1.addNode(NODE_3);

        ConsistentHashRing<String> ring2 = new ConsistentHashRing<>();
        ring2.addNode(NODE_3);
        ring2.addNode(NODE_1);
        ring2.addNode(NODE_2);

        // when & then
        for (int i = 0; i < KEY_COUNT; i++) {
            String key = "user:" + i;
            assertThat(ring1.getNode(key)).isEqualTo(ring2.getNode(key));
        }
    }

    // ── 5. 가상 노드 네이밍 검증 ──

    @Test
    @DisplayName("가상 노드는 '물리노드#인덱스' 형태로 생성된다")
    void virtualNodeNamingFollowsConvention() {
        // given
        ring.addNode("redis-pubsub-1");

        // when
        var state = ring.getRingState();

        // then
        assertThat(state).hasSize(150);
        for (var info : state) {
            assertThat(info.virtualIndex()).isBetween(0, 149);
            assertThat(info.physicalNode()).isEqualTo("redis-pubsub-1");
        }
    }

    @Test
    @DisplayName("가상 노드 해시는 '물리노드#인덱스' 문자열의 해시값과 일치한다")
    void virtualNodeHashMatchesExpectedPattern() {
        // given
        String physicalNode = "redis-pubsub-1";
        ring.addNode(physicalNode);

        // when
        var state = ring.getRingState();

        // then
        for (var info : state) {
            String virtualNodeKey = physicalNode + "#" + info.virtualIndex();
            long expectedHash = ConsistentHashRing.hash(virtualNodeKey);
            assertThat(info.position()).isEqualTo(expectedHash);
        }
    }

    @Test
    @DisplayName("getRingState는 가상 노드 위치를 position 기준 정렬하여 반환한다")
    void getRingStateReturnsSortedVirtualNodes() {
        // given
        ring.addNode(NODE_1);
        ring.addNode(NODE_2);

        // when
        var state = ring.getRingState();

        // then
        assertThat(state).hasSize(300);
        for (int i = 1; i < state.size(); i++) {
            assertThat(state.get(i).position()).isGreaterThanOrEqualTo(state.get(i - 1).position());
        }
    }

    @Test
    @DisplayName("getNodeForKey는 매핑된 노드와 해시 위치를 반환한다")
    void getNodeForKeyReturnsNodeAndHashPosition() {
        // given
        ring.addNode(NODE_1);
        ring.addNode(NODE_2);
        String key = "user:99";

        // when
        NodeMapping<String> mapping = ring.getNodeForKey(key);

        // then
        assertThat(mapping).isNotNull();
        assertThat(mapping.node()).isNotNull();
        assertThat(mapping.hashPosition()).isBetween(0L, 0xFFFFFFFFL);
        assertThat(mapping.node()).isEqualTo(ring.getNode(key));
    }

    private String findKeyWithHashGreaterThan(long threshold) {
        for (int i = 0; ; i++) {
            String candidate = "wrap-around-key:" + i;
            if (ConsistentHashRing.hash(candidate) > threshold) {
                return candidate;
            }
        }
    }
}
