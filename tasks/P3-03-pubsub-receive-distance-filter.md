# P3-03: Pub/Sub 수신 + 거리 필터링 + 클라이언트 전송

## 메타정보
- Phase: 3
- 의존성: P3-02, P2-06
- 예상 소요: 1시간
- 난이도: 상

## 목표
> Redis Pub/Sub에서 친구 위치 메시지를 수신하고, Euclidean 거리 필터링을 적용하여 반경 내 클라이언트에게만 FRIEND_LOCATION 메시지를 전송하며, PROPAGATION_LOG 메시지를 생성한다.

## 컨텍스트
> Pub/Sub 수신 측 로직. architecture.md "데이터 흐름 > 1. 위치 변경"의 subscribe 측 처리에 해당한다. 모든 WS서버가 모든 Redis Pub/Sub 노드를 subscribe하고 있으므로, 수신 측에서는 자신에게 연결된 클라이언트 중 해당 친구를 등록한 사용자를 탐색하여 거리 계산 후 전송한다. 전파 경로 메타데이터의 후반부(`subscribe_receive`, `deliver`)도 이 단계에서 추가된다.

## 상세 요구사항
1. Redis Pub/Sub 메시지 수신 리스너 구현
   - `RedisMessageListenerContainer`에 등록된 리스너에서 메시지 수신
   - **Lettuce EventLoop 스레드에서 블로킹 호출 금지** — 수신 즉시 별도 스레드풀(`TaskExecutor`)로 위임
   - 리스너가 Redis 인스턴스별로 분리되어 어떤 Redis 노드에서 메시지가 왔는지 식별 가능
2. 수신 메시지 파싱
   - 발행 측에서 보낸 내부 메시지 포맷: `{userId, x, y, timestamp, path}`
   - `path` 배열에 `subscribe_receive` 정보 추가
     ```json
     {"node": "redis-pubsub-1", "action": "subscribe_receive", "ts": 1234567892}
     ```
3. 로컬 클라이언트 탐색
   - 세션 맵에서 현재 WS서버에 연결된 클라이언트 중, 해당 `userId`를 친구로 등록한 사용자 탐색
   - 자기 자신에게는 전송하지 않음 (자신의 위치 업데이트를 자신에게 다시 보내는 것 방지)
4. Euclidean 거리 계산
   - `distance = sqrt((x2-x1)^2 + (y2-y1)^2)`
   - 검색 반경(기본 200) 내인지 판별
5. 클라이언트 전송
   - **반경 내**: FRIEND_LOCATION 메시지 전송 (design.md 포맷)
     ```json
     {
       "type": "FRIEND_LOCATION",
       "friendId": "user-3",
       "x": 200, "y": 400,
       "timestamp": 1234567890,
       "distance": 142.5,
       "inRange": true
     }
     ```
   - **반경 밖**: `inRange: false`로 전송 (프론트엔드에서 회색 처리)
   - `path` 배열에 `deliver` 정보 추가
     ```json
     {"node": "ws-1", "action": "deliver", "ts": 1234567893}
     ```
6. PROPAGATION_LOG 메시지 생성 (design.md 포맷)
   ```json
   {
     "type": "PROPAGATION_LOG",
     "sourceUser": "user-3",
     "wsServer": "ws-2",
     "redisNode": "redis-pubsub-1",
     "channel": "user:3",
     "subscribers": [
       {"userId": "user-1", "wsServer": "ws-1", "distance": 142, "inRange": true, "sent": true},
       {"userId": "user-5", "wsServer": "ws-2", "distance": 890, "inRange": false, "sent": false}
     ]
   }
   ```
   - 시각화용으로 모든 연결된 클라이언트에 브로드캐스트

## 설정 참조
```java
// 별도 스레드풀 설정 (architecture.md Pub/Sub 콜백 주의사항)
@Bean
public TaskExecutor pubSubTaskExecutor() {
    ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
    executor.setCorePoolSize(4);
    executor.setMaxPoolSize(8);
    executor.setThreadNamePrefix("pubsub-");
    return executor;
}
```

## 테스트 기준
- [ ] Redis Pub/Sub 메시지 수신 시 별도 스레드풀에서 처리되는지 확인
- [ ] 친구 관계인 사용자에게만 FRIEND_LOCATION 메시지가 전송되는지 확인
- [ ] 반경 내 친구에게 `inRange: true`, 반경 밖 친구에게 `inRange: false` 전송 확인
- [ ] Euclidean 거리 계산이 정확한지 확인
- [ ] PROPAGATION_LOG 메시지가 모든 클라이언트에 브로드캐스트되는지 확인
- [ ] 자기 자신의 위치 업데이트가 자신에게 재전송되지 않는지 확인
- [ ] 수신 메시지의 `path` 배열에 `subscribe_receive`, `deliver` 정보가 추가되는지 확인

## 주의사항
> - domain-insights.md: Pub/Sub 콜백에서 블로킹 호출이 "위치 업데이트가 간헐적으로 안 오는" 가장 흔한 원인. **반드시 별도 스레드풀로 위임**
> - domain-insights.md: 리스너를 Redis 인스턴스별로 분리하여 메시지가 어떤 Redis 노드에서 왔는지 식별 가능하게 구성 (전파 경로 추적에 필수)
> - lessons.md: Pub/Sub 리스너 스레드 블로킹 — `RedisMessageListenerContainer`에 별도 `TaskExecutor` 설정 필수
> - architecture.md: Pub/Sub 비대칭성 — subscribe는 모든 WS서버가 모든 Redis 노드에 연결
> - design.md: 반경 내 친구 초록색, 반경 밖 회색 — `inRange` 필드로 프론트엔드에 전달
