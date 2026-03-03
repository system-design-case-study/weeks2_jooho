# P6-03: 위치 변경 전체 파이프라인 통합 테스트

## 메타정보
- Phase: 6
- 의존성: P3-02, P3-03
- 예상 소요: 1시간
- 난이도: 상

## 목표
> WebSocket 연결부터 위치 업데이트 전송, 3개 저장소 갱신, Pub/Sub 전파, 거리 필터링까지 전체 데이터 흐름을 단일 통합 테스트로 검증한다.

## 컨텍스트
> design.md의 핵심 데이터 흐름: `사용자 위치 변경 → WS서버 → (a) Cassandra 이력 저장 (b) Redis 캐시 갱신 (c) Pub/Sub 발행 → 구독 WS서버 → 거리 필터링 → 클라이언트 전송`. 이 파이프라인은 5개의 인프라 컴포넌트(WebSocket, Cassandra, Redis Cache, Redis Pub/Sub, PostgreSQL)를 관통하며, 하나라도 실패하면 위치 전파가 불완전해진다. 이 테스트는 모든 컴포넌트가 정상적으로 연동되는지를 end-to-end로 검증한다.

## 상세 요구사항

### 1. 테스트 인프라 구성
- Testcontainers 또는 Docker Compose 테스트 프로파일로 전체 인프라 구성
  - Redis Cache 1개, Redis Pub/Sub 2개, PostgreSQL 1개, Cassandra 1개
- Spring Boot 테스트 컨텍스트에서 WebSocket 서버 기동
- WebSocket 테스트 클라이언트 2개 생성 (서로 다른 사용자 시뮬레이션)

### 2. WebSocket 연결 → LOCATION_UPDATE 전송 → 3개 저장소 갱신
- 클라이언트 A가 WebSocket 연결 후 `INIT` 메시지 수신 확인
- 클라이언트 A가 `{"type": "LOCATION_UPDATE", "x": 500, "y": 300}` 전송
- 3개 저장소에 병렬로 갱신이 발생하는지 확인:
  - (a) Cassandra `location_history` 테이블에 이력 레코드 존재 확인
  - (b) Redis Cache `location:user:{id}` 키에 `{x: 500, y: 300}` 저장 확인
  - (c) Redis Pub/Sub에 메시지 발행 확인

### 3. Cassandra 이력 저장 확인
- `location_history` 테이블에서 해당 user_id, timestamp로 조회
- x=500, y=300, ws_server가 올바른 인스턴스 ID인지 확인
- 비동기 저장이므로 약간의 대기 시간(최대 2초) 허용

### 4. Redis 캐시 갱신 확인
- `location:user:{userId}` 키 조회
- JSON 값의 x, y, timestamp가 정확한지 확인
- TTL이 설정되어 있는지 확인 (600초)

### 5. Redis Pub/Sub 발행 → 다른 WS서버 수신 확인
- 클라이언트 A와 B가 서로 친구 관계 설정 (PostgreSQL에 friendships 레코드)
- 클라이언트 A의 위치 업데이트가 Pub/Sub를 통해 클라이언트 B에게 전달되는지 확인
- 클라이언트 B가 수신한 `FRIEND_LOCATION` 메시지의 friendId, x, y 확인

### 6. 거리 필터링 → FRIEND_LOCATION 전송 확인
- 클라이언트 A 위치: (100, 100), 클라이언트 B 위치: (200, 200)
- 검색 반경 200 → Euclidean 거리 약 141 → 반경 내 → `inRange: true`로 전송 확인
- 클라이언트 A 위치를 (900, 900)으로 이동 → 거리 약 990 → 반경 밖 → drop 또는 `inRange: false`
- distance 값이 정확하게 계산되는지 확인

### 7. 전파 경로 메타데이터 정확성 확인
- 수신된 메시지의 `path` 배열에 다음 단계가 포함되는지 확인:
  - `{node: "ws-X", action: "receive"}`
  - `{node: "redis-pubsub-X", action: "publish"}`
  - `{node: "redis-pubsub-X", action: "subscribe_receive"}`
  - `{node: "ws-Y", action: "deliver"}`
- 각 단계의 timestamp가 단조 증가하는지 확인

## 테스트 기준
- [ ] WebSocket 연결 성공 + INIT 메시지 수신
- [ ] LOCATION_UPDATE 전송 후 Cassandra에 이력 레코드 존재
- [ ] LOCATION_UPDATE 전송 후 Redis Cache에 위치 데이터 존재 + TTL 설정
- [ ] Pub/Sub를 통한 친구 간 위치 전파 성공
- [ ] 반경 내 친구에게 FRIEND_LOCATION 메시지 전달 확인
- [ ] 반경 밖 친구에게는 메시지 미전달 또는 inRange: false 확인
- [ ] 전파 경로 path 배열에 4단계 메타데이터 포함 + timestamp 단조 증가
- [ ] 모든 통합 테스트 통과

## 주의사항
> - domain-insights.md 경고: Pub/Sub는 fire-and-forget이므로, subscribe 완료 전에 publish된 메시지는 유실됨. 테스트에서 친구 관계 설정 및 subscribe 완료를 확인한 후 위치 업데이트를 전송할 것
> - lessons.md 경고: Cassandra 이력 저장은 비동기이므로 즉시 조회하면 레코드가 없을 수 있음. 적절한 대기 또는 폴링 로직 필요
> - domain-insights.md 경고: "subscribe 측은 모든 WS서버가 해당 Redis 인스턴스를 구독해야 한다"는 비대칭 구조가 테스트에서도 정확히 반영되어야 함
> - 전파 경로 메타데이터는 design.md에서 "초기부터 포함"으로 명시됨. path 배열이 빠져 있으면 나중에 모든 핸들러를 수정해야 하므로 이 테스트에서 반드시 검증할 것
