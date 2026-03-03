# P1-08: Frontend 프로젝트 초기화

## 메타정보
- Phase: 1
- 의존성: P1-02
- 예상 소요: 45분
- 난이도: 하

## 목표
> Vite + React 18 + TypeScript 프로젝트가 생성되고, D3.js 의존성이 추가되며, 기본 레이아웃(좌측 Canvas 영역 + 우측 시스템 패널)이 브라우저에 표시된다.

## 컨텍스트
> 사용자가 직접 보고 상호작용하는 클라이언트. design.md "핵심 기능 > 시스템 내부 시각화" 섹션 참조. Canvas로 위치 맵을, D3.js + SVG로 Hash Ring을 렌더링한다 (tech-stack.md). 모든 모탭이 동일한 뷰를 가지며 별도 관리자 모드 없음.

## 상세 요구사항
1. Vite + React + TypeScript 프로젝트 생성 (`frontend/` 디렉토리)
   - `npm create vite@latest frontend -- --template react-ts`
   - 또는 수동 설정
2. 의존성 추가
   - `d3` (v7.x) + `@types/d3`
   - 기타: 필요 최소한만 (외부 라이브러리 최소화)
3. 기본 레이아웃 구현
   - 좌측: Canvas 영역 (위치 맵이 들어갈 자리, placeholder)
   - 우측: 시스템 패널 영역 (Hash Ring, 로그 등이 들어갈 자리, placeholder)
   - 반응형이 아닌 고정 레이아웃 (7~10명 데스크톱 학습 환경)
4. Frontend Dockerfile 작성 (`frontend/Dockerfile`)
   - 개발 환경: Vite dev server (`npm run dev -- --host 0.0.0.0`)
   - 또는 빌드 후 Nginx 서빙 (선택)
5. docker-compose.yml에 frontend 서비스 빌드 경로 설정
   - `build: ./frontend`
   - 포트: 3000

## 설정 참조
- tech-stack.md: React 18.x, TypeScript 5.x, D3.js 7.x, Vite 6.x
- tech-stack.md: D3.js는 DOM 직접 조작 → `useRef` + `useEffect`로 React 관리 밖에서 렌더링
- tech-stack.md: Create React App deprecated → Vite 사용
- design.md: Canvas + `requestAnimationFrame` (위치 맵), D3.js + SVG (Hash Ring)
- architecture.md: Canvas 데이터는 `useRef`, SVG/로그 데이터는 `useState` (이중 상태 패턴)

## 테스트 기준
- [ ] `npm run build` 성공 (TypeScript 컴파일 + Vite 빌드)
- [ ] `npm run dev`로 로컬 개발 서버 기동 확인
- [ ] 브라우저에서 기본 레이아웃 표시 (좌측 Canvas 영역 + 우측 패널 영역)
- [ ] D3.js import 성공 확인 (간단한 테스트 코드)
- [ ] Frontend Docker 이미지 빌드 및 컨테이너 기동 성공

## 주의사항
- lessons.md: `requestAnimationFrame` 사용 필수, `setInterval` 사용 금지 (Canvas 렌더링)
- lessons.md: React 상태 변경이 Canvas 리렌더링 유발 → 애니메이션 상태는 `useRef`로 관리
- lessons.md: 고해상도(Retina) 디스플레이 미대응 시 Canvas 흐릿 → `devicePixelRatio` 처리 필요 (이 task에서는 placeholder만, 실제 Canvas 구현은 이후 task)
- tech-stack.md: D3.js는 DOM 직접 조작 → React Virtual DOM과 충돌 가능 → `useRef` + `useEffect`로 분리
- 이 task에서는 레이아웃 골격만 생성. WebSocket 연결, Canvas 렌더링, D3.js Hash Ring 시각화는 이후 Phase에서 구현
