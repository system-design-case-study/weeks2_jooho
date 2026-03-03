# P3-04: 친구 관리 REST API

## 메타정보
- Phase: 3
- 의존성: P3-01, P2-05
- 예상 소요: 45분
- 난이도: 중

## 목표
> 친구 추가/제거/조회 REST API가 동작하고, 친구 추가 시 해당 친구의 Pub/Sub 채널이 subscribe되며 제거 시 unsubscribe된다.

## 컨텍스트
> 친구 관계 CRUD와 Pub/Sub 채널 구독을 연결하는 서비스. architecture.md "Friend Service" 섹션과 design.md "데이터 흐름 > 친구 추가/제거 시" 다이어그램이 명세. REST API는 Nginx `/api/**` 경로로 라우팅되며, WS서버가 직접 처리한다. WebSocket 세션에서 사용자 ID를 확인하여 REST와 WS를 연동해야 한다.

## 상세 요구사항
1. `FriendController` 생성
   - `POST /api/friends` — 친구 추가
     - Request Body: `{ "friendId": "user-5" }`
     - 사용자 ID는 요청 헤더 또는 쿼리 파라미터로 전달 (WebSocket 세션에서 직접 가져올 수 없으므로)
   - `DELETE /api/friends/{friendId}` — 친구 제거
   - `GET /api/friends` — 내 친구 목록 조회
2. `FriendService` 구현
   - **친구 추가 시**:
     1. PostgreSQL `friendships` 테이블에 관계 저장
     2. Hash Ring으로 해당 친구 채널(`user:{friendId}`)의 Redis 노드 결정
     3. 해당 Redis 노드에서 친구 채널 subscribe (PubSubManager 호출)
   - **친구 제거 시**:
     1. PostgreSQL `friendships` 테이블에서 관계 삭제
     2. 해당 Redis 노드에서 친구 채널 unsubscribe (PubSubManager 호출)
   - **친구 조회**:
     1. PostgreSQL에서 친구 ID 목록 반환
     2. Redis 캐시에서 친구들의 현재 위치/online 상태 조회하여 enrichment
3. REST와 WebSocket 연동
   - REST API 호출 시 사용자 ID를 식별할 방법 필요
   - 방법 1: 쿼리 파라미터 `?userId=user-7` (단순, 보안 불필요)
   - 방법 2: WebSocket 연결 시 발급한 토큰을 HTTP 헤더로 전달
   - 학습 프로젝트이므로 방법 1 채택 (인증 없음)
4. 응답 포맷
   - 친구 추가: `201 Created` + `{ "userId": "user-7", "friendId": "user-5" }`
   - 친구 제거: `204 No Content`
   - 친구 목록: `200 OK` + `[{ "id": "user-5", "online": true, "x": 300, "y": 400 }]`

## 설정 참조
```sql
-- PostgreSQL friendships 테이블 (P1 task에서 DDL 생성)
CREATE TABLE friendships (
    user_id VARCHAR(20) NOT NULL,
    friend_id VARCHAR(20) NOT NULL,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (user_id, friend_id)
);
```

## 테스트 기준
- [ ] POST /api/friends로 친구 추가 시 201 응답 + PostgreSQL에 저장 확인
- [ ] DELETE /api/friends/{friendId}로 친구 제거 시 204 응답 + PostgreSQL에서 삭제 확인
- [ ] GET /api/friends로 친구 목록 조회 시 200 응답 + 올바른 데이터 반환
- [ ] 친구 추가 시 PubSubManager.subscribe() 호출 확인
- [ ] 친구 제거 시 PubSubManager.unsubscribe() 호출 확인
- [ ] 존재하지 않는 사용자에 대한 친구 추가 시 적절한 에러 응답

## 주의사항
> - architecture.md: Friend Service는 PostgreSQL(Spring Data JPA)과 Redis Pub/Sub Manager에 의존
> - design.md: 인증 없음, 접속 즉시 사용자 ID 자동 부여
> - domain-insights.md: REST API는 Nginx `/api/**`를 통해 round-robin으로 WS서버에 라우팅됨. 특정 사용자의 REST 요청이 해당 사용자의 WebSocket이 연결된 서버와 다른 서버로 갈 수 있음 — Pub/Sub subscribe/unsubscribe가 올바른 서버에서 실행되어야 함
> - lessons.md: Sticky Session이 없으므로 REST와 WS가 같은 서버로 갈 보장이 없음. subscribe/unsubscribe는 모든 WS서버에서 해당 채널을 관리해야 하므로, 어떤 서버에서 REST를 받든 해당 서버에서 subscribe하면 됨 (모든 서버가 모든 Redis 노드에 연결)
