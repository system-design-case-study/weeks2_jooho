# P5-03: 인프라 상태 패널

## 메타정보
- Phase: 5
- 의존성: P4-01, P3-05
- 예상 소요: 45분
- 난이도: 중

## 목표
> WS서버별 연결 사용자, Redis 노드별 채널/구독자 수, Redis 캐시 위치 목록을 실시간으로 표시하는 인프라 상태 패널이 동작한다.

## 컨텍스트
> design.md "핵심 기능 > 3. 시스템 내부 시각화"의 "WS서버별 연결 상태, Redis 노드별 채널/구독자 표시"에 해당. P3-05의 시스템 상태 REST API를 주기적으로 호출하여 데이터를 갱신하거나, SYSTEM_STATE WebSocket 메시지를 활용한다. 분산 시스템의 인프라 상태를 한눈에 파악하는 대시보드.

## 상세 요구사항
1. WS서버 연결 상태 섹션
   - 각 WS서버(ws-1, ws-2)별 연결 사용자 목록
   - 사용자 수 카운트
   - 데이터 소스: GET /api/system/connections
   - 서버별 색상 구분
   - 표시 형태: 서버 카드 → 사용자 목록
2. Redis Pub/Sub 채널 섹션
   - 각 Redis 노드(redis-pubsub-1, redis-pubsub-2)별 구독 채널 목록
   - 채널 수, 구독자 수 카운트
   - 데이터 소스: GET /api/system/channels
   - 노드별 색상 구분 (Hash Ring 다이어그램과 동일)
3. Redis 캐시 섹션
   - 현재 캐시된 사용자 위치 목록
   - 각 항목: userId, x, y, TTL 남은 시간
   - 데이터 소스: GET /api/system/cache
4. 데이터 갱신 전략
   - 주기적 polling (5~10초 간격)
   - 또는 접속/해제 이벤트 시 갱신 (USER_LIST 메시지 수신 트리거)
   - 갱신 중 로딩 인디케이터 (선택)
5. 레이아웃
   - 3개 섹션을 수직 또는 그리드로 배치
   - 전체 시각화 레이아웃에서 Hash Ring 다이어그램(P5-01) 아래 또는 옆에 배치
   - 반응형 고려 불필요 (학습 프로젝트, 데스크탑만)
6. 주의: Nginx round-robin 때문에 API 요청이 서로 다른 WS서버로 갈 수 있음
   - 모든 서버의 상태를 수집하려면 2번 호출하거나, 백엔드에서 전체 상태를 집계하는 API 추가
   - 간단한 해결: 각 API 응답에 `serverId`가 포함되어 있으므로 여러 번 호출하여 수집

## 설정 참조
```typescript
// 주기적 API 호출
useEffect(() => {
  const interval = setInterval(async () => {
    const [connections, channels, cache] = await Promise.all([
      fetch('/api/system/connections').then(r => r.json()),
      fetch('/api/system/channels').then(r => r.json()),
      fetch('/api/system/cache').then(r => r.json()),
    ]);
    setSystemState({ connections, channels, cache });
  }, 5000);
  return () => clearInterval(interval);
}, []);
```

## 테스트 기준
- [ ] WS서버별 연결 사용자 목록이 표시되는지 확인
- [ ] Redis 노드별 구독 채널 목록이 표시되는지 확인
- [ ] Redis 캐시 위치 목록이 표시되는지 확인
- [ ] 사용자 접속/해제 후 패널이 갱신되는지 확인
- [ ] 친구 추가 후 Redis 채널 섹션에 새 구독이 반영되는지 확인
- [ ] API 응답 데이터와 패널 표시 내용이 일치하는지 확인

## 주의사항
> - P3-05 주의사항: 각 WS서버의 로컬 상태를 반환하므로, Nginx round-robin으로 인해 요청마다 다른 서버의 상태를 볼 수 있음. 전체 상태를 보려면 여러 번 호출 필요
> - lessons.md: Redis `KEYS` 대신 `SCAN` — 캐시 목록 조회 API가 `SCAN`을 사용하고 있는지 확인 (P3-05에서 처리)
> - design.md: 최대 7~10명이므로 데이터 양이 적음. 렌더링 성능 문제 없음
> - domain-insights.md: 프론트엔드 3중 렌더링(Canvas + SVG + 로그) 통합 — 이 패널의 데이터는 `useState`로 관리하며 Canvas/SVG와 독립적으로 갱신
