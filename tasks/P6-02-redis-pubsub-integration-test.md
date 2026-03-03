# P6-02: Redis Pub/Sub 통합 테스트

## 메타정보
- Phase: 6
- 의존성: P2-02, P1-06
- 예상 소요: 1시간
- 난이도: 상

## 목표
> 실제 Redis 인스턴스를 사용하여 Redis Pub/Sub Manager의 publish/subscribe 동작, 다중 인스턴스 라우팅, 채널 관리를 통합 테스트로 검증한다.

## 컨텍스트
> Redis Pub/Sub는 WebSocket 서버 간 실시간 위치 전파의 핵심 채널이다. 이 프로젝트에서 가장 높은 리스크로 식별된 "다중 Redis Pub/Sub 인스턴스 구독 관리"의 정확성을 실제 Redis 환경에서 검증한다. architecture.md에 따르면 Publish는 Hash Ring으로 1개 노드에만, Subscribe는 모든 WS서버가 모든 노드에 연결하는 비대칭 구조다. Spring Boot의 Redis auto-configuration을 비활성화하고 수동 Bean 구성한 `LettuceConnectionFactory`, `RedisMessageListenerContainer`가 올바르게 동작하는지 확인한다.

## 상세 요구사항

### 1. 테스트 인프라 구성
- Testcontainers로 Redis 인스턴스 2개 기동 (포트 동적 할당)
- 또는 Docker Compose 기반 테스트 프로파일로 Redis 2개 사용
- 각 Redis 인스턴스에 대한 `LettuceConnectionFactory`가 독립적으로 생성되는지 확인

### 2. Publish → Subscribe 메시지 수신 확인
- 채널 `user:test-1`에 subscribe 후 동일 채널에 publish
- 메시지 내용(JSON: userId, x, y, timestamp, path)이 정확히 수신되는지 검증
- 발행 후 수신까지의 지연 시간이 합리적 범위(1초 이내)인지 확인

### 3. 다중 Redis 인스턴스 간 Hash Ring 기반 라우팅 확인
- Hash Ring으로 `user:1`이 redis-pubsub-1에, `user:2`가 redis-pubsub-2에 매핑되는 시나리오 구성
- `user:1` 채널에 publish → redis-pubsub-1에서만 메시지가 발행되는지 확인
- subscribe 측은 모든 Redis 인스턴스를 구독하고 있으므로 정상 수신 확인
- 다른 Redis 인스턴스에는 해당 메시지가 발행되지 않았는지 확인

### 4. 채널 Subscribe/Unsubscribe 동작 확인
- subscribe 후 메시지 수신 → unsubscribe 후 메시지 미수신 검증
- 동일 채널에 대한 중복 subscribe 시 메시지 중복 수신 여부 확인
- unsubscribe 후 재subscribe 시 정상 동작 확인

### 5. Lettuce 콜백 비블로킹 확인
- Pub/Sub 메시지 수신 콜백이 Lettuce EventLoop 스레드가 아닌 별도 스레드풀에서 실행되는지 확인
- 콜백 내에서 Thread.currentThread().getName()으로 스레드 확인
- 콜백 처리 중 다른 메시지가 정상적으로 수신되는지 확인 (블로킹 미발생)

### 6. 메시지 포맷 검증
- 전파 경로 메타데이터(`path` 배열)가 포함된 메시지가 정확히 직렬화/역직렬화되는지 확인
- StringRedisSerializer 사용 시 JSON 문자열이 `redis-cli`로 확인 가능한 형태인지 검증

## 테스트 기준
- [ ] Testcontainers 또는 Docker 기반 Redis 2개 인스턴스 기동 성공
- [ ] publish → subscribe 메시지 정확 수신 (내용 일치)
- [ ] Hash Ring 기반 라우팅: 올바른 Redis 노드에만 publish 확인
- [ ] subscribe/unsubscribe 라이프사이클 정상 동작
- [ ] Lettuce 콜백이 별도 스레드풀에서 실행됨 확인
- [ ] JSON 메시지 직렬화/역직렬화 정확성 확인
- [ ] 모든 통합 테스트 통과

## 주의사항
> - domain-insights.md 핵심 경고: "Pub/Sub 콜백에서 블로킹 호출"이 "위치 업데이트가 간헐적으로 안 오는 가장 흔한 원인"이 됨. 콜백 스레드 검증을 반드시 포함할 것
> - lessons.md 경고: Spring Boot의 `spring.data.redis.*` auto-configuration과 수동 Bean 구성이 충돌할 수 있음. 테스트 프로파일에서 auto-configuration 비활성화 확인 필요
> - lessons.md 경고: Pub/Sub 모드에 진입한 Redis 연결은 일반 명령어를 실행할 수 없음. 구독 전용 연결이 분리되어 있는지 확인
> - 기본 `JdkSerializationRedisSerializer` 사용 금지 — `StringRedisSerializer` 설정 확인 필수
> - Redis Pub/Sub는 fire-and-forget 특성이므로, 구독자가 없는 채널에 발행한 메시지는 유실됨. 테스트에서 subscribe 완료 후 publish하는 순서를 보장할 것
