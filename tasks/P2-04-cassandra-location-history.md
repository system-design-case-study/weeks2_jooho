# P2-04: 위치 이력 저장 서비스 구현 (Cassandra)

## 메타정보
- Phase: 2
- 의존성: P1-05
- 예상 소요: 45분
- 난이도: 중

## 목표
> 사용자의 위치 이력을 Cassandra에 비동기로 저장하는 `LocationHistoryService`를 구현한다.

## 컨텍스트
> Cassandra는 write-heavy 워크로드에 최적화된 저장소로, 사용자 위치가 변경될 때마다 이력을 append-only로 저장한다. 파티션 키 `user_id` + 클러스터링 키 `timestamp DESC`로 사용자별 최신 이력부터 효율적으로 조회할 수 있다. 비동기 저장이므로 위치 업데이트 응답 시간에 영향을 주지 않아야 한다. (docs/architecture.md "Location History Service" 섹션, docs/erd.md "Cassandra" 섹션 참조)

## 상세 요구사항
1. `LocationHistoryService` 클래스 생성
2. Cassandra 엔티티 정의: `LocationHistory`
   - `user_id` (TEXT) — Partition Key
   - `timestamp` (TIMESTAMP) — Clustering Key (DESC)
   - `x` (INT)
   - `y` (INT)
   - `ws_server` (TEXT) — 수신한 WebSocket 서버 ID (전파 경로 추적용)
3. Spring Data Cassandra Repository 사용: `LocationHistoryRepository extends CassandraRepository<LocationHistory, ...>`
4. 비동기 저장 구현:
   - `@Async` 어노테이션 또는 `AsyncCassandraOperations` 사용
   - `CompletableFuture<Void>` 반환
   - 비동기 실행을 위한 `@EnableAsync` + 별도 `TaskExecutor` Bean 구성
5. 주요 메서드:
   - `saveLocation(String userId, int x, int y, String wsServer)` — 비동기 저장, timestamp는 서버 시각 사용
   - `getHistory(String userId, int limit)` — 사용자별 최신 이력 조회 (timestamp DESC), 기본 limit 50
6. Keyspace: `nearby_friends`
7. 스키마 자동 생성: Spring Data Cassandra `schema-action: CREATE_IF_NOT_EXISTS`

## 설정 참조
```yaml
# application.yml
spring:
  cassandra:
    contact-points: ${CASSANDRA_HOST:cassandra}
    port: 9042
    keyspace-name: nearby_friends
    schema-action: CREATE_IF_NOT_EXISTS
    local-datacenter: datacenter1
```

```cql
-- Cassandra 스키마 (자동 생성 또는 초기화 스크립트)
CREATE KEYSPACE IF NOT EXISTS nearby_friends
    WITH replication = {'class': 'SimpleStrategy', 'replication_factor': 1};

CREATE TABLE IF NOT EXISTS location_history (
    user_id   TEXT,
    timestamp TIMESTAMP,
    x         INT,
    y         INT,
    ws_server TEXT,
    PRIMARY KEY (user_id, timestamp)
) WITH CLUSTERING ORDER BY (timestamp DESC);
```

## 테스트 기준
- [ ] 위치 저장 후 `getHistory(userId, 10)` 호출 시 저장한 데이터가 조회되는지 확인
- [ ] 여러 위치 저장 후 `timestamp DESC` 정렬로 조회되는지 확인 (최신 데이터가 첫 번째)
- [ ] `ws_server` 필드에 올바른 인스턴스 ID가 저장되는지 확인
- [ ] 비동기 저장이 호출 스레드를 블로킹하지 않는지 확인 (저장 호출 후 즉시 반환)
- [ ] `limit` 파라미터가 반환 결과 수를 제한하는지 확인

## 주의사항
> - Cassandra는 Docker 컨테이너에서 `-Xmx512m` 메모리 제한이 있다. 과도한 배치 쓰기를 피하고 건별 비동기 저장을 사용한다. (docs/design.md "제약사항" 참조)
> - Cassandra 이력은 append-only 패턴이다. 업데이트/삭제 없이 INSERT만 수행한다. (docs/erd.md "데이터 정합성" 참조)
> - Cassandra healthcheck가 통과한 후에야 Spring Boot가 시작되어야 한다. `depends_on` + `condition: service_healthy`로 보장한다. (docs/lessons.md "Docker Compose" 참조)
> - Keyspace 생성은 Docker 초기화 스크립트 또는 Spring Data Cassandra `schema-action`으로 처리한다. 수동 CQL 실행 불필요.
