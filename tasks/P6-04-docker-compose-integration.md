# P6-04: Docker Compose 전체 통합 검증

## 메타정보
- Phase: 6
- 의존성: 모든 P1 task (P1-01 ~ P1-08)
- 예상 소요: 45분
- 난이도: 중

## 목표
> `docker-compose up` 한 방에 9개 서비스가 정상 기동되고, 서비스 간 네트워크 통신 및 WebSocket 프록시가 동작함을 검증한다.

## 컨텍스트
> design.md에 명시된 Docker Compose 구성은 9개 컨테이너(Frontend, Nginx, WS-Server x2, Redis Cache, Redis Pub/Sub x2, PostgreSQL, Cassandra)로 이루어진다. architecture.md에 따르면 `depends_on` + `condition: service_healthy`로 시작 순서를 보장하며, 모든 컨테이너는 단일 Docker bridge 네트워크에서 컨테이너 이름으로 DNS 해석된다. 이 검증은 인프라 레벨의 안정성을 확인하는 게이트 역할을 하며, 모든 후속 테스트의 전제 조건이다.

## 상세 요구사항

### 1. 전체 서비스 기동 확인
- `docker-compose up -d` 실행 후 9개 컨테이너 모두 `running` 상태 확인
- 각 서비스의 healthcheck가 `healthy` 상태로 전환되는지 확인
- 기동 순서가 architecture.md에 명시된 대로 진행되는지 확인:
  - PostgreSQL, Cassandra, Redis (cache + pubsub) → healthy 후
  - WS-Server-1, WS-Server-2 → healthy 후
  - Nginx → healthy 후
  - Frontend

### 2. 서비스 간 네트워크 통신 확인
- WS-Server → Redis Cache (6379) 연결 성공
- WS-Server → Redis Pub/Sub 1 (6380) 연결 성공
- WS-Server → Redis Pub/Sub 2 (6381) 연결 성공
- WS-Server → PostgreSQL (5432) 연결 성공
- WS-Server → Cassandra (9042) 연결 성공
- 컨테이너 이름 기반 DNS 해석 동작 확인 (예: `redis-cache`, `postgresql` 등)

### 3. Nginx WebSocket 프록시 동작 확인
- 브라우저 또는 `wscat`으로 `ws://localhost:80/ws/location` 연결 시도
- HTTP 101 Switching Protocols 응답 수신 확인
- WebSocket 연결이 60초 이상 유지되는지 확인 (`proxy_read_timeout` 설정 검증)
- Nginx 설정 4종 세트 확인:
  - `proxy_http_version 1.1`
  - `proxy_set_header Upgrade $http_upgrade`
  - `proxy_set_header Connection "upgrade"`
  - `proxy_read_timeout` (3600s 이상)

### 4. Healthcheck 기반 시작 순서 보장 확인
- PostgreSQL healthcheck: `pg_isready` 명령 성공
- Cassandra healthcheck: CQL 쿼리 성공
- Redis healthcheck: `redis-cli ping` → `PONG` 응답
- WS-Server healthcheck: Spring Boot Actuator `/actuator/health` 또는 커스텀 엔드포인트
- `depends_on` + `condition: service_healthy` 설정으로 종속 서비스가 준비된 후에만 기동

### 5. 브라우저에서 WebSocket 연결 성공 확인
- `http://localhost:3000` (또는 Nginx를 통한 `http://localhost:80`) 접속
- 브라우저 개발자 도구 Network 탭에서 WebSocket 연결 상태 확인
- INIT 메시지 수신 확인 (userId 자동 부여, 랜덤 좌표)

### 6. 리소스 사용량 확인
- `docker stats`로 각 컨테이너의 메모리 사용량 확인
- Spring Boot 컨테이너가 `-Xmx256m` 제한을 준수하는지 확인
- Cassandra 컨테이너가 `-Xmx512m` 제한을 준수하는지 확인
- 전체 메모리 사용량이 합리적 범위(~2.5GB 이내)인지 확인

## 테스트 기준
- [ ] `docker-compose up` 후 9개 컨테이너 모두 running 상태
- [ ] 모든 healthcheck가 healthy로 전환
- [ ] WS-Server에서 5개 인프라 서비스로의 네트워크 연결 성공
- [ ] Nginx를 통한 WebSocket 연결(101 Switching Protocols) 성공
- [ ] WebSocket 연결 60초 이상 유지
- [ ] healthcheck 기반 시작 순서 보장 동작
- [ ] 브라우저에서 INIT 메시지 수신 성공
- [ ] JVM 메모리 제한 준수 확인

## 주의사항
> - lessons.md 핵심 경고: `depends_on`만으로는 서비스 ready를 보장하지 않음. 반드시 `condition: service_healthy` + healthcheck 조합 사용
> - lessons.md 경고: Docker Compose 내부에서 `localhost` 사용 시 연결 실패. 서비스 이름(`redis-cache`, `postgresql` 등)을 hostname으로 사용해야 함
> - domain-insights.md 경고: Nginx WebSocket 설정 4종 세트(`proxy_http_version 1.1`, `Upgrade` 헤더, `Connection` 헤더, `proxy_read_timeout`) 중 하나라도 누락되면 WebSocket 동작 불가
> - design.md 제약사항: Spring Boot `-Xmx256m`, Cassandra `-Xmx512m` 메모리 제한 필수. 미설정 시 로컬 리소스 부족으로 컨테이너 OOM kill 가능
> - lessons.md 경고: 포트 충돌 시 컨테이너 시작 실패. 기존에 사용 중인 포트(5432, 6379 등) 확인 필요
