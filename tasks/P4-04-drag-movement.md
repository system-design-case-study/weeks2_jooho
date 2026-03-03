# P4-04: 드래그 이동 + 위치 전송

## 메타정보
- Phase: 4
- 의존성: P4-03, P4-01
- 예상 소요: 45분
- 난이도: 중

## 목표
> Canvas 내에서 자기 캐릭터를 드래그로 이동할 수 있고, 이동 시 throttle이 적용된 LOCATION_UPDATE 메시지가 WebSocket으로 전송된다.

## 컨텍스트
> 사용자 인터랙션의 핵심 기능. 드래그 이벤트가 위치 변경의 시작점이며, design.md "데이터 흐름 > 입력"의 "사용자 드래그 이벤트 (x, y 좌표) → WebSocket으로 전송"에 해당한다. Canvas 좌표 변환이 정확해야 드래그가 올바르게 동작한다.

## 상세 요구사항
1. Canvas 마우스/터치 이벤트 처리
   - `mousedown` / `touchstart`: 자기 캐릭터 위에서 시작 시 드래그 모드 진입
   - `mousemove` / `touchmove`: 드래그 중 캐릭터 위치 갱신
   - `mouseup` / `touchend`: 드래그 모드 종료
2. 히트 테스트 (Hit Testing)
   - 마우스 클릭 위치가 자기 캐릭터 영역 내인지 판별
   - Canvas 좌표 변환: `getBoundingClientRect()` + `devicePixelRatio` 보정
   - 자기 캐릭터만 드래그 가능 (다른 캐릭터 드래그 불가)
3. 좌표 변환
   - DOM 이벤트 좌표 (clientX, clientY) → Canvas 논리 좌표 (0~1000)
   - `canvas.getBoundingClientRect()`로 Canvas 오프셋 계산
   - `devicePixelRatio` 보정 적용
   - 좌표 범위 클램핑 (0~1000)
4. LOCATION_UPDATE 전송 (throttle 적용)
   - 드래그 중 `mousemove`마다 WebSocket 메시지 전송은 과도 → throttle 적용
   - throttle 간격: 50~100ms (초당 10~20회 전송)
   - 메시지 포맷: `{ "type": "LOCATION_UPDATE", "x": 500, "y": 300 }`
   - 드래그 종료 시 마지막 위치를 반드시 전송 (throttle로 누락 방지)
5. Canvas ref 데이터 갱신
   - 드래그 중 `useRef`의 `myPosition` 즉시 갱신 → 렌더링 루프에서 반영
   - React 상태는 갱신하지 않음 (리렌더링 방지)

## 설정 참조
```typescript
// throttle 구현 (간단 버전)
function throttle(fn: Function, delay: number) {
  let lastCall = 0;
  return (...args: any[]) => {
    const now = Date.now();
    if (now - lastCall >= delay) {
      lastCall = now;
      fn(...args);
    }
  };
}
```

## 테스트 기준
- [ ] 자기 캐릭터를 마우스로 드래그하여 이동할 수 있는지 확인
- [ ] 드래그 중 Canvas 좌표 변환이 정확한지 확인 (클릭 위치와 캐릭터 위치 일치)
- [ ] 드래그 중 LOCATION_UPDATE 메시지가 WebSocket으로 전송되는지 확인
- [ ] throttle이 적용되어 초당 10~20회로 메시지 전송이 제한되는지 확인
- [ ] 드래그 종료 시 마지막 위치가 전송되는지 확인
- [ ] 다른 캐릭터는 드래그되지 않는지 확인
- [ ] 좌표가 0~1000 범위를 벗어나지 않는지 확인

## 주의사항
> - lessons.md: Canvas 좌표와 DOM 좌표 불일치 — `getBoundingClientRect()` + `devicePixelRatio` 보정 필수
> - design.md: 비기능 요구사항 — 위치 드래그 → 친구 화면 반영까지 체감 지연 200ms 이내. throttle 간격을 너무 길게 설정하면 체감 지연 증가
> - architecture.md: Canvas 데이터는 `useRef`로 관리 — 드래그 중 `useState` 갱신하면 매 프레임 리렌더링 발생
