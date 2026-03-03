# P3-R02: unsubscribe 리스너 전체 제거 버그 수정

## 메타정보
- Phase: 3 (리뷰 수정)
- 의존성: P3-01, P2-02
- 예상 소요: 45분
- 난이도: 상

## 목표
> unsubscribe 시 해당 사용자의 리스너만 제거하고, 같은 채널을 구독 중인 다른 사용자의 리스너는 유지된다.

## 컨텍스트
> 현재 RedisPubSubManager.unsubscribe()가 `container.removeMessageListener(null, topic)`을 호출하여 해당 채널의 모든 리스너를 제거한다.
> 예: user-1과 user-2가 모두 user-3의 채널을 구독 중일 때, user-1이 연결 종료하면 user-2의 리스너도 함께 제거되어 user-2가 user-3의 위치 업데이트를 받지 못한다.

## 상세 요구사항
1. RedisPubSubManager에서 채널별 리스너를 개별 추적하는 구조 추가 (채널 → List<MessageListener>)
2. subscribeWithNodeInfo() 호출 시 반환된 리스너 목록을 저장
3. unsubscribe() 대신 unsubscribe(channel, List<MessageListener>)로 특정 리스너만 제거하는 메서드 추가
4. LocationWebSocketHandler에서 사용자별로 subscribeWithNodeInfo 반환값(리스너 목록)을 저장
5. afterConnectionClosed()에서 저장된 리스너만 제거

## 테스트 기준
- [ ] compileJava 성공
- [ ] 기존 테스트 모두 통과
- [ ] unsubscribe 시 다른 사용자의 리스너가 유지되는지 확인
