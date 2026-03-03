# P1-05: Cassandra 스키마 초기화

## 메타정보
- Phase: 1
- 의존성: P1-01, P1-02
- 예상 소요: 45분
- 난이도: 중

## 목표
> Cassandra 컨테이너 기동 시 `nearby_friends` 키스페이스와 `location_history` 테이블이 생성되고, Spring Data Cassandra 엔티티 클래스가 매핑된다.

## 컨텍스트
> 위치 이력을 저장하는 write-heavy 워크로드 전용 저장소. erd.md "Cassandra" 섹션에 CQL DDL이 정의되어 있다. 파티션 키는 `user_id`, 클러스터링 키는 `timestamp DESC`로 한 사용자의 이력을 시간순으로 조회한다. design.md에서 Cassandra는 Alex Xu 책의 설계를 재현하기 위해 선택되었다.

## 상세 요구사항
1. CQL 초기화 스크립트 작성 (`cassandra/init.cql`)
   ```cql
   CREATE KEYSPACE IF NOT EXISTS nearby_friends
       WITH replication = {'class': 'SimpleStrategy', 'replication_factor': 1};

   USE nearby_friends;

   CREATE TABLE IF NOT EXISTS location_history (
       user_id   TEXT,
       timestamp TIMESTAMP,
       x         INT,
       y         INT,
       ws_server TEXT,
       PRIMARY KEY (user_id, timestamp)
   ) WITH CLUSTERING ORDER BY (timestamp DESC);
   ```
2. Cassandra 초기화 스크립트 실행 방법
   - Docker Compose에서 Cassandra healthcheck 통과 후 실행하는 init 컨테이너, 또는
   - `docker-entrypoint-initdb.d` 대응 스크립트 (Cassandra 이미지는 PostgreSQL처럼 자동 실행 미지원)
   - 방법 1: `cassandra/init.sh` 셸 스크립트 작성 → healthcheck 후 `cqlsh -f /scripts/init.cql` 실행
   - 방법 2: Spring Data Cassandra `schema-action: CREATE_IF_NOT_EXISTS` 활용
   - 권장: Spring Data Cassandra의 `schema-action`으로 테이블 자동 생성 + 키스페이스는 `init.cql` 스크립트로 생성
3. Docker Compose에 CQL 스크립트 볼륨 마운트 추가 (init 스크립트 방식 선택 시)
4. Spring Data Cassandra 엔티티 클래스 작성
   - `LocationHistory` 엔티티
   - `@Table("location_history")`
   - `@PrimaryKey` 복합키 클래스 또는 `@PrimaryKeyColumn` 사용
   - `userId`: 파티션 키, `timestamp`: 클러스터링 키 (DESC)
   - `x`, `y`, `wsServer` 필드
5. `application.yml` Cassandra 연결 설정
   - `spring.cassandra.contact-points`: `${CASSANDRA_HOST:localhost}`
   - `spring.cassandra.port`: 9042
   - `spring.cassandra.keyspace-name`: nearby_friends
   - `spring.cassandra.local-datacenter`: datacenter1
   - `spring.cassandra.schema-action`: CREATE_IF_NOT_EXISTS

## 설정 참조
- erd.md: CQL DDL 정의 및 키 구조 설명
- design.md: Cassandra `-Xmx512m` 메모리 제한
- architecture.md: `Location History Service` — 비동기 저장, write-heavy 워크로드

## 테스트 기준
- [ ] Cassandra 컨테이너 기동 후 `nearby_friends` 키스페이스 존재 확인
- [ ] `location_history` 테이블 구조 확인 (`DESCRIBE TABLE nearby_friends.location_history`)
- [ ] `cqlsh`로 테스트 데이터 INSERT/SELECT 성공
- [ ] 클러스터링 키 DESC 정렬 확인 (최신 데이터가 먼저 조회)
- [ ] Spring Boot 기동 시 Cassandra 연결 성공

## 주의사항
- lessons.md: Cassandra healthcheck는 `start_period`를 충분히 길게 설정 (60s 이상) — Cassandra 초기 기동이 매우 느림
- design.md: Cassandra `-Xmx512m` 메모리 제한 필수 (Docker 컨테이너 리소스 절약)
- Cassandra Docker 이미지는 PostgreSQL과 달리 `/docker-entrypoint-initdb.d/`를 지원하지 않음 → 별도 초기화 전략 필요
- erd.md: append-only 패턴, 업데이트/삭제 없음
- `SimpleStrategy`와 `replication_factor: 1`은 로컬 개발 환경 전용 설정
