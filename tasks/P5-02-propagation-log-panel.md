# P5-02: 메시지 전파 경로 실시간 로그

## 메타정보
- Phase: 5
- 의존성: P4-01, P3-03
- 예상 소요: 45분
- 난이도: 중

## 목표
> PROPAGATION_LOG 메시지를 실시간 로그 스트림으로 표시하여, 위치 업데이트가 어떤 경로(누가 → 어떤 WS서버 → 어떤 Redis 노드 → 어떤 WS서버 → 누구)를 거쳐 전파되었는지 시각적으로 확인할 수 있다.

## 컨텍스트
> design.md "핵심 기능 > 3. 시스템 내부 시각화"의 "메시지 전파 경로 실시간 로그"에 해당. P3-03에서 생성하는 PROPAGATION_LOG 메시지를 소비하는 프론트엔드 컴포넌트. 분산 시스템의 메시지 전파 과정을 눈으로 확인하는 학습 도구.

## 상세 요구사항
1. 전파 경로 로그 컴포넌트 (`PropagationLogPanel`)
   - PROPAGATION_LOG WebSocket 메시지 수신 시 로그 항목 추가
   - 시간순 스크롤 로그 (최신이 위 또는 아래 — 선택)
   - 자동 스크롤 (새 로그 도착 시 최신 항목으로 스크롤)
2. 로그 항목 렌더링
   - 경로 시각화: `user-3 → ws-2 → redis-pubsub-1 → ws-1 → user-1`
   - 각 단계를 화살표로 연결
   - 노드 타입별 색상 구분 (사용자=파랑, WS서버=초록, Redis=주황)
   - 거리 계산 결과 표시: `distance: 142, inRange: true`
   - 반경 필터링 결과: `sent: true/false`
3. 로그 항목 데이터 (P3-03 PROPAGATION_LOG 포맷)
   ```json
   {
     "type": "PROPAGATION_LOG",
     "sourceUser": "user-3",
     "wsServer": "ws-2",
     "redisNode": "redis-pubsub-1",
     "channel": "user:3",
     "subscribers": [
       {"userId": "user-1", "wsServer": "ws-1", "distance": 142, "inRange": true, "sent": true},
       {"userId": "user-5", "wsServer": "ws-2", "distance": 890, "inRange": false, "sent": false}
     ]
   }
   ```
4. 로그 관리
   - 최대 로그 항목 수 제한 (예: 100개, FIFO)
   - 로그 데이터는 `useState`로 관리 (React 리렌더링 활용)
   - 고빈도 메시지 시 렌더링 성능 고려 — 배치 업데이트 또는 디바운싱
5. 필터 기능 (선택)
   - 특정 사용자의 전파 로그만 보기
   - inRange만 보기 / 전체 보기 토글
6. 레이아웃
   - Canvas, Hash Ring 다이어그램과 함께 배치 (하단 또는 우측 패널)
   - 높이 제한 + 스크롤

## 설정 참조
```typescript
// 로그 항목 최대 수 제한
const MAX_LOG_ENTRIES = 100;

// 새 로그 추가 시
setLogs(prev => {
  const next = [newLog, ...prev];
  return next.slice(0, MAX_LOG_ENTRIES);
});
```

## 테스트 기준
- [ ] PROPAGATION_LOG 수신 시 로그 패널에 새 항목이 추가되는지 확인
- [ ] 전파 경로(사용자 → WS서버 → Redis → WS서버 → 사용자)가 시각적으로 표시되는지 확인
- [ ] 거리 계산 결과와 반경 필터링 결과가 표시되는지 확인
- [ ] 시간순으로 정렬되는지 확인
- [ ] 자동 스크롤이 동작하는지 확인
- [ ] 로그 항목이 100개 초과 시 오래된 항목이 제거되는지 확인
- [ ] 고빈도 위치 업데이트 시에도 UI가 버벅거리지 않는지 확인

## 주의사항
> - domain-insights.md: 전파 경로 추적은 시각화의 핵심이자 가장 어려운 부분 — 데이터가 P3-02, P3-03에서 올바르게 생성되어야 이 컴포넌트가 의미있게 동작
> - domain-insights.md: 프론트엔드 동시 렌더링 복잡도 — 로그 패널은 WebSocket 메시지마다 업데이트되므로, `useState` 업데이트 빈도가 높을 수 있음. 배치 업데이트 활용
> - design.md: 7~10명 동시 접속에서 각 사용자가 초당 10~20회 위치 업데이트를 보내면, 초당 최대 200개의 PROPAGATION_LOG가 발생할 수 있음. 실제로는 드래그 시에만 발생하므로 훨씬 적음
