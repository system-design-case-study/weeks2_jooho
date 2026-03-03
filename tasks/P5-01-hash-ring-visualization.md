# P5-01: Hash Ring 원형 다이어그램 (D3.js + SVG)

## 메타정보
- Phase: 5
- 의존성: P4-01, P3-05, P2-01
- 예상 소요: 1시간
- 난이도: 상

## 목표
> D3.js + SVG로 Consistent Hash Ring을 원형으로 시각화하고, 각 Redis Pub/Sub 노드의 위치(가상 노드 포함)와 사용자 채널 매핑이 시각적으로 표시된다.

## 컨텍스트
> design.md "범위 > Phase 2 (확장)"의 "Hash Ring 원형 다이어그램 시각화 (D3.js + SVG)"에 해당. architecture.md "Frontend > 렌더링 파이프라인"의 "D3.js + SVG: Hash Ring 시각화" 명세. GET /api/system/hash-ring API(P3-05)에서 데이터를 가져와 시각화한다. Hash Ring 계산은 백엔드에서만 수행하고, 프론트엔드는 순수 시각화만 담당한다.

## 상세 요구사항
1. Hash Ring 원형 다이어그램 컴포넌트 (`HashRingDiagram`)
   - D3.js `d3.arc()`로 원호 생성
   - SVG 엘리먼트 내부에 렌더링
   - React 컴포넌트에서 D3를 계산 엔진으로 사용 + JSX로 SVG 렌더링 (D3 직접 DOM 조작 대신)
2. 노드 시각화
   - 물리 노드: 큰 원 + 노드 이름 라벨 (redis-pubsub-1, redis-pubsub-2)
   - 가상 노드: 작은 점으로 표시 (물리 노드와 같은 색상)
   - 각 물리 노드별 색상 구분 (예: redis-pubsub-1 = 파랑, redis-pubsub-2 = 주황)
   - 호버 시 가상 노드 범위 하이라이트 (가상 노드 450개 전부 표시하면 시각적으로 지저분)
3. 사용자 채널 매핑 표시
   - 활성 사용자 채널(`user:1`, `user:2` 등)이 원형 위 해당 위치에 표시
   - 채널에서 매핑된 Redis 노드로 선 또는 화살표 연결
   - 내 채널 하이라이트 (다른 색상 또는 굵은 테두리)
4. 데이터 소스
   - GET /api/system/hash-ring API 호출로 데이터 조회
   - 또는 SYSTEM_STATE WebSocket 메시지에서 데이터 수신
   - 주기적 갱신 (접속자 변경 시) 또는 이벤트 기반 갱신
5. 인터랙션
   - 노드 호버: 해당 노드에 매핑된 채널 하이라이트
   - 채널 호버: 해당 채널이 매핑된 노드 하이라이트 + 연결선 강조
6. SVG 데이터는 `useState`로 관리 (Canvas와 달리 React 리렌더링 활용)

## 설정 참조
```typescript
// D3.js 원형 레이아웃 기본 패턴
const radius = 200;
const nodeAngle = (position: number) => (position / 0xFFFFFFFF) * 2 * Math.PI;
const nodeX = (position: number) => radius * Math.cos(nodeAngle(position));
const nodeY = (position: number) => radius * Math.sin(nodeAngle(position));
```

## 테스트 기준
- [ ] Hash Ring 원형 다이어그램이 SVG로 렌더링되는지 확인
- [ ] 물리 노드 2개가 서로 다른 색상으로 표시되는지 확인
- [ ] 가상 노드가 물리 노드와 같은 색상의 작은 점으로 표시되는지 확인
- [ ] 활성 사용자 채널이 원형 위에 표시되는지 확인
- [ ] 채널과 매핑된 Redis 노드 간 연결선이 표시되는지 확인
- [ ] 내 채널이 다른 채널과 시각적으로 구분되는지 확인
- [ ] 노드 호버 시 관련 채널이 하이라이트되는지 확인
- [ ] API 데이터와 시각화가 일치하는지 확인

## 주의사항
> - domain-insights.md: Hash Ring 계산은 백엔드에서만 수행, 프론트엔드는 API/WebSocket으로 받은 계산 결과를 시각화만 담당. MurmurHash3의 Java/JS 구현 차이로 인한 불일치 방지
> - domain-insights.md: 가상 노드 450개(150 x 3)를 모두 개별 표시하면 시각적으로 지저분 — 그룹으로 표시하거나 호버 시 범위 하이라이트
> - lessons.md: D3.js 성능 — SVG 요소 수백 개는 문제없음. 가독성이 성능보다 중요한 이슈
> - architecture.md: Frontend 상태 관리 — SVG 데이터는 `useState`로 관리 (Canvas의 `useRef`와 구분)
> - domain-insights.md: 시각화 목적의 가상 노드 수(150개)와 실제 운영 환경의 적정 수가 다를 수 있음
