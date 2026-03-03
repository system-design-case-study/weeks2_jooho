# P3-05: 시스템 상태 REST API

## 메타정보
- Phase: 3
- 의존성: P3-01, P2-01, P2-02, P2-03
- 예상 소요: 45분
- 난이도: 중

## 목표
> 시스템 내부 상태(Hash Ring, 연결 사용자, Redis 채널, 캐시 위치)를 조회하는 REST API가 동작하여, 프론트엔드 시각화 패널에 데이터를 제공한다.

## 컨텍스트
> Phase 5 시각화 패널의 데이터 소스. design.md "REST API" 섹션의 `/api/system/*` 엔드포인트에 해당한다. Hash Ring의 원형 다이어그램, WS서버별 연결 상태, Redis 노드별 채널 정보를 프론트엔드에 전달한다. 시각화와 실제 인프라를 연결하는 브릿지 역할.

## 상세 요구사항
1. `SystemController` 생성
2. `GET /api/system/hash-ring` — Hash Ring 현재 상태
   - ConsistentHashRing의 `getRingState()` 호출
   - 응답 포맷:
     ```json
     {
       "nodes": [
         {"position": 200, "server": "redis-pubsub-1", "virtual": false, "virtualIndex": 0},
         {"position": 350, "server": "redis-pubsub-1", "virtual": true, "virtualIndex": 1}
       ],
       "channelMapping": {
         "user:1": "redis-pubsub-2",
         "user:2": "redis-pubsub-1"
       }
     }
     ```
   - `channelMapping`은 현재 활성 사용자의 채널만 포함
3. `GET /api/system/connections` — WS서버별 연결 사용자
   - 현재 WS서버의 세션 맵에서 연결된 사용자 목록 반환
   - 응답 포맷:
     ```json
     {
       "serverId": "ws-1",
       "users": [
         {"userId": "user-1", "x": 100, "y": 200},
         {"userId": "user-3", "x": 500, "y": 600}
       ],
       "totalConnections": 2
     }
     ```
4. `GET /api/system/channels` — Redis 노드별 채널/구독자
   - PubSubManager에서 각 Redis 노드별 구독 중인 채널 목록 반환
   - 응답 포맷:
     ```json
     {
       "redis-pubsub-1": {
         "channels": ["user:1", "user:3"],
         "subscriberCount": 2
       },
       "redis-pubsub-2": {
         "channels": ["user:2", "user:5"],
         "subscriberCount": 2
       }
     }
     ```
5. `GET /api/system/cache` — Redis 캐시 위치 목록
   - Redis Cache에서 `location:user:*` 패턴의 모든 키를 스캔하여 위치 반환
   - 응답 포맷:
     ```json
     [
       {"userId": "user-1", "x": 100, "y": 200, "timestamp": 1234567890, "ttl": 3500}
     ]
     ```
   - **주의**: `KEYS` 명령 대신 `SCAN` 사용 (프로덕션 습관)

## 설정 참조
- Hash Ring 상태 조회: `ConsistentHashRing.getRingState()` (P2-01에서 구현)
- 세션 맵: `LocationWebSocketHandler`의 `ConcurrentHashMap<String, UserSession>` (P3-01)
- Pub/Sub 채널 관리: `PubSubManager`에서 채널 목록 관리 (P2 task)
- Redis Cache 스캔: `RedisTemplate.scan()` 또는 `SCAN` 커맨드 사용

## 테스트 기준
- [ ] GET /api/system/hash-ring — Hash Ring 노드 위치와 채널 매핑 정보 반환 확인
- [ ] GET /api/system/connections — 현재 WS서버의 연결 사용자 목록 반환 확인
- [ ] GET /api/system/channels — Redis 노드별 구독 채널 목록 반환 확인
- [ ] GET /api/system/cache — Redis 캐시의 위치 데이터 반환 확인
- [ ] 사용자 접속/해제 후 connections, channels API 응답이 갱신되는지 확인
- [ ] API 응답이 실제 시스템 상태와 일치하는지 교차 검증

## 주의사항
> - design.md: API 응답의 SYSTEM_STATE WebSocket 메시지 포맷 참조 — REST API와 WS 메시지의 데이터 구조를 일관되게 유지
> - domain-insights.md: 이 API는 각 WS서버의 로컬 상태를 반환함. Nginx round-robin으로 인해 요청마다 다른 서버의 상태를 볼 수 있음. 프론트엔드에서 모든 서버의 상태를 수집하려면 서버 ID를 지정하여 요청하거나, 특정 서버로 라우팅하는 방법이 필요
> - lessons.md: Redis `KEYS` 명령은 O(N)으로 프로덕션에서 사용 금지. `SCAN` 커맨드 사용 습관화
