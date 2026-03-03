# P2-07: 전파 경로 메타데이터 생성 로직

## 메타정보
- Phase: 2
- 의존성: P2-02
- 예상 소요: 45분
- 난이도: 중

## 목표
> 위치 업데이트 메시지에 전파 경로(`path`) 배열을 포함하여, 메시지가 어떤 노드를 거쳐 전달되었는지 추적하는 메타데이터 생성 로직을 구현한다.

## 컨텍스트
> 전파 경로 추적은 시각화의 핵심이다. "사용자 A → WS-2 → Redis Pub/Sub 1 → WS-1 → 사용자 B"라는 전파 경로를 메시지 내 `path` 배열로 기록한다. 각 단계에서 노드 ID, 액션, 타임스탬프를 추가한다. 이는 횡단 관심사로 설계 초기부터 메시지 포맷에 반영해야 하며, 나중에 추가하면 모든 핸들러를 수정해야 한다. (docs/design.md "전파 경로 메타데이터" 섹션, docs/domain-insights.md "전파 경로 추적" 참조)

## 상세 요구사항
1. 내부 메시지 포맷 정의 (`LocationUpdateMessage` 클래스 또는 record):
   ```json
   {
     "userId": "user-3",
     "x": 200,
     "y": 400,
     "timestamp": 1234567890,
     "path": [
       {"node": "ws-2", "action": "receive", "ts": 1234567890},
       {"node": "redis-pubsub-1", "action": "publish", "ts": 1234567891}
     ]
   }
   ```
2. `PropagationPath` 값 객체:
   - `node` (String) — 노드 식별자 (예: `ws-1`, `redis-pubsub-1`)
   - `action` (String) — 액션 타입 (`receive`, `publish`, `subscribe_receive`, `deliver`)
   - `ts` (long) — 타임스탬프 (`System.currentTimeMillis()`)
3. 전파 경로 추가 로직 (4단계):
   - **Step 1 - WS서버 수신**: 클라이언트로부터 위치 업데이트 수신 시
     - `path.add({node: "ws-{INSTANCE_ID}", action: "receive", ts: now})`
   - **Step 2 - Redis publish**: Hash Ring으로 결정된 Redis 노드에 publish 시
     - `path.add({node: "redis-pubsub-{N}", action: "publish", ts: now})`
     - Redis 노드 이름은 Hash Ring에서 반환된 노드 ID 사용
   - **Step 3 - Redis subscribe 수신**: 다른 WS서버가 subscribe로 메시지 수신 시
     - `path.add({node: "redis-pubsub-{N}", action: "subscribe_receive", ts: now})`
     - Redis 노드 식별: `RedisMessageListenerContainer`가 어떤 `LettuceConnectionFactory`에 연결되어 있는지로 판단
   - **Step 4 - WS서버 전달**: 최종적으로 클라이언트에 전달 시
     - `path.add({node: "ws-{INSTANCE_ID}", action: "deliver", ts: now})`
4. `PropagationPathBuilder` 유틸리티 클래스:
   - `static PropagationPath receive(String wsInstanceId)` — Step 1 생성
   - `static PropagationPath publish(String redisNodeId)` — Step 2 생성
   - `static PropagationPath subscribeReceive(String redisNodeId)` — Step 3 생성
   - `static PropagationPath deliver(String wsInstanceId)` — Step 4 생성
5. 환경 변수 `INSTANCE_ID` (예: `ws-1`, `ws-2`)를 사용하여 자기 WS서버 식별

## 설정 참조
```yaml
# 환경 변수
INSTANCE_ID: ws-1  # 또는 ws-2
```

```java
// 사용 예시

// Step 1: WS서버가 클라이언트로부터 위치 수신
LocationUpdateMessage msg = new LocationUpdateMessage(userId, x, y, timestamp);
msg.addPath(PropagationPathBuilder.receive(instanceId));

// Step 2: Redis publish
String targetRedisNode = hashRing.getNode("user:" + userId);
msg.addPath(PropagationPathBuilder.publish(targetRedisNode));
redisPubSubManager.publish("user:" + userId, msg);

// Step 3: 다른 WS서버가 subscribe로 수신 (Pub/Sub 콜백에서)
msg.addPath(PropagationPathBuilder.subscribeReceive(sourceRedisNodeId));

// Step 4: 클라이언트에 전달
msg.addPath(PropagationPathBuilder.deliver(instanceId));
session.sendMessage(new TextMessage(toJson(msg)));
```

## 테스트 기준
- [ ] `LocationUpdateMessage` 생성 시 빈 `path` 배열로 시작하는지 확인
- [ ] `addPath` 호출 시 `path` 배열에 올바른 `PropagationPath` 객체가 추가되는지 확인
- [ ] 전체 4단계 경로 추가 후 `path` 배열 크기가 4인지 확인
- [ ] 각 `PropagationPath`의 `node`, `action`, `ts` 값이 올바른지 확인
- [ ] JSON 직렬화/역직렬화 후 `path` 배열이 보존되는지 확인
- [ ] `ts` 값이 단계별로 단조증가(monotonically increasing)하는지 확인
- [ ] `INSTANCE_ID` 환경 변수가 올바르게 주입되어 `node` 값에 반영되는지 확인

## 주의사항
> - 전파 경로 추적은 초기부터 메시지 포맷에 포함해야 한다. 나중에 추가하면 모든 핸들러를 수정해야 한다. (docs/domain-insights.md "전파 경로 추적은 시각화의 핵심이자 가장 어려운 부분" 참조)
> - Redis Pub/Sub 노드 자체는 메시지에 정보를 추가할 수 없다. subscribe하는 WS서버가 `RedisMessageListenerContainer`의 `LettuceConnectionFactory` 연결 정보로 출처 Redis 노드를 판단해야 한다. 이를 위해 P2-02에서 리스너를 Redis 인스턴스별로 분리해야 한다. (docs/domain-insights.md "전파 경로 메타데이터 추적의 실제 난이도" 참조)
> - `action` 값은 문자열이 아닌 enum으로 정의하는 것을 고려한다. 오타 방지와 타입 안전성 확보.
> - 프론트엔드로 전달되는 `FRIEND_LOCATION` 메시지에 `path` 배열을 포함하여 전파 경로 로그 패널에서 시각화할 수 있도록 한다.
