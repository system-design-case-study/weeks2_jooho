# P6-06: 장애 시나리오 테스트 가이드

## 개요

이 가이드는 분산 시스템의 장애 시나리오를 검증하기 위한 테스트 프레임워크입니다.

### 테스트 목표
- Redis Pub/Sub 노드 장애 시 graceful degradation 확인
- WS서버 장애 시 다른 서버의 사용자 영향 없음 확인
- Redis Cache 장애 시 실시간 전파 지속 여부 확인
- Docker restart 후 자동 재연결 및 상태 복구 확인
- Cassandra 장애 시 실시간 위치 전파 영향 없음 확인

## 파일 구성

| 파일 | 설명 |
|------|------|
| `failure-scenario-test.sh` | 메인 테스트 스크립트 (5가지 시나리오) |
| `verify-services.sh` | 서비스 상태 검증 유틸리티 |
| `analyze-logs.sh` | 로그 분석 유틸리티 |
| `README-FAILURE-SCENARIO.md` | 이 파일 |

## 사전 준비

### 1. Docker Compose 환경 구성 확인

```bash
# 모든 서비스가 정상 기동되었는지 확인
docker-compose ps

# 예상 상태:
# - 9개 컨테이너 모두 Up 상태
# - healthcheck가 모두 healthy (또는 시작 중)
```

### 2. 스크립트 실행 권한 설정

```bash
chmod +x tests/failure-scenario-test.sh
chmod +x tests/verify-services.sh
chmod +x tests/analyze-logs.sh
```

### 3. 브라우저 개발자 도구 준비

테스트 중 다음을 모니터링합니다:
- **Network 탭**: WebSocket 연결 상태
- **Console 탭**: 에러 및 로그 메시지
- **Application 탭**: 캐시된 데이터 확인

## 빠른 시작

### 기본 테스트 실행

```bash
# 메인 테스트 스크립트 실행 (대화형)
./tests/failure-scenario-test.sh
```

이 스크립트는 다음을 수행합니다:
1. 각 장애 시나리오 설명 출력
2. 사용자에게 docker stop/start 명령 실행 지시
3. 각 단계 후 상태 검증
4. 최종 체크리스트 제시

### 서비스 상태 빠른 확인

```bash
# 전체 서비스 상태 확인
./tests/verify-services.sh all

# 특정 서비스만 확인
./tests/verify-services.sh redis      # Redis Cache + Pub/Sub
./tests/verify-services.sh websocket  # WebSocket 서버
./tests/verify-services.sh postgres   # PostgreSQL
./tests/verify-services.sh cassandra  # Cassandra
./tests/verify-services.sh network    # 네트워크 연결성
```

### 로그 분석

```bash
# Redis 연결 에러 분석
./tests/analyze-logs.sh redis

# Cassandra 연결 에러 분석
./tests/analyze-logs.sh cassandra

# WS서버 1 에러 로그 확인
./tests/analyze-logs.sh ws-server-1

# 패턴으로 로그 추출
./tests/analyze-logs.sh extract ws-server-1 "connection\|timeout"
```

## 시나리오별 상세 가이드

### 시나리오 1: Redis Pub/Sub 노드 1개 중지

**목표**: Hash Ring에서 매핑된 채널의 메시지 유실 확인

**테스트 절차**:
1. 정상 상태에서 redis-pubsub-1, redis-pubsub-2 PING 확인
2. `docker stop redis-pubsub-1` 실행
3. redis-pubsub-2는 정상 작동 확인
4. WS서버 로그에서 redis-pubsub-1 연결 실패 에러 확인
5. `docker start redis-pubsub-1` 실행
6. redis-pubsub-1 복구 확인

**기대 동작**:
- redis-pubsub-1에 매핑된 채널의 메시지: 영구 소실 (Pub/Sub fire-and-forget 특성)
- redis-pubsub-2에 매핑된 채널의 메시지: 정상 전파
- 사용자가 친구를 추가했을 때 일부 친구의 위치는 업데이트되지 않을 수 있음
- WS서버는 에러를 로그하지만 완전히 중단되지는 않음

**검증 포인트**:
```bash
# 복구 후 Redis Pub/Sub 정상 작동 확인
docker exec -i redis-pubsub-1 redis-cli -p 6380 PING

# WS서버 로그에서 연결 실패 에러 확인
docker-compose logs ws-server-1 | grep -i "redis\|connection" | tail -20
```

### 시나리오 2: WS서버 1개 중지

**목표**: 한 WS서버의 장애가 다른 서버의 사용자에게 영향을 주지 않음 확인

**테스트 절차**:
1. 브라우저 탭 A: ws://localhost/ws/location 연결 (Nginx round-robin → ws-server-1)
2. 브라우저 탭 B: ws://localhost/ws/location 연결 (Nginx round-robin → ws-server-2)
3. `docker stop ws-server-1` 실행
4. 탭 A의 WebSocket 연결 끊김 확인
5. 탭 B의 위치 업데이트가 정상 작동함을 확인
6. 탭 A를 새로고침하면 ws-server-2로 재연결되는지 확인
7. `docker start ws-server-1` 실행
8. 새로 접속하는 탭 C가 ws-server-1에도 연결될 수 있는지 확인

**기대 동작**:
- 탭 A: WebSocket 연결 끊어짐 (Connection closed)
- 탭 B: WebSocket 연결 유지, 위치 업데이트 정상 동작
- 탭 A 새로고침 후: ws-server-2로 재연결
- 탭 C: 새 접속 시 round-robin으로 배분 (ws-server-1 또는 ws-server-2)

**검증 포인트**:
```bash
# ws-server-1 상태 확인
docker-compose ps ws-server-1

# WS서버 로그에서 연결 종료 메시지 확인
docker-compose logs ws-server-1 | grep -i "close\|disconnect"
```

### 시나리오 3: Redis Cache 중지

**목표**: 위치 갱신 실패 시 에러 처리 및 다른 경로의 정상 동작 확인

**테스트 절차**:
1. `docker stop redis-cache` 실행
2. redis-cache PING 응답 없음 확인
3. 브라우저에서 위치 업데이트 시도
4. WS서버 로그에서 Redis Cache 연결 실패 에러 확인
5. 위치 업데이트가 실패하지만 다른 경로는 정상 동작하는지 확인
6. `docker start redis-cache` 실행
7. redis-cache 복구 후 위치 갱신 정상 동작 확인

**기대 동작**:
- Redis Cache 중단 후 위치 업데이트 실패 (캐시 갱신 불가)
- 에러 메시지가 WS서버 로그에 기록됨
- Cassandra 이력 저장은 계속 동작 (독립적인 경로)
- Redis Pub/Sub 발행은 계속 동작 (독립적인 경로)
- 초기 접속 시 캐시에서 친구 위치 조회 실패

**검증 포인트**:
```bash
# Redis Cache 상태 확인
docker exec -i redis-cache redis-cli PING

# WS서버 로그에서 캐시 에러 확인
docker-compose logs ws-server-1 | grep -i "cache\|redis" | tail -20
```

### 시나리오 4: Docker restart 후 재연결

**목표**: 클라이언트 자동 재연결 및 상태 복구 확인

**테스트 절차**:
1. 브라우저 탭 A, B 접속 및 친구 관계 설정
2. 탭 A의 위치를 업데이트하고 탭 B에서 수신 확인
3. `docker restart ws-server-1` 실행
4. 탭 A의 WebSocket 연결 끊김 확인
5. 프론트엔드 자동 재연결 로직 동작 확인 (5-10초)
6. 재연결 후 상태 복구 확인:
   - 친구 관계 (PostgreSQL 재로드)
   - 위치 (Redis Cache 재로드)
   - Pub/Sub 채널 재구독

**기대 동작**:
- ws-server-1 컨테이너 재시작 중 모든 연결 손실
- 브라우저가 지수 백오프를 사용하여 자동 재연결 시도 (1s, 2s, 4s, 8s, 10s 최대)
- 5-10초 내에 재연결 성공
- INIT 메시지 수신 (userId 확인)
- 친구 관계 복구 (DB에서 재로드)
- 위치 복구 (캐시에서 재로드)
- Pub/Sub 채널 재구독

**검증 포인트**:
```bash
# ws-server-1 건강 상태 확인
docker inspect ws-server-1 | grep -A 5 '"Health"'

# 브라우저 Network 탭에서 새로운 WebSocket 연결 확인
# 브라우저 Console에서 재연결 로그 확인
```

### 시나리오 5: Cassandra 중지

**목표**: 이력 저장 실패 시 실시간 위치 전파는 영향 없음 확인

**테스트 절차**:
1. `docker stop cassandra` 실행
2. Cassandra 응답 없음 확인
3. 브라우저에서 위치 업데이트 시도
4. WS서버 로그에서 Cassandra 연결 실패 에러 확인
5. 위치 업데이트가 실시간으로 전파되는지 확인 (친구가 수신)
6. `docker start cassandra` 실행
7. Cassandra 복구 후 이력 저장 정상 동작 확인

**기대 동작**:
- Cassandra 중단 후 이력 저장 실패 (에러 로그 기록)
- Redis Cache 갱신은 정상 동작
- Redis Pub/Sub 발행은 정상 동작
- 실시간 위치 전파는 영향 없음 (친구가 위치 수신)
- 즉, 캐시와 Pub/Sub은 독립적으로 동작

**검증 포인트**:
```bash
# Cassandra 상태 확인 (시작 시간 오래 걸림)
docker-compose ps cassandra

# WS서버 로그에서 Cassandra 에러 확인
docker-compose logs ws-server-1 | grep -i "cassandra" | tail -20

# 브라우저: 친구가 위치 업데이트를 수신했는지 확인
```

## 검증 체크리스트

모든 시나리오 완료 후 다음을 확인하세요:

- [ ] Redis Pub/Sub 1개 중지 시 나머지 노드의 채널은 정상 전파
- [ ] Redis Pub/Sub 1개 중지 시 해당 노드의 채널 메시지 유실 확인 및 에러 로깅
- [ ] WS서버 1개 중지 시 다른 WS서버 사용자 영향 없음
- [ ] WS서버 중지된 서버에 연결된 사용자의 연결 끊김 + 재연결 가능
- [ ] Redis Cache 중지 시 위치 갱신 실패하지만 Pub/Sub 전파는 유지
- [ ] Docker restart 후 클라이언트 자동 재연결 + 상태 복구
- [ ] Cassandra 중지 시 실시간 위치 전파에 영향 없음
- [ ] 장애 시나리오별 기대 동작 확인 완료

## 주의사항

### 1. WebSocket 서버는 Stateful

- WS서버 재시작 = 모든 연결 손실
- 재시작 후 프론트엔드의 자동 재연결이 핵심
- 상태는 DB/캐시에서 재로드됨

### 2. Redis Pub/Sub의 fire-and-forget 특성

- 구독자가 없는 채널에 발행한 메시지는 영구 소실됨
- Redis Pub/Sub 노드 장애 시 해당 채널의 메시지 유실은 의도된 동작
- 이력이 필요한 경우 Cassandra와 함께 사용해야 함

### 3. Lettuce 클라이언트 주의

- 끊긴 TCP 연결에서 수 시간 동안 대기할 수 있음
- TCP keepalive 설정이 필수
- Lettuce의 reconnect 옵션 확인 필요

### 4. Pub/Sub 콜백 블로킹

- 장애 시 콜백에서 블로킹 호출이 있으면 전체 파이프라인이 중단됨
- 콜백은 비동기/논블로킹으로 구현되어야 함

### 5. 각 시나리오 후 복구

- 다음 시나리오 테스트에 영향을 주지 않도록 반드시 서비스 복구
- 복구 후 healthcheck가 healthy로 전환될 때까지 대기

## 문제 해결

### 서비스가 시작되지 않는 경우

```bash
# 포트 충돌 확인
lsof -i :6379  # Redis Cache
lsof -i :6380  # Redis Pub/Sub 1
lsof -i :6381  # Redis Pub/Sub 2
lsof -i :5432  # PostgreSQL
lsof -i :9042  # Cassandra
lsof -i :8080  # WS서버
lsof -i :80    # Nginx
lsof -i :3000  # Frontend

# 기존 컨테이너 정리
docker-compose down -v
docker-compose up -d
```

### 자동 재연결이 작동하지 않는 경우

브라우저 콘솔에서 확인:
- WebSocket close 이벤트 발생?
- 재연결 로직이 트리거되었는가?
- 네트워크 탭에서 새로운 WebSocket 연결 시도?

```javascript
// 브라우저 콘솔에서 수동 테스트
ws = new WebSocket('ws://localhost/ws/location');
ws.onopen = () => console.log('Connected');
ws.onerror = (e) => console.log('Error:', e);
ws.onclose = () => console.log('Closed');
```

### 에러 로그가 보이지 않는 경우

```bash
# 더 많은 로그 확인
docker-compose logs --tail=500 ws-server-1

# 실시간 로그 모니터링
docker-compose logs -f ws-server-1

# 특정 패턴으로 검색
docker-compose logs ws-server-1 | grep -i "error\|exception"
```

## 추가 유틸리티 명령

### 완전한 서비스 상태 리포트

```bash
# 모든 서비스의 상태, 헬스체크, 연결성 확인
./tests/verify-services.sh all

# 출력 예시:
# ✓ frontend: running (healthy)
# ✓ nginx: running (healthy)
# ✓ ws-server-1: running (healthy)
# ✓ ws-server-2: running (healthy)
# ✓ redis-cache: running (healthy)
# ✓ redis-pubsub-1: running (healthy)
# ✓ redis-pubsub-2: running (healthy)
# ✓ postgresql: running (healthy)
# ✓ cassandra: running (healthy)
```

### 네트워크 연결성 확인

```bash
# WS서버에서 모든 의존성으로의 연결 확인
./tests/verify-services.sh network

# 출력 예시:
# ✓ redis-cache: Connected
# ✓ redis-pubsub-1: Connected
# ✓ redis-pubsub-2: Connected
# ✓ postgresql: Connected
# ✓ cassandra: Connected
```

### 메시지 전파 추적

```bash
# Pub/Sub 메시지 발행 기록 확인
./tests/analyze-logs.sh propagation

# WebSocket 연결 이벤트 추적
./tests/analyze-logs.sh websocket

# 위치 업데이트 추적
./tests/analyze-logs.sh location
```

## 참고 자료

- `domain-insights.md`: Redis Pub/Sub 다중 인스턴스 구독 관리의 핵심 개념
- `lessons.md`: Lettuce 클라이언트 주의사항, 이전 학습 내용
- `design.md`: Phase 3 (보너스) 장애 시나리오 시뮬레이션 정의
- `docker-compose.yml`: 서비스 구성 및 의존성

## 결론

이 테스트는 분산 시스템의 부분 장애 시나리오에서 graceful degradation이 정상적으로 동작하는지 검증합니다.

모든 시나리오가 성공적으로 완료되면 시스템은 다음을 만족합니다:
- 부분 장애 시 전체 시스템이 중단되지 않음
- 장애 서비스를 제외한 나머지는 정상 작동
- 복구 후 자동으로 정상 상태로 복구
- 사용자 관점에서 가능한 범위의 서비스 지속 제공
