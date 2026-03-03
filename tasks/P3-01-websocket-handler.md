# P3-01: WebSocket 핸들러 구현

## 메타정보
- Phase: 3
- 의존성: P1-01, P1-03, P2-02, P2-03, P2-05
- 예상 소요: 1시간
- 난이도: 중

## 목표
> `LocationWebSocketHandler`가 WebSocket 연결/해제/메시지 수신을 처리하고, 접속 시 사용자 ID 자동 부여 + 랜덤 좌표 배치 + INIT 메시지 전송까지 동작한다.

## 컨텍스트
> 클라이언트와 백엔드 간 실시간 통신의 진입점. Nginx를 통해 `/ws/location` 경로로 WebSocket 연결이 이루어지며, 이 핸들러가 모든 WebSocket 이벤트를 처리한다. architecture.md "WebSocket Server" 섹션의 책임 범위에 해당하며, design.md의 "초기 접속 시" 데이터 흐름을 구현한다. Raw WebSocket(`TextWebSocketHandler`)을 사용하며 STOMP는 사용하지 않는다.

## 상세 요구사항
1. `LocationWebSocketHandler extends TextWebSocketHandler` 생성
   - `WebSocketConfigurer` 구현하여 `/ws/location` 엔드포인트 등록
   - `setAllowedOrigins("*")` 설정 (개발 환경)
2. 사용자 세션 관리
   - `ConcurrentHashMap<String, UserSession>` (userId -> WebSocketSession + 현재 좌표)
   - `UserSession` 내부 클래스/레코드: `userId`, `WebSocketSession`, `x`, `y`, `timestamp`
3. `afterConnectionEstablished()` 구현
   - 사용자 ID 자동 부여 (AtomicInteger 기반 `"user-{N}"` 형식)
   - 랜덤 좌표 배치 (0~1000 범위 XY)
   - PostgreSQL에서 친구 목록 로드 (FriendService 호출)
   - Redis 캐시에 자기 위치 저장 (LocationCacheService 호출)
   - Redis 캐시에서 친구들 현재 위치 배치 조회
   - 모든 친구의 Pub/Sub 채널 subscribe (PubSubManager 호출)
   - 자신의 채널에 위치 publish (친구들에게 접속 알림)
   - INIT 메시지 전송
4. `handleTextMessage()` 구현
   - JSON 파싱 후 `type` 필드 기반 분기
   - `LOCATION_UPDATE`: P3-02에서 상세 구현 (이 task에서는 분기 구조만)
   - `ADD_FRIEND`, `REMOVE_FRIEND`: P3-04와 연동
5. `afterConnectionClosed()` 구현
   - 세션 맵에서 제거
   - Redis 캐시에서 위치 삭제
   - 구독 중인 모든 Pub/Sub 채널 unsubscribe
   - 접속자 목록 브로드캐스트 트리거 (P3-06과 연동)
6. INIT 메시지 포맷 (design.md 참조)
   ```json
   {
     "type": "INIT",
     "userId": "user-7",
     "x": 450, "y": 230,
     "friends": [
       {"id": "user-1", "x": 100, "y": 200, "online": true}
     ],
     "searchRadius": 200
   }
   ```

## 설정 참조
```java
// WebSocketConfigurer 등록
@Override
public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
    registry.addHandler(locationWebSocketHandler(), "/ws/location")
            .setAllowedOrigins("*");
}
```

## 테스트 기준
- [ ] `/ws/location`으로 WebSocket 연결 성공
- [ ] 연결 시 사용자 ID가 자동 부여되고 세션 맵에 등록
- [ ] INIT 메시지가 올바른 포맷으로 클라이언트에 전송
- [ ] 연결 해제 시 세션 맵에서 제거되고 리소스 정리
- [ ] 메시지 타입별 분기가 올바르게 동작 (LOCATION_UPDATE, ADD_FRIEND, REMOVE_FRIEND)

## 주의사항
> - lessons.md: Connection Leak 주의 — `afterConnectionClosed`에서 반드시 모든 리소스 해제 (세션 맵 제거, Pub/Sub unsubscribe, 캐시 삭제)
> - lessons.md: CORS 설정 누락 시 WebSocket handshake에서 오류 발생 가능 — `setAllowedOrigins("*")` 필수
> - domain-insights.md: WebSocket 서버는 Stateful — 컨테이너 재시작 시 모든 연결 손실. 개발 중 재시작 사이클 인지 필요
> - architecture.md: 인스턴스 식별을 위해 `INSTANCE_ID` 환경 변수를 로그에 포함
> - design.md: 모든 사용자 ID는 `"user-{N}"` 형식, 인증 없이 자동 부여
