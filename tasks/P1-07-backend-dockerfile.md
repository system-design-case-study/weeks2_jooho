# P1-07: Backend Dockerfile (Multi-stage Build)

## 메타정보
- Phase: 1
- 의존성: P1-01
- 예상 소요: 30분
- 난이도: 하

## 목표
> Multi-stage build로 Spring Boot 애플리케이션의 Docker 이미지가 빌드되고, 컨테이너가 `-Xmx256m` 제한으로 기동된다.

## 컨텍스트
> WS서버 2개(`ws-server-1`, `ws-server-2`)가 동일한 Docker 이미지로 실행되며, 환경 변수(`INSTANCE_ID`)로 인스턴스를 구분한다. architecture.md "배포 구조" 섹션에서 리소스 제한이 명시되어 있다. design.md 제약사항에 `-Xmx256m` 메모리 제한 필수.

## 상세 요구사항
1. `Dockerfile` 작성 (프로젝트 루트)
2. Build stage
   - 베이스 이미지: `eclipse-temurin:21-jdk` (또는 `eclipse-temurin:21-jdk-alpine`)
   - Gradle Wrapper 복사 및 의존성 다운로드 (레이어 캐싱)
   - 소스 코드 복사 및 `./gradlew build -x test` 실행
3. Run stage
   - 베이스 이미지: `eclipse-temurin:21-jre` (또는 `eclipse-temurin:21-jre-alpine`)
   - JAR 파일 복사
   - `ENTRYPOINT`에서 `JAVA_OPTS` 환경 변수 전달
   - 기본 `JAVA_OPTS`: `-Xmx256m -XX:MaxMetaspaceSize=128m`
4. 의존성 레이어 캐싱 최적화
   - `build.gradle.kts`, `settings.gradle.kts`, `gradle/` 먼저 복사
   - `./gradlew dependencies` 실행으로 의존성 캐싱
   - 이후 소스 코드 복사 및 빌드
5. `.dockerignore` 파일 작성
   - `.gradle/`, `build/`, `.idea/`, `*.iml` 등 불필요한 파일 제외

## 설정 참조
- architecture.md: WS서버 리소스 제한 `-Xmx256m`
- architecture.md: 환경 변수 `JAVA_OPTS: -Xmx256m -XX:MaxMetaspaceSize=128m`
- docker-compose.yml에서 동일 이미지를 ws-server-1, ws-server-2로 실행

## 테스트 기준
- [ ] `docker build -t nearby-friends-ws .` 빌드 성공
- [ ] 이미지 크기 확인 (JRE 기반으로 JDK 대비 경량)
- [ ] `docker run` 시 컨테이너 정상 기동 (Spring Boot 로고 출력)
- [ ] `JAVA_OPTS` 환경 변수가 JVM에 전달되는지 확인 (`-Xmx256m`)
- [ ] 소스 코드만 변경 시 의존성 레이어 캐시 활용 확인 (재빌드 속도)

## 주의사항
- design.md: Spring Boot `-Xmx256m` 메모리 제한 필수
- Gradle Daemon은 Docker 빌드 내에서 사용하지 않는 것이 권장 → `--no-daemon` 옵션 추가
- alpine 이미지 사용 시 glibc 관련 호환성 이슈 가능 → 문제 시 일반 이미지로 전환
- `ENTRYPOINT`에서 `exec java $JAVA_OPTS -jar app.jar` 형태로 PID 1에서 직접 실행 (시그널 전달)
