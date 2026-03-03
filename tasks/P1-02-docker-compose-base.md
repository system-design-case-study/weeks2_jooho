# P1-02: Docker Compose 기본 구성

## 메타정보
- Phase: 1
- 의존성: 없음
- 예상 소요: 1시간
- 난이도: 중

## 목표
> `docker-compose up`으로 9개 컨테이너(Frontend, Nginx, WS서버 x2, Redis Cache, Redis Pub/Sub x2, PostgreSQL, Cassandra)가 모두 기동되고, healthcheck를 통해 시작 순서가 보장된다.

## 컨텍스트
> 전체 인프라의 뼈대. design.md "Docker Compose 구성" 다이어그램과 architecture.md "배포 구조" 섹션이 정확한 명세. 모든 컨테이너가 단일 Docker bridge 네트워크에서 컨테이너 이름으로 DNS 해석된다. 이 task는 docker-compose.yml 파일 자체만 작성하며, 각 서비스의 상세 설정(Nginx 설정, SQL 스크립트, Dockerfile 등)은 개별 task에서 처리한다.

## 상세 요구사항
1. `docker-compose.yml` 작성 (9개 서비스)
   - `frontend`: React dev server, 포트 3000
   - `nginx`: `nginx:alpine`, 포트 80
   - `ws-server-1`: Spring Boot 커스텀 이미지, 포트 8081
   - `ws-server-2`: Spring Boot 동일 이미지, 포트 8082
   - `redis-cache`: `redis:7-alpine`, 포트 6379
   - `redis-pubsub-1`: `redis:7-alpine`, 포트 6380
   - `redis-pubsub-2`: `redis:7-alpine`, 포트 6381
   - `postgresql`: `postgres:16-alpine`, 포트 5432
   - `cassandra`: `cassandra:4`, 포트 9042
2. healthcheck 설정
   - Redis: `redis-cli ping` (interval: 5s, timeout: 3s, retries: 5)
   - PostgreSQL: `pg_isready -U ${POSTGRES_USER}` (interval: 5s, timeout: 3s, retries: 5)
   - Cassandra: `cqlsh -e 'describe cluster'` (interval: 30s, timeout: 10s, retries: 10, start_period: 60s)
3. `depends_on` + `condition: service_healthy`로 시작 순서 보장
   - PostgreSQL, Cassandra, Redis 전체 → WS서버 → Nginx → Frontend
4. 환경 변수 정의 (WS서버 공통)
   - `INSTANCE_ID`: ws-1 / ws-2
   - `REDIS_CACHE_HOST`: redis-cache
   - `REDIS_CACHE_PORT`: 6379
   - `REDIS_PUBSUB_NODES`: redis-pubsub-1:6380,redis-pubsub-2:6381
   - `POSTGRES_HOST`: postgresql
   - `CASSANDRA_HOST`: cassandra
   - `JAVA_OPTS`: -Xmx256m -XX:MaxMetaspaceSize=128m
5. Cassandra 리소스 제한: `-Xmx512m` (environment `MAX_HEAP_SIZE`, `HEAP_NEWSIZE`)
6. 단일 Docker bridge 네트워크 (`nearby-friends-net`)
7. PostgreSQL named volume (`pgdata`), Cassandra named volume (`cassandra-data`)

## 설정 참조
```yaml
# WS서버 환경 변수 (architecture.md 참조)
environment:
  INSTANCE_ID: ws-1
  REDIS_CACHE_HOST: redis-cache
  REDIS_CACHE_PORT: 6379
  REDIS_PUBSUB_NODES: redis-pubsub-1:6380,redis-pubsub-2:6381
  POSTGRES_HOST: postgresql
  CASSANDRA_HOST: cassandra
  JAVA_OPTS: -Xmx256m -XX:MaxMetaspaceSize=128m
```

## 테스트 기준
- [ ] `docker-compose config` 문법 검증 통과
- [ ] `docker-compose up -d` 실행 시 9개 컨테이너 모두 기동
- [ ] Redis 3개 인스턴스 healthcheck 통과 (각각 다른 포트)
- [ ] PostgreSQL healthcheck 통과
- [ ] Cassandra healthcheck 통과 (start_period 이후)
- [ ] WS서버는 DB/Redis healthcheck 통과 후 시작
- [ ] 컨테이너 간 DNS 해석 가능 (예: ws-server-1에서 `redis-cache` 호스트명 resolve)

## 주의사항
- lessons.md: `depends_on`은 ready를 보장하지 않음 → `condition: service_healthy` 필수
- lessons.md: Docker Compose 내부에서 `localhost` 대신 서비스 이름을 hostname으로 사용
- lessons.md: 포트 충돌 주의 → 호스트 포트 바인딩 시 기존 사용 여부 확인
- design.md: Spring Boot `-Xmx256m`, Cassandra `-Xmx512m` 메모리 제한 필수
- Nginx 설정 파일, SQL 초기화 스크립트, Dockerfile은 이 task에서 작성하지 않음 (stub/placeholder로 참조만 설정)
- 처음에는 WS서버와 Frontend를 빌드 없이 공식 이미지로 대체하거나 `build` 경로만 지정해둘 수 있음 (P1-07, P1-08에서 Dockerfile 작성)
