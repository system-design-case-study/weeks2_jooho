# P4-01: WebSocket 클라이언트 + 상태 관리

## 메타정보
- Phase: 4
- 의존성: P3-01
- 예상 소요: 1시간
- 난이도: 중

## 목표
> React 앱에서 native WebSocket 클라이언트가 `/ws/location`에 연결되고, 자동 재연결(지수 백오프)이 동작하며, 메시지 타입별로 상태를 분배한다.

## 컨텍스트
> 프론트엔드의 모든 실시간 데이터 흐름의 출발점. architecture.md "Frontend" 섹션의 "native WebSocket 클라이언트" 명세에 해당. 단일 WebSocket 연결에서 수신한 메시지를 Canvas(위치 맵), SVG(Hash Ring), 로그 패널에 분배해야 한다. Canvas 데이터는 `useRef`, SVG/로그 데이터는 `useState`로 관리하는 이중 상태 패턴을 적용한다.

## 상세 요구사항
1. WebSocket 연결 관리 Hook (`useWebSocket`)
   - native `WebSocket` 사용 (`ws://localhost/ws/location`)
   - 연결 상태 관리: `CONNECTING`, `CONNECTED`, `DISCONNECTED`, `RECONNECTING`
   - 자동 재연결 (지수 백오프)
     - 초기 딜레이: 1초
     - 최대 딜레이: 30초
     - 재시도 계수: 2배씩 증가
     - 재연결 성공 시 딜레이 리셋
2. 메시지 타입별 분기
   - `INIT`: 사용자 ID, 초기 좌표, 친구 목록, 검색 반경 설정
   - `FRIEND_LOCATION`: 친구 위치 갱신 (Canvas 데이터에 반영)
   - `USER_LIST`: 접속자 목록 갱신
   - `PROPAGATION_LOG`: 전파 경로 로그 추가
   - `SYSTEM_STATE`: 시스템 상태 갱신 (Hash Ring 등)
3. 상태 관리 구조
   - Canvas용 상태 (`useRef` — React 리렌더링 트리거하지 않음):
     - `myPosition: {x, y}`
     - `friends: Map<string, {x, y, inRange, distance}>`
     - `allUsers: Map<string, {x, y}>` (비친구 포함)
   - React UI용 상태 (`useState`):
     - `userId: string`
     - `connectionStatus: string`
     - `userList: UserInfo[]`
     - `propagationLogs: PropagationLog[]`
     - `searchRadius: number`
4. 메시지 전송 함수
   - `sendLocationUpdate(x, y)` — LOCATION_UPDATE 메시지 전송
   - `sendAddFriend(friendId)` — ADD_FRIEND 메시지 전송
   - `sendRemoveFriend(friendId)` — REMOVE_FRIEND 메시지 전송
5. React Context Provider
   - `WebSocketProvider`로 하위 컴포넌트에 WebSocket 상태와 메서드 제공
   - 또는 Zustand/Jotai 같은 경량 상태 관리 (선택)

## 설정 참조
```typescript
// WebSocket URL (Nginx 프록시 경유)
const WS_URL = `ws://${window.location.host}/ws/location`;
```

## 테스트 기준
- [ ] 페이지 로드 시 WebSocket 연결이 자동으로 수립되는지 확인
- [ ] INIT 메시지 수신 시 userId, 초기 좌표, 친구 목록이 상태에 설정되는지 확인
- [ ] FRIEND_LOCATION 수신 시 Canvas용 ref 데이터가 갱신되는지 확인
- [ ] USER_LIST 수신 시 접속자 목록 상태가 갱신되는지 확인
- [ ] 연결 끊김 시 자동 재연결이 지수 백오프로 동작하는지 확인
- [ ] 재연결 성공 시 INIT 메시지를 다시 수신하는지 확인
- [ ] sendLocationUpdate 호출 시 올바른 JSON 메시지가 전송되는지 확인

## 주의사항
> - domain-insights.md: Canvas 데이터는 `useRef`, SVG/로그 데이터는 `useState`로 관리하는 이중 상태 패턴 필요. Canvas 데이터를 `useState`로 관리하면 매 프레임 리렌더링 발생
> - lessons.md: 재연결 미처리 시 네트워크 불안정 시 클라이언트가 연결 끊김을 인지하지 못함 — 지수 백오프 재연결 로직 필수
> - domain-insights.md: WebSocket 서버는 Stateful — 서버 재시작 시 클라이언트 재연결 필수, 재연결 후 INIT 메시지로 상태 복구
> - design.md: native WebSocket 사용, 추가 라이브러리 불필요
