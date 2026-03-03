# P3-06: 접속자 목록 브로드캐스트

## 메타정보
- Phase: 3
- 의존성: P3-01, P3-03
- 예상 소요: 45분
- 난이도: 중

## 목표
> 사용자 접속/해제 시 모든 클라이언트에 USER_LIST 메시지를 전송하여, 전체 접속자 목록을 실시간으로 동기화한다.

## 컨텍스트
> 프론트엔드의 접속자 목록 UI와 친구 관리 기능에 필요한 데이터를 제공한다. design.md의 USER_LIST 메시지 타입에 해당. Nginx 뒤에서 여러 WS서버에 분산되므로, 단일 서버의 세션 맵만으로는 전체 접속자를 파악할 수 없다. Redis Pub/Sub로 접속 이벤트를 전파하여 모든 서버가 전체 접속자 목록을 유지해야 한다.

## 상세 요구사항
1. 접속 이벤트 전파 채널 설정
   - Redis Pub/Sub에 시스템 채널 `"system:user-events"` 사용
   - 이 채널은 Hash Ring과 무관하게 모든 Redis Pub/Sub 노드 중 고정 1개를 사용 (또는 별도 채널 전략)
2. 사용자 접속 시 이벤트 발행
   - `afterConnectionEstablished()`에서 접속 이벤트 publish
   - 이벤트 메시지: `{ "type": "USER_CONNECTED", "userId": "user-7", "wsServer": "ws-1" }`
3. 사용자 해제 시 이벤트 발행
   - `afterConnectionClosed()`에서 해제 이벤트 publish
   - 이벤트 메시지: `{ "type": "USER_DISCONNECTED", "userId": "user-7", "wsServer": "ws-1" }`
4. 이벤트 수신 및 전체 접속자 목록 관리
   - 각 WS서버가 `"system:user-events"` 채널 subscribe
   - 수신 시 로컬 전체 접속자 목록 갱신 (`ConcurrentHashMap<String, UserInfo>`)
   - 갱신 후 자신에게 연결된 모든 클라이언트에 USER_LIST 메시지 전송
5. USER_LIST 메시지 포맷 (design.md 참조)
   ```json
   {
     "type": "USER_LIST",
     "users": [
       {"id": "user-1", "online": true},
       {"id": "user-3", "online": true},
       {"id": "user-7", "online": true}
     ]
   }
   ```
6. 초기 접속 시 전체 목록 제공
   - 새로 접속한 사용자에게 현재 전체 접속자 목록을 INIT 메시지에 포함하거나, 별도 USER_LIST 메시지로 즉시 전송

## 설정 참조
```
# 시스템 이벤트 채널
system:user-events  # 접속/해제 이벤트 전파용 고정 채널
```

## 테스트 기준
- [ ] 사용자 접속 시 모든 클라이언트가 갱신된 USER_LIST를 수신하는지 확인
- [ ] 사용자 해제 시 모든 클라이언트가 갱신된 USER_LIST를 수신하는지 확인
- [ ] 서로 다른 WS서버에 연결된 클라이언트 간에도 접속자 목록이 동기화되는지 확인
- [ ] 새로 접속한 사용자가 기존 접속자 전체 목록을 받는지 확인
- [ ] 동시 접속/해제 이벤트 시 목록 일관성이 유지되는지 확인

## 주의사항
> - domain-insights.md: Redis Pub/Sub는 fire-and-forget — 이벤트가 유실되면 접속자 목록이 불일치할 수 있음. 주기적 동기화 메커니즘(heartbeat) 또는 접속 시 전체 목록 조회로 보완
> - lessons.md: Pub/Sub 콜백에서 블로킹 호출 금지 — USER_LIST 브로드캐스트도 별도 스레드풀에서 처리
> - domain-insights.md: 모든 WS서버가 모든 Redis 노드에 연결되어 있으므로, 시스템 채널도 이미 구독 가능한 인프라 위에서 동작
> - design.md: 최대 7~10명 동시 접속이므로 접속자 목록 크기가 작음. 브로드캐스트 빈도도 낮으므로 성능 우려 없음
