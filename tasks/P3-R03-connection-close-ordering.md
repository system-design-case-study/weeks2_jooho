# P3-R03: 연결 종료 이벤트 순서 수정

## 메타정보
- Phase: 3 (리뷰 수정)
- 의존성: P3-01, P3-06
- 예상 소요: 15분
- 난이도: 하

## 목표
> afterConnectionClosed()에서 세션을 먼저 제거한 후 disconnect 이벤트를 발행하여, 이벤트 수신 측에서 이미 종료된 세션에 메시지 전송을 시도하지 않는다.

## 컨텍스트
> 현재 순서: publishUserDisconnected → sessions.remove → 리소스 정리
> 문제: disconnect 이벤트 수신 시 broadcastUserList가 호출되고, 아직 sessions에 남아있는 종료된 세션에 sendMessage를 시도하여 IOException 발생 가능.

## 상세 요구사항
1. afterConnectionClosed()에서 sessions.remove()를 publishUserDisconnected() 이전으로 이동
2. Pub/Sub unsubscribe도 disconnect 이벤트 이전에 수행

## 테스트 기준
- [ ] compileJava 성공
- [ ] 기존 테스트 모두 통과
- [ ] afterConnectionClosed 내 실행 순서: (1) sessions.remove → (2) Pub/Sub unsubscribe → (3) Redis 캐시 삭제 → (4) publishUserDisconnected
