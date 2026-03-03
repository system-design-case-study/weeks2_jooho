# P2-06: 거리 계산 + 반경 필터링 로직

## 메타정보
- Phase: 2
- 의존성: P1-01
- 예상 소요: 30분
- 난이도: 하

## 목표
> Euclidean 거리 계산과 검색 반경 내 판정을 수행하는 `DistanceCalculator` 유틸리티 클래스를 구현한다.

## 컨텍스트
> WS서버가 Redis Pub/Sub에서 친구의 위치 업데이트를 수신하면, 자신에게 연결된 클라이언트의 현재 위치와 비교하여 검색 반경 내에 있는지 판정한다. 이 계산은 메모리 내 좌표로 수행되며 DB 조회가 없다. XY 좌표 평면(0~1000)에서의 단순 Euclidean 거리를 사용한다. (docs/design.md "실시간 위치 공유 + 거리 필터링" 섹션, docs/architecture.md 데이터 흐름 참조)

## 상세 요구사항
1. `DistanceCalculator` 유틸리티 클래스 생성 (인스턴스 불필요, static 메서드)
2. Euclidean 거리 계산:
   - `static double calculate(int x1, int y1, int x2, int y2)`
   - 공식: `sqrt((x2-x1)^2 + (y2-y1)^2)`
   - `Math.sqrt()` + `Math.pow()` 사용
3. 반경 판정:
   - `static boolean isInRange(int x1, int y1, int x2, int y2, double searchRadius)`
   - `distance <= searchRadius` 이면 `true`
   - 최적화: 제곱 비교로 `sqrt` 생략 가능 (`dx*dx + dy*dy <= radius*radius`)
4. 기본 검색 반경 상수: `DEFAULT_SEARCH_RADIUS = 200.0`
5. 좌표 범위: 0 ~ 1000 (XY 평면)
6. private 생성자로 인스턴스 생성 방지 (유틸리티 클래스 패턴)

## 설정 참조
```java
// 사용 예시 (WS서버 Connection Handler에서)
double distance = DistanceCalculator.calculate(myX, myY, friendX, friendY);
boolean inRange = DistanceCalculator.isInRange(myX, myY, friendX, friendY, searchRadius);
```

## 테스트 기준
- [ ] 같은 좌표 입력 시 거리 0 반환 확인
- [ ] 알려진 좌표 쌍에 대해 정확한 거리 계산 확인 (예: (0,0)-(3,4) = 5.0)
- [ ] 반경 경계값 테스트: 거리가 정확히 반경과 같을 때 `true` 반환 확인
- [ ] 반경 내 판정: 거리 < 반경일 때 `true`, 거리 > 반경일 때 `false` 확인
- [ ] 좌표 범위 극값 테스트: (0,0)-(1000,1000) 거리 계산 정확성 확인 (약 1414.21)
- [ ] 음수나 범위 초과 좌표 입력 시 동작 확인 (유효성 검증 여부 결정)

## 주의사항
> - 이 프로젝트는 실제 GPS 좌표가 아닌 XY 좌표 평면을 사용한다. Haversine 공식이 아닌 단순 Euclidean 거리를 사용한다. (docs/design.md "Out of Scope: 실제 GPS/지도 연동" 참조)
> - 거리 계산은 WS서버의 Connection Handler에서 호출되며, 메모리 내 좌표로 계산한다. DB 조회 없이 빠르게 수행되어야 한다.
> - 7~10명 동시 접속 규모이므로 성능 최적화는 불필요하지만, `sqrt` 대신 제곱 비교를 사용하는 것은 코드 복잡도 증가 없이 가능한 간단한 최적화이다.
