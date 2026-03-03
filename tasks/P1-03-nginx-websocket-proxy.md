# P1-03: Nginx WebSocket 프록시 설정

## 메타정보
- Phase: 1
- 의존성: P1-02
- 예상 소요: 30분
- 난이도: 중

## 목표
> Nginx가 WebSocket 프록시와 REST 라우팅을 올바르게 수행하여, 브라우저에서 `/ws/**`로 WebSocket 연결이 가능하고 `/api/**`로 REST 요청이 라우팅된다.

## 컨텍스트
> 클라이언트와 백엔드 사이의 유일한 진입점. architecture.md "Nginx Load Balancer" 섹션 참조. WebSocket Upgrade 설정 4종 세트가 핵심이며, 하나라도 누락되면 WebSocket 연결이 불가하거나 60초 후 자동 종료된다. lessons.md의 WebSocket 관련 함정 참조.

## 상세 요구사항
1. `nginx/nginx.conf` 파일 작성
2. upstream 설정
   - `ws_backend`: ws-server-1:8081, ws-server-2:8082 (round-robin)
   - `api_backend`: ws-server-1:8081, ws-server-2:8082 (round-robin)
3. `/ws/**` → WebSocket proxy 설정 (필수 4종 세트)
   - `proxy_http_version 1.1;`
   - `proxy_set_header Upgrade $http_upgrade;`
   - `proxy_set_header Connection "upgrade";`
   - `proxy_read_timeout 86400s;` (24시간, 기본 60초 방지)
4. `/api/**` → REST proxy 설정
   - `proxy_pass http://api_backend;`
   - 표준 프록시 헤더 설정 (Host, X-Real-IP, X-Forwarded-For, X-Forwarded-Proto)
5. CORS 설정
   - `Access-Control-Allow-Origin: *` (개발 환경)
   - `Access-Control-Allow-Methods: GET, POST, PUT, DELETE, OPTIONS`
   - `Access-Control-Allow-Headers: Content-Type, Authorization`
6. Frontend 정적 파일 서빙 또는 Frontend dev server로 프록시
   - `/` → frontend:3000 으로 프록시 (개발 환경)
7. docker-compose.yml에 Nginx 설정 파일 볼륨 마운트 추가
   - `./nginx/nginx.conf:/etc/nginx/nginx.conf:ro`

## 설정 참조
```nginx
# architecture.md 필수 설정 (하나라도 누락 시 WebSocket 연결 불가)
proxy_http_version 1.1;
proxy_set_header Upgrade $http_upgrade;
proxy_set_header Connection "upgrade";
proxy_read_timeout 86400s;
```

## 테스트 기준
- [ ] `nginx -t` 설정 문법 검증 통과 (컨테이너 내에서 실행)
- [ ] `/ws/location` 경로로 WebSocket 연결 성공 (wscat 또는 브라우저 개발자 도구)
- [ ] `/api/**` 경로로 REST 요청 라우팅 확인
- [ ] WebSocket 연결이 60초 이후에도 유지됨 (proxy_read_timeout 검증)
- [ ] round-robin으로 ws-server-1, ws-server-2에 분산되는지 로그 확인

## 주의사항
- lessons.md: `proxy_http_version 1.1` 미설정 시 HTTP/1.0이 기본이며 프로토콜 업그레이드 불가
- lessons.md: `Upgrade` 헤더는 hop-by-hop 헤더로 프록시에서 자동 전달되지 않음 → 명시적 설정 필수
- lessons.md: 기본 timeout 60초 → `proxy_read_timeout 86400s`로 변경 필수
- lessons.md: CORS 설정 누락 시 WebSocket handshake에서 CORS 오류 발생 가능
- architecture.md: 설정 필수사항으로 4가지 모두 명시 — 하나라도 누락 시 WebSocket 연결 불가 또는 60초 후 자동 종료
