# P2-03: Redis 위치 캐시 서비스 구현

## 메타정보
- Phase: 2
- 의존성: P1-06
- 예상 소요: 30분
- 난이도: 하

## 목표
> 활성 사용자의 최신 위치를 Redis 캐시에 저장하고 TTL로 비활성 사용자를 자동 만료하는 `LocationCacheService`를 구현한다.

## 컨텍스트
> Redis 위치 캐시는 사용자의 최신 위치를 key-value로 저장하며, 초기 접속 시 친구들의 현재 위치를 배치 조회하는 데 사용된다. Pub/Sub 노드와는 별개의 Redis 인스턴스(redis-cache:6379)를 사용한다. (docs/architecture.md "Redis Location Cache" 섹션, docs/erd.md "Redis 위치 캐시" 섹션 참조)

## 상세 요구사항
1. `LocationCacheService` 클래스 생성
2. Redis 캐시 전용 `LettuceConnectionFactory` + `StringRedisTemplate` Bean 구성
   - 호스트: `REDIS_CACHE_HOST` (기본 `redis-cache`)
   - 포트: `REDIS_CACHE_PORT` (기본 `6379`)
   - `@Qualifier("cacheRedisTemplate")`로 Pub/Sub용 RedisTemplate과 구분
3. 키 패턴: `location:user:{id}`
4. 값 포맷: JSON 문자열 `{"x": 500, "y": 300, "timestamp": 1234567890}`
5. TTL: 600초 (10분), 상수로 분리 `LOCATION_TTL_SECONDS = 600`
6. 직렬화: `StringRedisSerializer` 사용 (`JdkSerializationRedisSerializer` 기본값 사용 금지)
7. 주요 메서드:
   - `setLocation(String userId, int x, int y, long timestamp)` — 위치 저장 + TTL 리셋 (`SET key value EX ttl`)
   - `getLocation(String userId)` — 단건 위치 조회, 없으면 `Optional.empty()` 반환
   - `getLocations(List<String> userIds)` — 배치 위치 조회 (`MGET` 또는 pipeline), 결과를 `Map<String, Location>` 으로 반환 (존재하지 않는 키는 제외)
   - `removeLocation(String userId)` — 위치 삭제 (연결 종료 시 사용)
8. `Location` 값 객체 (DTO/record): `x`, `y`, `timestamp` 필드

## 설정 참조
```yaml
# 환경 변수
REDIS_CACHE_HOST: redis-cache
REDIS_CACHE_PORT: 6379
```

```java
// Bean 구성 예시
@Bean("cacheConnectionFactory")
public LettuceConnectionFactory cacheConnectionFactory() {
    RedisStandaloneConfiguration config = new RedisStandaloneConfiguration(cacheHost, cachePort);
    return new LettuceConnectionFactory(config);
}

@Bean("cacheRedisTemplate")
public StringRedisTemplate cacheRedisTemplate(
    @Qualifier("cacheConnectionFactory") LettuceConnectionFactory cf) {
    return new StringRedisTemplate(cf);
}
```

## 테스트 기준
- [ ] 위치 저장 후 조회 시 동일한 x, y, timestamp 값을 반환하는지 확인
- [ ] 배치 조회 시 존재하는 사용자만 결과에 포함되는지 확인
- [ ] TTL 만료 후 조회 시 `Optional.empty()` 반환 확인 (통합 테스트에서 짧은 TTL로 검증)
- [ ] `redis-cli GET location:user:test-1`로 JSON 문자열이 사람이 읽을 수 있는 형태인지 확인 (StringRedisSerializer 검증)
- [ ] `removeLocation` 후 조회 시 `Optional.empty()` 반환 확인

## 주의사항
> - `JdkSerializationRedisSerializer`가 기본값이다. 반드시 `StringRedisSerializer`로 변경해야 `redis-cli`로 값을 확인할 수 있다. (docs/lessons.md "기본 JdkSerializationRedisSerializer" 참조, docs/domain-insights.md "Spring Boot JdkSerializationRedisSerializer 기본값" 참조)
> - Redis 캐시 키 패턴은 `location:user:{id}`로 통일한다. `user:{id}`만 사용하면 Pub/Sub 채널명(`user:{id}`)과 네임스페이스가 충돌한다. (docs/erd.md 일관성 검토 참조)
> - 배치 조회 시 `MGET` 사용 — Pipeline 대비 간단하고 7~10명 규모에서 충분한 성능이다.
