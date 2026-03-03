# P2-02: Redis Pub/Sub Manager 구현

## 메타정보
- Phase: 2
- 의존성: P1-06, P2-01
- 예상 소요: 1시간
- 난이도: 상

## 목표
> 모든 WS서버가 모든 Redis Pub/Sub 노드에 연결하여 subscribe하고, Consistent Hash Ring으로 결정된 노드에만 publish하는 Pub/Sub Manager를 구현한다.

## 컨텍스트
> Redis Pub/Sub Manager는 프로젝트의 핵심 복잡성을 담당한다. Publish는 Hash Ring으로 대상 노드 1개만 결정하지만, Subscribe는 모든 WS서버가 모든 Redis Pub/Sub 노드에 연결해야 한다. 이 비대칭성이 프로젝트의 핵심 학습 포인트이다. (docs/architecture.md "Redis Pub/Sub Manager" 섹션, docs/domain-insights.md "Redis Pub/Sub 다중 인스턴스의 구독 관리" 참조)

## 상세 요구사항
1. `RedisPubSubManager` 클래스 생성
2. Redis Pub/Sub 노드별로 개별 `LettuceConnectionFactory` 생성
   - 환경 변수 `REDIS_PUBSUB_NODES`를 파싱하여 `host:port` 쌍으로 분리
   - 각 노드에 대해 `LettuceConnectionFactory` + `StringRedisTemplate` 생성
3. Redis 노드별로 개별 `RedisMessageListenerContainer` 생성
   - 리스너를 노드별로 분리하여 메시지가 어떤 Redis 노드에서 왔는지 식별 가능하게 구성
   - 각 `RedisMessageListenerContainer`에 별도 `TaskExecutor` 설정 (Lettuce EventLoop 블로킹 방지)
4. 주요 메서드:
   - `publish(String channel, LocationMessage message)` — Consistent Hash Ring으로 대상 Redis 노드 결정 후 해당 노드의 `StringRedisTemplate`으로 publish
   - `subscribe(String channel, MessageListener listener)` — 모든 Redis Pub/Sub 노드에서 해당 채널 subscribe (비대칭성 핵심)
   - `unsubscribe(String channel)` — 모든 Redis Pub/Sub 노드에서 해당 채널 unsubscribe
5. Pub/Sub 콜백 처리:
   - Lettuce EventLoop 스레드에서 직접 블로킹 호출 금지
   - 수신 즉시 별도 `ThreadPoolTaskExecutor`로 위임
   - 스레드풀 설정: `corePoolSize=4`, `maxPoolSize=8`, `queueCapacity=100`
6. Spring Boot Redis auto-configuration 비활성화
   - `@SpringBootApplication(exclude = {RedisAutoConfiguration.class, RedisRepositoriesAutoConfiguration.class})`
   - 모든 Redis Bean을 `@Configuration` 클래스에서 수동 구성
   - `@Primary`, `@Qualifier`로 Bean 구분
7. 메시지 직렬화: JSON 문자열로 publish/subscribe (`StringRedisSerializer` 사용)

## 설정 참조
```yaml
# 환경 변수
REDIS_PUBSUB_NODES: redis-pubsub-1:6380,redis-pubsub-2:6381
```

```java
// Redis auto-configuration 비활성화 예시
@SpringBootApplication(exclude = {
    RedisAutoConfiguration.class,
    RedisRepositoriesAutoConfiguration.class
})

// Bean 구성 예시
@Configuration
public class RedisPubSubConfig {
    @Bean("pubsubConnectionFactory1")
    public LettuceConnectionFactory pubsubConnectionFactory1() { ... }

    @Bean("pubsubConnectionFactory2")
    public LettuceConnectionFactory pubsubConnectionFactory2() { ... }

    @Bean("pubsubListenerContainer1")
    public RedisMessageListenerContainer listenerContainer1(
        @Qualifier("pubsubConnectionFactory1") LettuceConnectionFactory cf) { ... }
}
```

## 테스트 기준
- [ ] 채널 subscribe 후 동일 채널에 publish하면 메시지가 수신되는지 확인
- [ ] Consistent Hash Ring에 의해 결정된 Redis 노드에만 publish되는지 확인
- [ ] subscribe는 모든 Redis 노드에서 수행되는지 확인 (2개 노드 모두 subscribe)
- [ ] unsubscribe 후 해당 채널의 메시지가 더 이상 수신되지 않는지 확인
- [ ] Pub/Sub 콜백이 Lettuce EventLoop 스레드가 아닌 별도 스레드풀에서 실행되는지 확인 (`Thread.currentThread().getName()` 검증)

## 주의사항
> - Lettuce EventLoop 스레드에서 동기 Redis 호출이나 DB 조회를 하면 전체 Pub/Sub 메시지 처리가 멈춘다. 이것이 "위치 업데이트가 간헐적으로 안 오는" 가장 흔한 원인이 될 수 있다. (docs/domain-insights.md "Pub/Sub 콜백에서 블로킹 호출" 참조)
> - Spring Boot의 `spring.data.redis.*` auto-configuration을 반드시 비활성화하고 모든 Bean을 수동으로 구성해야 한다. auto-configuration과 수동 Bean이 충돌하면 예측 불가능한 동작이 발생한다. (docs/domain-insights.md "Lettuce 기반 다중 Redis 인스턴스 연결" 참조)
> - Pub/Sub 모드에 진입한 Redis 연결은 일반 명령어를 실행할 수 없다. `RedisMessageListenerContainer`가 자동으로 별도 연결을 관리한다. (docs/lessons.md "Pub/Sub 구독 시 별도 연결 필요" 참조)
> - `RedisMessageListenerContainer`에 등록된 리스너가 어떤 `LettuceConnectionFactory`에 연결되어 있는지로 메시지 출처 Redis 노드를 판단한다. 리스너를 Redis 인스턴스별로 분리하는 것이 전파 경로 추적(P2-07)의 전제조건이다.
