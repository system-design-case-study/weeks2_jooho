# P2-05: 친구 관계 서비스 구현

## 메타정보
- Phase: 2
- 의존성: P1-04, P2-02
- 예상 소요: 45분
- 난이도: 중

## 목표
> PostgreSQL 기반 친구 관계 CRUD와 Redis Pub/Sub 채널 subscribe/unsubscribe 트리거를 제공하는 `FriendService`를 구현한다.

## 컨텍스트
> 친구 관계는 양방향으로 관리된다. A가 B를 친구로 추가하면 A->B, B->A 두 레코드가 동시에 생성된다. 친구 추가 시 해당 친구의 Pub/Sub 채널을 subscribe하고, 제거 시 unsubscribe한다. 이를 통해 친구의 위치 업데이트만 수신할 수 있다. (docs/architecture.md "Friend Service" 섹션, docs/erd.md "friendships" 섹션 참조)

## 상세 요구사항
1. `FriendService` 클래스 생성
2. JPA 엔티티:
   - `Friendship` 엔티티 (friendships 테이블 매핑)
   - 필드: `id` (BIGSERIAL PK), `userId` (VARCHAR FK), `friendId` (VARCHAR FK), `createdAt` (TIMESTAMP)
   - 제약조건: `UNIQUE(user_id, friend_id)`, `CHECK(user_id != friend_id)`
3. Spring Data JPA Repository: `FriendshipRepository extends JpaRepository<Friendship, Long>`
   - `List<Friendship> findByUserId(String userId)` — 친구 목록 조회
   - `Optional<Friendship> findByUserIdAndFriendId(String userId, String friendId)` — 특정 친구 관계 조회
   - `void deleteByUserIdAndFriendId(String userId, String friendId)` — 친구 관계 삭제
4. 친구 추가 (`addFriend`):
   - `@Transactional`로 양방향 레코드 동시 생성 (A->B, B->A)
   - 중복 체크: 이미 친구인 경우 예외 또는 무시
   - 저장 성공 후 → `RedisPubSubManager.subscribe("user:{friendId}", listener)` 호출
   - 상대방이 현재 접속 중이면 상대방 WS서버에서도 내 채널을 subscribe해야 함 → 이 부분은 상대방 WS서버가 친구 추가 이벤트를 수신하여 처리 (Pub/Sub 또는 REST 내부 호출)
5. 친구 제거 (`removeFriend`):
   - `@Transactional`로 양방향 레코드 동시 삭제 (A->B, B->A)
   - 삭제 성공 후 → `RedisPubSubManager.unsubscribe("user:{friendId}")` 호출
6. 친구 목록 조회 (`getFriends`):
   - `userId`로 친구 목록 조회
   - 친구 ID 목록 반환 `List<String>`
7. REST API는 이 task에서 구현하지 않음 (P3-04에서 Controller 구현)

## 설정 참조
```yaml
# application.yml
spring:
  datasource:
    url: jdbc:postgresql://${POSTGRES_HOST:postgresql}:5432/nearby_friends
    username: ${POSTGRES_USER:postgres}
    password: ${POSTGRES_PASSWORD:postgres}
  jpa:
    hibernate:
      ddl-auto: validate
    properties:
      hibernate:
        dialect: org.hibernate.dialect.PostgreSQLDialect
```

```sql
-- PostgreSQL 스키마 (P1-04에서 생성)
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

## 테스트 기준
- [ ] 친구 추가 후 DB에 양방향 레코드(A->B, B->A)가 모두 생성되는지 확인
- [ ] 친구 제거 후 DB에 양방향 레코드가 모두 삭제되는지 확인
- [ ] 친구 목록 조회 시 올바른 친구 ID 목록이 반환되는지 확인
- [ ] 중복 친구 추가 시 예외가 발생하거나 정상 처리되는지 확인
- [ ] 자기 자신을 친구로 추가 시 예외가 발생하는지 확인
- [ ] 친구 추가 시 `RedisPubSubManager.subscribe`가 호출되는지 확인 (mock 검증)
- [ ] 친구 제거 시 `RedisPubSubManager.unsubscribe`가 호출되는지 확인 (mock 검증)
- [ ] `@Transactional` 검증: 한쪽 레코드 생성 실패 시 다른 쪽도 롤백되는지 확인

## 주의사항
> - 양방향 친구 관계는 `@Transactional`로 원자성을 보장해야 한다. A->B 생성 후 B->A 생성이 실패하면 A->B도 롤백되어야 한다. (docs/erd.md "데이터 정합성" 참조)
> - 친구 추가 시 Pub/Sub subscribe 호출은 DB 트랜잭션 커밋 후 수행해야 한다. 트랜잭션 내에서 subscribe하면 롤백 시에도 subscribe 상태가 남는다. `@TransactionalEventListener(phase = AFTER_COMMIT)` 패턴 또는 트랜잭션 밖에서 호출하는 방식을 고려한다.
> - REST API의 현재 사용자 ID는 WebSocket 세션에서 가져와야 한다. 인증이 없으므로, 요청 시 사용자 ID를 어떻게 식별할지 결정 필요 (예: 쿼리 파라미터 `?userId=user-1` 또는 세션 기반).
