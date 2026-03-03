# P3-R01: WebSocket sendMessage 스레드 안전성 수정

## 메타정보
- Phase: 3 (리뷰 수정)
- 의존성: P3-01
- 예상 소요: 30분
- 난이도: 중

## 목표
> WebSocket 세션의 sendMessage 호출이 멀티스레드 환경에서 안전하게 동작한다.

## 컨텍스트
> Pub/Sub 리스너 스레드, broadcastUserList, broadcastPropagationLog 등 여러 스레드에서 동일 세션에 sendMessage를 호출할 수 있다.
> Spring의 WebSocketSession은 기본적으로 스레드 안전하지 않으므로 ConcurrentWebSocketSessionDecorator로 래핑해야 한다.

## 상세 요구사항
1. UserSession 생성 시 WebSocketSession을 ConcurrentWebSocketSessionDecorator로 래핑
2. 적절한 sendTimeLimit(5000ms)과 bufferSizeLimit(512KB) 설정
3. 기존 코드에서 session.sendMessage() 호출 부분이 래핑된 세션을 사용하는지 확인

## 테스트 기준
- [ ] compileJava 성공
- [ ] 기존 테스트 모두 통과
- [ ] UserSession.getSession()이 ConcurrentWebSocketSessionDecorator 인스턴스를 반환
