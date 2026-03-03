# P4-06: 접속자 목록 + 친구 관리 UI

## 메타정보
- Phase: 4
- 의존성: P4-01, P3-04, P3-06
- 예상 소요: 45분
- 난이도: 하

## 목표
> 현재 접속자 목록이 사이드 패널에 표시되고, 각 사용자 옆에 친구 추가/제거 버튼이 동작하여 REST API를 호출한다.

## 컨텍스트
> design.md "범위 > Phase 2 (확장)"의 "접속자 목록 + 친구 관리 UI"에 해당. P3-06의 USER_LIST 메시지와 P3-04의 친구 관리 REST API를 소비하는 UI 컴포넌트. 친구 추가 → Pub/Sub 구독 → 위치 수신의 전체 파이프라인을 사용자가 직접 체험하는 인터페이스.

## 상세 요구사항
1. 접속자 목록 컴포넌트 (`UserListPanel`)
   - USER_LIST 메시지 데이터를 기반으로 현재 접속자 표시
   - 각 사용자 항목: 색상 도트 아이콘 (HSL 색상), userId, 친구 상태
   - 자기 자신은 "(나)" 표시 + 별도 스타일
2. 친구 관리 버튼
   - **비친구**: "친구 추가" 버튼 표시
   - **친구**: "친구 제거" 버튼 표시
   - 버튼 클릭 시 REST API 호출
     - 추가: `POST /api/friends?userId={myId}` body: `{ "friendId": "{targetId}" }`
     - 제거: `DELETE /api/friends/{friendId}?userId={myId}`
3. API 호출 후 상태 갱신
   - 친구 추가 성공 → 로컬 친구 목록에 추가, 버튼 변경
   - 친구 제거 성공 → 로컬 친구 목록에서 제거, 버튼 변경
   - API 실패 시 에러 표시 (토스트 또는 인라인 메시지)
4. 친구 상태와 Canvas 연동
   - 친구 추가 후 해당 사용자의 위치가 Canvas에서 반투명 → 색상으로 변경
   - 친구 제거 후 해당 사용자가 Canvas에서 색상 → 반투명으로 변경
5. 레이아웃
   - Canvas 옆에 사이드 패널로 배치 (flex 레이아웃)
   - 스크롤 가능 (접속자 7~10명)

## 설정 참조
```typescript
// REST API 호출
const addFriend = async (userId: string, friendId: string) => {
  await fetch(`/api/friends?userId=${userId}`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ friendId }),
  });
};
```

## 테스트 기준
- [ ] 접속자 목록이 사이드 패널에 표시되는지 확인
- [ ] 새 사용자 접속 시 목록이 실시간으로 갱신되는지 확인
- [ ] "친구 추가" 버튼 클릭 시 REST API가 호출되고 성공 응답을 받는지 확인
- [ ] 친구 추가 후 해당 사용자의 위치가 Canvas에서 실시간으로 보이기 시작하는지 확인
- [ ] "친구 제거" 버튼 클릭 시 REST API가 호출되고 해당 사용자가 Canvas에서 반투명으로 변하는지 확인
- [ ] 자기 자신에 대해서는 친구 추가/제거 버튼이 표시되지 않는지 확인

## 주의사항
> - design.md: 인증 없음, userId를 쿼리 파라미터로 전달 (학습 프로젝트)
> - P3-04 주의사항: REST와 WS가 같은 서버로 갈 보장이 없음. 하지만 subscribe는 어떤 서버에서 하든 모든 Redis 노드에 연결되어 있으므로 문제없음
> - design.md: 최대 7~10명 동시 접속 — 접속자 목록이 길지 않으므로 가상 스크롤 불필요
