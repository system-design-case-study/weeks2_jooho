# P1-04: PostgreSQL 스키마 초기화

## 메타정보
- Phase: 1
- 의존성: P1-01, P1-02
- 예상 소요: 45분
- 난이도: 중

## 목표
> PostgreSQL 컨테이너 기동 시 `users`, `friendships` 테이블이 자동 생성되고, Spring Data JPA 엔티티 클래스가 해당 스키마와 매핑된다.

## 컨텍스트
> 사용자 정보와 친구 관계를 저장하는 관계형 저장소. erd.md "PostgreSQL" 섹션에 정확한 DDL이 정의되어 있다. 양방향 친구 관계는 애플리케이션 레벨에서 A→B, B→A 두 레코드를 동시 생성한다. architecture.md "Friend Service" 섹션 참조.

## 상세 요구사항
1. SQL 초기화 스크립트 작성 (`sql/init.sql`)
   ```sql
   CREATE TABLE users (
       id          VARCHAR PRIMARY KEY,
       nickname    VARCHAR(50)  NOT NULL,
       color_hue   INT          NOT NULL CHECK (color_hue >= 0 AND color_hue <= 360),
       initial_x   INT          NOT NULL CHECK (initial_x >= 0 AND initial_x <= 1000),
       initial_y   INT          NOT NULL CHECK (initial_y >= 0 AND initial_y <= 1000),
       created_at  TIMESTAMP    NOT NULL DEFAULT NOW()
   );

   CREATE TABLE friendships (
       id          BIGSERIAL PRIMARY KEY,
       user_id     VARCHAR   NOT NULL REFERENCES users(id) ON DELETE CASCADE,
       friend_id   VARCHAR   NOT NULL REFERENCES users(id) ON DELETE CASCADE,
       created_at  TIMESTAMP NOT NULL DEFAULT NOW(),
       CONSTRAINT uq_friendship UNIQUE (user_id, friend_id),
       CONSTRAINT chk_no_self_friend CHECK (user_id != friend_id)
   );

   CREATE INDEX idx_friendships_friend_id ON friendships(friend_id);
   ```
2. Docker Compose에 SQL 스크립트 볼륨 마운트 추가
   - `./sql/init.sql:/docker-entrypoint-initdb.d/init.sql:ro`
3. PostgreSQL 환경 변수 설정
   - `POSTGRES_DB`: nearbyfreinds
   - `POSTGRES_USER`: nearbyfreinds
   - `POSTGRES_PASSWORD`: nearbyfreinds
4. Spring Data JPA 엔티티 클래스 작성
   - `User` 엔티티: id(PK), nickname, colorHue, initialX, initialY, createdAt
   - `Friendship` 엔티티: id(PK, auto-increment), userId(FK), friendId(FK), createdAt
5. `application.yml` PostgreSQL 연결 설정
   - `spring.datasource.url`: `jdbc:postgresql://${POSTGRES_HOST:localhost}:5432/nearbyfreinds`
   - `spring.jpa.hibernate.ddl-auto: validate` (SQL 스크립트로 초기화, JPA는 검증만)

## 설정 참조
- erd.md: 정확한 DDL 및 제약 조건 정의
- erd.md: `UNIQUE(user_id, friend_id)` 제약이 복합 인덱스 자동 생성 → user_id 기준 조회에 별도 인덱스 불필요
- erd.md: `idx_friendships_friend_id` 인덱스 → 역방향 조회(나를 친구로 추가한 사용자) 지원

## 테스트 기준
- [ ] Docker Compose로 PostgreSQL 컨테이너 기동 시 `users`, `friendships` 테이블 자동 생성
- [ ] `psql`로 테이블 구조 확인 (컬럼, 제약 조건, 인덱스)
- [ ] 자기 자신 친구 추가 시도 시 CHECK 제약 위반 확인 (`chk_no_self_friend`)
- [ ] 중복 친구 관계 추가 시도 시 UNIQUE 제약 위반 확인
- [ ] Spring Boot 기동 시 `ddl-auto: validate` 통과 (엔티티와 스키마 일치)
- [ ] `User`, `Friendship` 엔티티로 기본 CRUD 동작 확인

## 주의사항
- erd.md: 양방향 친구 관계는 애플리케이션 레벨에서 처리 (A→B 추가 시 B→A도 INSERT, `@Transactional` 필수)
- 이 task에서는 엔티티 클래스와 스키마만 작성. FriendService 비즈니스 로직은 Phase 2에서 구현
- `ddl-auto: validate` 설정이므로 엔티티 필드명/타입이 DDL과 정확히 일치해야 함
- PostgreSQL Docker 이미지는 `/docker-entrypoint-initdb.d/` 내 SQL을 최초 기동 시에만 실행 → 스키마 변경 시 볼륨 삭제 후 재생성 필요
