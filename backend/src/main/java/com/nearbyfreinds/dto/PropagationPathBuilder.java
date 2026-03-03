package com.nearbyfreinds.dto;

public final class PropagationPathBuilder {

    private PropagationPathBuilder() {
    }

    /**
     * Step 1: WS서버가 클라이언트로부터 위치 업데이트를 수신했을 때의 경로 엔트리를 생성한다.
     *
     * @param wsInstanceId WS서버 인스턴스 ID (예: "ws-1")
     * @return PropagationPath
     */
    public static PropagationPath receive(String wsInstanceId) {
        return new PropagationPath(wsInstanceId, PathAction.RECEIVE, System.currentTimeMillis());
    }

    /**
     * Step 2: Hash Ring으로 결정된 Redis 노드에 메시지를 publish할 때의 경로 엔트리를 생성한다.
     *
     * @param redisNodeId Redis 노드 ID (예: "redis-pubsub-1")
     * @return PropagationPath
     */
    public static PropagationPath publish(String redisNodeId) {
        return new PropagationPath(redisNodeId, PathAction.PUBLISH, System.currentTimeMillis());
    }

    /**
     * Step 3: 다른 WS서버가 subscribe를 통해 메시지를 수신했을 때의 경로 엔트리를 생성한다.
     *
     * @param redisNodeId 메시지를 수신한 Redis 노드 ID (예: "redis-pubsub-1")
     * @return PropagationPath
     */
    public static PropagationPath subscribeReceive(String redisNodeId) {
        return new PropagationPath(redisNodeId, PathAction.SUBSCRIBE_RECEIVE, System.currentTimeMillis());
    }

    /**
     * Step 4: WS서버가 최종적으로 클라이언트에 메시지를 전달할 때의 경로 엔트리를 생성한다.
     *
     * @param wsInstanceId WS서버 인스턴스 ID (예: "ws-1")
     * @return PropagationPath
     */
    public static PropagationPath deliver(String wsInstanceId) {
        return new PropagationPath(wsInstanceId, PathAction.DELIVER, System.currentTimeMillis());
    }
}
