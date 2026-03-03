# P3-02: 위치 변경 처리 파이프라인

## 메타정보
- Phase: 3
- 의존성: P3-01, P2-04, P2-07
- 예상 소요: 1시간
- 난이도: 상

## 목표
> LOCATION_UPDATE 메시지 수신 시 Cassandra 이력 저장(비동기), Redis 캐시 갱신(동기), Pub/Sub 발행(Hash Ring 기반)을 병렬로 수행하고, 전파 경로 메타데이터를 메시지에 포함한다.

## 컨텍스트
> 위치 변경의 핵심 처리 경로. design.md "데이터 흐름 > 처리" 섹션의 (a)(b)(c) 병렬 처리를 구현한다. architecture.md "데이터 흐름 > 1. 위치 변경" 다이어그램이 정확한 명세. 이 파이프라인의 성능이 전체 시스템의 실시간성(200ms 이내 체감 지연)을 결정한다.

## 상세 요구사항
1. `LocationWebSocketHandler.handleTextMessage()`에서 `LOCATION_UPDATE` 처리
   - 클라이언트 메시지: `{ "type": "LOCATION_UPDATE", "x": 500, "y": 300 }`
   - 세션 맵에서 사용자 좌표 갱신
2. 병렬 처리 3가지 (CompletableFuture 또는 @Async 활용)
   - **(a) LocationHistoryService.saveLocation()** — Cassandra 비동기 저장
     - `user_id`, `timestamp`, `x`, `y`
     - 실패해도 전체 흐름 중단하지 않음 (fire-and-forget)
   - **(b) LocationCacheService.updateLocation()** — Redis 캐시 동기 갱신
     - `location:user:{id}` -> `{x, y, timestamp}` + TTL 리셋
     - 이 연산은 후속 조회 일관성을 위해 동기로 완료 대기
   - **(c) PubSubManager.publish()** — Hash Ring 기반 노드에 publish
     - Consistent Hash Ring으로 `"user:{id}"` 채널의 대상 Redis Pub/Sub 노드 결정
     - 해당 노드에만 publish
3. 전파 경로 메타데이터 생성 (design.md "전파 경로 메타데이터" 섹션)
   - Pub/Sub에 발행하는 내부 메시지에 `path` 배열 포함
   ```json
   {
     "userId": "user-3",
     "x": 200, "y": 400,
     "timestamp": 1234567890,
     "path": [
       {"node": "ws-2", "action": "receive", "ts": 1234567890},
       {"node": "redis-pubsub-1", "action": "publish", "ts": 1234567891}
     ]
   }
   ```
   - `node` 값은 `INSTANCE_ID` 환경 변수에서 가져옴
   - Redis Pub/Sub 노드 이름은 Hash Ring에서 반환된 노드 식별자 사용
4. 에러 처리
   - Cassandra 저장 실패: 로그 경고 후 계속 진행
   - Redis 캐시 갱신 실패: 로그 에러, 전체 처리 중단 고려
   - Pub/Sub 발행 실패: 로그 에러, 위치는 캐시에 저장되었으므로 다음 업데이트에서 복구

## 설정 참조
```yaml
# 환경 변수 (architecture.md)
INSTANCE_ID: ws-1  # 전파 경로 메타데이터에 사용
```

## 테스트 기준
- [ ] LOCATION_UPDATE 수신 시 세션 맵의 좌표가 갱신되는지 확인
- [ ] Cassandra에 위치 이력이 저장되는지 확인 (비동기)
- [ ] Redis 캐시에 최신 위치가 갱신되고 TTL이 리셋되는지 확인
- [ ] Pub/Sub에 올바른 Redis 노드로 메시지가 발행되는지 확인
- [ ] 발행된 메시지에 `path` 배열이 포함되어 있는지 확인
- [ ] Cassandra 장애 시에도 캐시 갱신과 Pub/Sub 발행이 정상 동작하는지 확인

## 주의사항
> - domain-insights.md: 전파 경로 추적은 설계 초기부터 메시지 포맷에 반영해야 함. 나중에 추가하면 모든 핸들러 수정 필요
> - lessons.md: Pub/Sub 콜백에서 블로킹 호출 금지 — 이 task는 publish 측이므로 직접 해당하지 않으나, publish 자체도 Lettuce async API 사용 권장
> - architecture.md: Pub/Sub 비대칭성 — publish는 Hash Ring으로 1개 노드만, subscribe는 모든 노드
> - design.md: 비기능 요구사항 — 위치 드래그 → 친구 화면 반영까지 체감 지연 200ms 이내
