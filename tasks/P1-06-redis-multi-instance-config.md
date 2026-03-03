# P1-06: Redis 다중 인스턴스 연결 설정

## 메타정보
- Phase: 1
- 의존성: P1-01, P1-02
- 예상 소요: 1시간
- 난이도: 상

## 목표
> Spring Boot에서 3개의 Redis 인스턴스(cache, pubsub-1, pubsub-2)에 각각 독립적으로 연결하는 Bean 구성이 완료되고, 각 인스턴스에 데이터를 읽고 쓸 수 있다.

## 컨텍스트
> 이 프로젝트의 핵심 난이도 지점 중 하나. architecture.md "Redis Pub/Sub Manager" 섹션과 "Redis Location Cache" 섹션 참조. Spring Boot의 Redis auto-configuration은 단일 인스턴스만 지원하므로 비활성화하고 수동으로 3개의 `LettuceConnectionFactory`를 구성해야 한다. design.md "리스크 및 완화 방안"에서 최고 리스크로 분류됨.

## 상세 요구사항
1. Spring Boot Redis auto-configuration 비활성화
   - `spring.data.redis.repositories.enabled: false`
   - `@SpringBootApplication(exclude = {RedisAutoConfiguration.class, RedisRepositoriesAutoConfiguration.class})`
2. Redis 연결 설정 Configuration 클래스 작성 (`RedisConfig`)
3. `LettuceConnectionFactory` 3개 생성
   - `cacheRedisConnectionFactory`: redis-cache:6379 (`@Primary`)
   - `pubsub1RedisConnectionFactory`: redis-pubsub-1:6380 (`@Qualifier("pubsub1")`)
   - `pubsub2RedisConnectionFactory`: redis-pubsub-2:6381 (`@Qualifier("pubsub2")`)
4. `RedisTemplate` 구성 (cache용)
   - `@Primary` `cacheRedisTemplate`
   - `StringRedisSerializer` 적용 (key, value 모두)
   - `JdkSerializationRedisSerializer` 사용 금지
5. `RedisMessageListenerContainer` 구성 (pubsub용, 인스턴스별 분리)
   - `pubsub1ListenerContainer`: pubsub-1 전용
   - `pubsub2ListenerContainer`: pubsub-2 전용
   - 별도 `TaskExecutor` 설정 (Lettuce EventLoop 블로킹 방지)
6. 환경 변수 기반 호스트/포트 설정
   - `REDIS_CACHE_HOST` / `REDIS_CACHE_PORT`
   - `REDIS_PUBSUB_NODES` 파싱 → 개별 호스트:포트 추출
7. `StringRedisSerializer` 적용으로 `redis-cli`에서 직접 데이터 확인 가능하도록 구성

## 설정 참조
```yaml
# architecture.md 환경 변수
REDIS_CACHE_HOST: redis-cache
REDIS_CACHE_PORT: 6379
REDIS_PUBSUB_NODES: redis-pubsub-1:6380,redis-pubsub-2:6381
```

- architecture.md: `spring.data.redis.*` auto-configuration 비활성화, 모든 Redis Bean 수동 구성
- architecture.md: 리스너를 Redis 인스턴스별로 분리하여 메시지가 어떤 Redis 노드에서 왔는지 식별 가능하게 구성
- architecture.md: `StringRedisSerializer` 필수 사용 (`JdkSerializationRedisSerializer` 기본값 사용 금지)

## 테스트 기준
- [ ] Spring Boot 기동 시 3개 Redis 인스턴스 연결 성공 (로그 확인)
- [ ] `cacheRedisTemplate`으로 redis-cache에 SET/GET 성공
- [ ] `redis-cli -p 6379`에서 캐시 데이터 확인 가능 (StringRedisSerializer 검증)
- [ ] `redis-cli -p 6380`에서 pubsub-1 인스턴스 접속 확인
- [ ] `redis-cli -p 6381`에서 pubsub-2 인스턴스 접속 확인
- [ ] 각 `RedisMessageListenerContainer`가 올바른 Redis 인스턴스에 연결되었는지 확인

## 주의사항
- lessons.md: 기본 `JdkSerializationRedisSerializer` 사용 시 `redis-cli`로 값 확인 불가(바이너리)
- lessons.md: Pub/Sub 리스너 스레드 블로킹 주의 → `RedisMessageListenerContainer`에 별도 `TaskExecutor` 설정
- lessons.md: Pub/Sub 모드에 진입한 Redis 연결은 일반 명령어 실행 불가 → `RedisMessageListenerContainer`가 별도 연결 관리
- lessons.md: `LettuceConnection` 자체는 thread-safe하지 않음 → `RedisTemplate`을 통해 접근
- design.md 리스크: "Redis Pub/Sub 다중 인스턴스 구독 관리"가 최고 리스크 → auto-config 비활성화 후 수동 Bean 구성으로 완화
- `@Primary`를 cache용 Bean에 설정하여 다른 컴포넌트에서 기본 주입 시 cache용이 사용되도록 함
