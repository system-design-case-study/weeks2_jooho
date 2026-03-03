# P4-02: XY 좌표 Canvas -- 기본 렌더링

## 메타정보
- Phase: 4
- 의존성: P4-01
- 예상 소요: 45분
- 난이도: 중

## 목표
> 1000x1000 좌표 평면 Canvas가 Retina 대응으로 선명하게 렌더링되고, 그리드/배경이 표시되며, `requestAnimationFrame` 렌더링 루프가 동작한다.

## 컨텍스트
> 위치 맵의 기반 레이어. architecture.md "Frontend > 렌더링 파이프라인"의 Canvas + `requestAnimationFrame` 명세에 해당. 이 Canvas 위에 도트 캐릭터(P4-03), 드래그 이동(P4-04), 반경 표시(P4-05)가 순차적으로 추가된다. Retina 대응을 초기에 적용하지 않으면 macOS에서 흐릿한 결과가 나온다.

## 상세 요구사항
1. Canvas 컴포넌트 생성 (`LocationCanvas`)
   - HTML5 `<canvas>` 엘리먼트
   - 논리 크기: 화면에 맞춰 반응형 (예: 부모 컨테이너의 70% 또는 고정 사이즈)
   - 좌표 공간: 0~1000 x 0~1000 (design.md)
2. Retina (HiDPI) 대응
   - `window.devicePixelRatio`로 물리 픽셀 크기 계산
   - Canvas 실제 크기(`width`, `height` 속성): 논리 크기 x DPR
   - CSS 크기(`style.width`, `style.height`): 논리 크기 유지
   - Context에 `scale(dpr, dpr)` 적용
3. 그리드/배경 렌더링
   - 밝은 배경색 (#f8f9fa 또는 유사한 밝은 색)
   - 100px 간격 그리드 라인 (연한 회색)
   - 좌표 레이블 (0, 100, 200, ... 1000)
4. `requestAnimationFrame` 렌더링 루프
   - `useRef`로 animation frame ID 관리
   - `useEffect` cleanup에서 `cancelAnimationFrame` 호출
   - 렌더링 함수: `clearRect` → 그리드 → (향후) 캐릭터
   - Canvas 데이터는 `useRef`로 관리 (React 리렌더링 회피)
5. 좌표 변환 유틸리티
   - 논리 좌표(0~1000) → Canvas 픽셀 좌표 변환
   - Canvas 픽셀 좌표 → 논리 좌표 변환 (마우스 이벤트용)
   - `getBoundingClientRect()` + `devicePixelRatio` 보정

## 설정 참조
```typescript
// Retina 대응 기본 패턴
const dpr = window.devicePixelRatio || 1;
canvas.width = logicalWidth * dpr;
canvas.height = logicalHeight * dpr;
canvas.style.width = `${logicalWidth}px`;
canvas.style.height = `${logicalHeight}px`;
ctx.scale(dpr, dpr);
```

## 테스트 기준
- [ ] Canvas가 화면에 렌더링되는지 확인
- [ ] Retina 디스플레이에서 흐릿하지 않은지 확인 (DPR 2에서 선명한 그리드)
- [ ] 그리드 라인이 100px 간격으로 표시되는지 확인
- [ ] `requestAnimationFrame` 루프가 동작 중인지 확인 (개발자 도구 Performance 탭)
- [ ] 컴포넌트 언마운트 시 `cancelAnimationFrame`이 호출되는지 확인 (메모리 누수 방지)
- [ ] 좌표 변환 유틸리티: (500, 500) 논리 좌표가 Canvas 중앙에 매핑되는지 확인

## 주의사항
> - domain-insights.md: macOS에서 `devicePixelRatio`가 2이므로, 첫 시각적 결과가 흐릿한 것이 동기 부여를 크게 떨어뜨림 — Retina 대응 초기 적용 필수
> - lessons.md: `setInterval` 대신 `requestAnimationFrame` 사용 — 프레임 드랍 방지
> - lessons.md: React 상태 변경이 Canvas 리렌더링을 유발하지 않도록 `useRef`로 관리
> - lessons.md: `useEffect` cleanup에서 `cancelAnimationFrame` 호출 필수 — 메모리 누수 방지
> - lessons.md: Canvas 좌표와 DOM 좌표 불일치 — `getBoundingClientRect()` + DPR 보정 필수
