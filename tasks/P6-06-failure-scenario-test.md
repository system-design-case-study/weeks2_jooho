# P6-06: 장애 시나리오 테스트

## 메타정보
- Phase: 6
- 의존성: P6-04
- 예상 소요: 1시간
- 난이도: 상

## 목표
> Redis Pub/Sub 노드 장애, WS서버 장애, Redis Cache 장애, Docker restart 시 시스템의 graceful degradation과 재연결 동작을 검증한다.

## 컨텍스트
> design.md Phase 3(보너스)에서 정의된 장애 시나리오 시뮬레이션의 검증 단계다. 분산 시스템에서는 부분 장애가 일상이며, 장애 발생 시 전체 시스템이 중단되지 않고 가능한 범위에서 서비스를 지속하는 것이 중요하다. domain-insights.md에서 "Redis Pub/Sub 다중 인스턴스 구독 관리"를 프로젝트 최대 난관으로 식별했으며, 장애 시 이 관리가 어떻게 동작하는지를 실제로 확인한다.

## 상세 요구사항

### 1. Redis Pub/Sub 노드 1개 중지 → 부분 서비스 유지
- 정상 상태: 2개의 Redis Pub/Sub 노드로 위치 전파 동작 확인
- `docker stop redis-pubsub-1` 실행
- Hash Ring에서 redis-pubsub-1에 매핑된 채널의 메시지가 유실되는지 확인
- redis-pubsub-2에 매핑된 채널의 메시지는 정상 전파되는지 확인
- WS서버의 에러 로그에 연결 실패 메시지가 기록되는지 확인
- 사용자 관점에서 일부 친구의 위치가 업데이트되지 않는 현상 확인
- `docker start redis-pubsub-1` 후 정상 복구되는지 확인

### 2. WS서버 1개 중지 → 다른 WS서버 사용자 영향 없음
- 정상 상태: 탭 A가 ws-server-1에, 탭 B가 ws-server-2에 연결 (Nginx round-robin)
- `docker stop ws-server-1` 실행
- 탭 A의 WebSocket 연결이 끊어지는지 확인
- 탭 B의 WebSocket 연결은 유지되고, 위치 업데이트가 정상 동작하는지 확인
- 탭 A를 새로고침하면 ws-server-2로 재연결되는지 확인
- `docker start ws-server-1` 후 ws-server-1이 정상 복구되는지 확인
- 새로 접속하는 사용자가 ws-server-1에도 연결될 수 있는지 확인

### 3. Redis Cache 중지 → 위치 갱신 실패 시 에러 처리
- 정상 상태: 위치 업데이트 → Redis Cache 갱신 정상 동작
- `docker stop redis-cache` 실행
- 위치 업데이트 시도 시 WS서버에서 에러가 발생하는지 확인
- 에러가 전체 파이프라인을 중단시키지 않는지 확인:
  - Cassandra 이력 저장은 계속 동작하는가?
  - Pub/Sub 발행은 계속 동작하는가?
- 캐시 장애로 인해 초기 접속 시 친구 위치 조회가 실패하는지 확인
- WS서버 로그에 Redis Cache 연결 실패 에러가 기록되는지 확인
- `docker start redis-cache` 후 캐시 기능 정상 복구 확인

### 4. Docker restart 후 재연결 확인
- 전체 서비스 실행 상태에서 탭 A, B 접속 및 친구 관계 설정
- `docker restart ws-server-1` 실행
- ws-server-1에 연결되어 있던 클라이언트의 WebSocket 연결이 끊어지는지 확인
- 프론트엔드의 자동 재연결 로직(지수 백오프)이 동작하는지 확인
- 재연결 후 이전 상태(친구 관계, 위치)가 복구되는지 확인:
  - 친구 관계: PostgreSQL에서 재로드
  - 위치: Redis Cache에서 재로드 (캐시가 살아있는 경우)
- Pub/Sub 채널 구독이 재설정되는지 확인

### 5. Cassandra 중지 → 이력 저장 실패 시 동작
- `docker stop cassandra` 실행
- 위치 업데이트 전송 시 이력 저장은 실패하지만, 캐시 갱신 + Pub/Sub 발행은 정상 동작하는지 확인
- 즉, Cassandra 장애가 실시간 위치 전파에 영향을 주지 않는지 확인
- WS서버 로그에 Cassandra 연결 실패 에러가 기록되는지 확인

## 테스트 기준
- [ ] Redis Pub/Sub 1개 중지 시 나머지 노드의 채널은 정상 전파
- [ ] Redis Pub/Sub 1개 중지 시 해당 노드의 채널 메시지 유실 확인 및 에러 로깅
- [ ] WS서버 1개 중지 시 다른 WS서버 사용자 영향 없음
- [ ] WS서버 중지된 서버에 연결된 사용자의 연결 끊김 + 재연결 가능
- [ ] Redis Cache 중지 시 위치 갱신 실패하지만 Pub/Sub 전파는 유지
- [ ] Docker restart 후 클라이언트 자동 재연결 + 상태 복구
- [ ] Cassandra 중지 시 실시간 위치 전파에 영향 없음
- [ ] 장애 시나리오별 기대 동작 확인 완료

## 주의사항
> - domain-insights.md 핵심: WebSocket 서버는 Stateful이므로 컨테이너 재시작 = 모든 연결 손실. 재시작 후 프론트엔드 재연결과 상태 복구가 핵심 확인 포인트
> - lessons.md 경고: Redis 연결 끊김 시 Lettuce 클라이언트가 끊긴 TCP 연결에서 수 시간 동안 대기할 수 있음. TCP keepalive 설정 및 Lettuce의 reconnect 옵션이 올바르게 구성되었는지 확인
> - lessons.md 경고: Redis Pub/Sub는 fire-and-forget 특성으로, 구독자가 없는 채널에 발행한 메시지는 영구 소실됨. 노드 장애 시 해당 노드에 매핑된 채널의 메시지 유실은 의도된 동작임을 이해할 것
> - domain-insights.md 경고: Pub/Sub 콜백에서 블로킹 호출이 있으면 장애 시 전체 메시지 파이프라인이 중단됨. 장애 시나리오에서 이 문제가 더 심각하게 나타날 수 있음
> - 각 장애 시나리오 테스트 후 반드시 서비스를 복구(`docker start`)하여 다음 시나리오에 영향을 주지 않도록 할 것
