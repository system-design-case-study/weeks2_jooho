# P1-01: Spring Boot 프로젝트 초기화

## 메타정보
- Phase: 1
- 의존성: 없음
- 예상 소요: 30분
- 난이도: 하

## 목표
> Gradle Kotlin DSL 기반 Spring Boot 3.4.x 프로젝트가 생성되고, 필요한 모든 의존성이 포함된 상태로 `./gradlew build`가 성공한다.

## 컨텍스트
> 전체 시스템의 백엔드 기반이 되는 프로젝트. 이후 모든 Phase 1 백엔드 task(P1-04 ~ P1-07)와 Phase 2 이상의 비즈니스 로직이 이 프로젝트 위에 구현된다. design.md의 "기술 스택 > Backend" 섹션과 tech-stack.md의 의존성 호환성 확인 참조.

## 상세 요구사항
1. Spring Initializr 또는 수동으로 Gradle Kotlin DSL 프로젝트 생성
   - Group: `com.nearbyfreinds` (또는 적절한 패키지명)
   - Java 21, Spring Boot 3.4.x
   - 단일 모듈 구조 (멀티 모듈 아님)
2. `build.gradle.kts`에 다음 의존성 추가
   - `spring-boot-starter-web`
   - `spring-boot-starter-websocket`
   - `spring-boot-starter-data-redis` (Lettuce 기본 포함)
   - `spring-boot-starter-data-jpa`
   - `org.postgresql:postgresql` (런타임)
   - `spring-boot-starter-data-cassandra`
   - `com.google.guava:guava:33.4.0-jre` (MurmurHash3용)
   - `spring-boot-starter-test` (테스트)
3. `application.yml` 기본 설정
   - `server.port: 8080`
   - `spring.application.name: nearby-friends`
   - `spring.jpa.hibernate.ddl-auto: validate`
   - `spring.data.redis.repositories.enabled: false` (Redis auto-config 비활성화 준비)
   - 환경 변수 플레이스홀더 설정 (INSTANCE_ID, REDIS_CACHE_HOST 등)
4. 메인 Application 클래스 생성

## 설정 참조
- Java 21 LTS, Spring Boot 3.4.x (tech-stack.md 호환성 확인 완료)
- Guava 33.x-jre flavor 필수 (Android flavor는 Java 21 미지원)
- `Hashing.murmur3_32()` deprecated, `Hashing.murmur3_128()` 사용 (tech-stack.md 주의사항)
- DataStax Java Driver 4.18+에서 groupId 변경되었으나 Spring Boot BOM이 관리하므로 직접 지정 불필요

## 테스트 기준
- [ ] `./gradlew build` 성공 (컴파일 + 테스트)
- [ ] 의존성 트리에 Lettuce, Hibernate, DataStax Driver, Guava 포함 확인
- [ ] Application 클래스의 `main()` 메서드 존재

## 주의사항
- tech-stack.md: DataStax Driver 4.18+ groupId가 `org.apache.cassandra`로 변경되었으나 Spring Boot BOM이 관리하므로 직접 버전 지정 불필요
- tech-stack.md: Guava가 DataStax Driver 내부에 shaded 번들되어 있어 버전 충돌 가능성은 낮으나, 프로젝트 레벨에서도 별도로 추가해야 `Hashing` API 사용 가능
- DB 연결 설정은 이 task에서 하지 않음 (Docker Compose + 각 DB task에서 처리)
- `spring.data.redis.*` auto-configuration은 P1-06에서 비활성화 처리
