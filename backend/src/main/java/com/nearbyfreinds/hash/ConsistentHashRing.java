package com.nearbyfreinds.hash;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.NavigableMap;
import java.util.Set;
import java.util.TreeMap;

import com.google.common.hash.Hashing;

/**
 * Consistent Hash Ring으로 키를 물리 노드에 매핑한다.
 * 해시 공간은 0 ~ 2^32-1 (unsigned 32bit)이며, 물리 노드당 가상 노드를 배치하여 균등 분포를 보장한다.
 *
 * @param <T> 물리 노드 타입
 */
public class ConsistentHashRing<T> {

    private static final int DEFAULT_VIRTUAL_NODE_COUNT = 3;
    private static final long HASH_SPACE_MAX = 0xFFFFFFFFL;

    private final int virtualNodeCount;
    private final TreeMap<Long, T> ring = new TreeMap<>();
    private final Set<T> physicalNodes = new HashSet<>();

    public ConsistentHashRing() {
        this(DEFAULT_VIRTUAL_NODE_COUNT);
    }

    public ConsistentHashRing(int virtualNodeCount) {
        this.virtualNodeCount = virtualNodeCount;
    }

    /**
     * 물리 노드를 Ring에 추가하고, 가상 노드를 배치한다.
     *
     * @param node 추가할 물리 노드
     */
    public synchronized void addNode(T node) {
        physicalNodes.add(node);
        for (int i = 0; i < virtualNodeCount; i++) {
            long hash = hash(node + "#" + i);
            ring.put(hash, node);
        }
    }

    /**
     * 물리 노드를 Ring에서 제거하고, 해당 가상 노드를 모두 제거한다.
     *
     * @param node 제거할 물리 노드
     */
    public synchronized void removeNode(T node) {
        physicalNodes.remove(node);
        for (int i = 0; i < virtualNodeCount; i++) {
            long hash = hash(node + "#" + i);
            ring.remove(hash);
        }
    }

    /**
     * 키를 해싱하여 시계 방향으로 가장 가까운 물리 노드를 반환한다.
     *
     * @param key 매핑할 키
     * @return 매핑된 물리 노드, Ring이 비어있으면 null
     */
    public T getNode(String key) {
        NavigableMap<Long, T> snapshot = getRingSnapshot();
        if (snapshot.isEmpty()) {
            return null;
        }
        long hash = hash(key);
        var entry = snapshot.ceilingEntry(hash);
        if (entry == null) {
            entry = snapshot.firstEntry();
        }
        return entry.getValue();
    }

    /**
     * 등록된 모든 물리 노드 목록을 반환한다.
     *
     * @return 물리 노드의 불변 리스트
     */
    public List<T> getAllNodes() {
        synchronized (this) {
            return List.copyOf(physicalNodes);
        }
    }

    /**
     * Ring의 전체 가상 노드 위치 정보를 반환한다. (시각화용)
     *
     * @return 가상 노드 정보 목록 (position 기준 정렬)
     */
    public List<VirtualNodeInfo<T>> getRingState() {
        NavigableMap<Long, T> snapshot = getRingSnapshot();
        List<VirtualNodeInfo<T>> state = new ArrayList<>();
        for (T node : getAllNodes()) {
            for (int i = 0; i < virtualNodeCount; i++) {
                long hash = hash(node + "#" + i);
                if (snapshot.containsKey(hash)) {
                    state.add(new VirtualNodeInfo<>(hash, node, i));
                }
            }
        }
        state.sort((a, b) -> Long.compare(a.position(), b.position()));
        return Collections.unmodifiableList(state);
    }

    /**
     * 특정 키가 어떤 노드에 매핑되는지와 해시 위치를 반환한다. (시각화용)
     *
     * @param key 조회할 키
     * @return 매핑된 노드와 해시 위치, Ring이 비어있으면 null
     */
    public NodeMapping<T> getNodeForKey(String key) {
        NavigableMap<Long, T> snapshot = getRingSnapshot();
        if (snapshot.isEmpty()) {
            return null;
        }
        long hash = hash(key);
        var entry = snapshot.ceilingEntry(hash);
        if (entry == null) {
            entry = snapshot.firstEntry();
        }
        return new NodeMapping<>(entry.getValue(), hash);
    }

    /**
     * Ring에 등록된 가상 노드 수를 반환한다.
     *
     * @return 가상 노드 수
     */
    public int getVirtualNodeCount() {
        synchronized (this) {
            return ring.size();
        }
    }

    /**
     * MurmurHash3 128bit의 상위 32bit를 unsigned long으로 반환한다.
     */
    static long hash(String key) {
        long full = Hashing.murmur3_128(0).hashBytes(key.getBytes()).asLong();
        return (full >>> 32) & HASH_SPACE_MAX;
    }

    private NavigableMap<Long, T> getRingSnapshot() {
        synchronized (this) {
            return new TreeMap<>(ring);
        }
    }
}
