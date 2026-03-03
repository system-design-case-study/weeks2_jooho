# P2-01: Consistent Hash Ring 구현

## 메타정보
- Phase: 2
- 의존성: P1-01
- 예상 소요: 1시간
- 난이도: 중

## 목표
> 사용자 채널(`user:{id}`)을 Redis Pub/Sub 노드에 매핑하는 Consistent Hash Ring을 구현한다.

## 컨텍스트
> Consistent Hash Ring은 Pub/Sub 채널을 어떤 Redis 노드에 publish할지 결정하는 핵심 라우팅 모듈이다. 모든 WS서버가 동일한 Hash Ring 상태를 유지해야 하며, 환경 변수 `REDIS_PUBSUB_NODES`로 노드 목록을 주입받아 인스턴스 간 불일치를 방지한다. 순수 계산 모듈로 외부 의존이 없다. (docs/architecture.md "Consistent Hash Ring" 섹션, docs/design.md "Consistent Hash Ring 설계" 섹션 참조)

## 상세 요구사항
1. `ConsistentHashRing<T>` 제네릭 클래스 생성
2. 내부 자료구조: `TreeMap<Long, T>` — 해시값을 키로, 물리 노드를 값으로 저장
3. 해시 함수: Guava `Hashing.murmur3_128()` 사용 → `HashCode.asLong()`의 상위 32bit를 추출 (`>>> 32` 또는 `asInt()` 대신 128bit의 상위 32bit 사용)
4. 가상 노드 지원: 물리 노드당 150개, 네이밍 규칙 `"{nodeId}#0"` ~ `"{nodeId}#149"`
5. 주요 메서드:
   - `addNode(T node)` — 물리 노드 추가, 가상 노드 150개를 Ring에 배치
   - `removeNode(T node)` — 물리 노드 제거, 해당 가상 노드 150개를 Ring에서 제거
   - `getNode(String key)` — 키를 해싱하여 `TreeMap.ceilingEntry()` 로 시계 방향 가장 가까운 노드 반환. ring 끝을 넘어가면 `firstEntry()` (wrap-around)
   - `getAllNodes()` — 등록된 물리 노드 목록 반환
6. Hash Ring 상태 조회용 메서드 (시각화 데이터 제공):
   - `getRingState()` — 전체 가상 노드 위치(`{position, physicalNode, virtualIndex}`) 목록 반환
   - `getNodeForKey(String key)` — 특정 키가 어떤 노드에 매핑되는지 + 해시 위치 반환
7. 해시 공간: `0 ~ 2^32 - 1` (원형), `TreeMap`의 `Long` 타입은 unsigned 32bit 값을 저장
8. 스레드 안전성: `ConcurrentSkipListMap` 대신 `TreeMap` + 읽기 위주이므로 `Collections.unmodifiableNavigableMap` 복사본 제공 또는 `synchronized` 블록 사용 (노드 추가/제거는 애플리케이션 시작 시에만 발생)

## 설정 참조
```yaml
# 환경 변수
REDIS_PUBSUB_NODES: redis-pubsub-1:6380,redis-pubsub-2:6381
```

- Guava 의존성: `com.google.guava:guava` (build.gradle에 추가 필요)
- 가상 노드 수 상수: `DEFAULT_VIRTUAL_NODE_COUNT = 150`

## 테스트 기준
- [ ] 노드 2개 추가 시 TreeMap에 가상 노드 300개(150 x 2)가 등록되는지 확인
- [ ] `getNode(key)` 호출 시 항상 null이 아닌 유효한 노드를 반환하는지 확인
- [ ] 동일 키에 대해 항상 동일 노드를 반환하는지 확인 (결정론적)
- [ ] 균등 분포 검증: 1000개 랜덤 키에 대해 각 노드에 매핑된 비율이 40~60% 범위인지 (2개 노드 기준)
- [ ] 노드 추가 시 기존 키의 최소 재배치 검증: 키 1000개 중 노드 추가 전후로 재배치되는 비율이 이론값(1/N) 근처인지
- [ ] 노드 제거 후 `getNode(key)` 가 남은 노드를 반환하는지 확인
- [ ] Ring wrap-around 검증: 해시값이 Ring의 최대값을 넘어가는 키가 첫 번째 노드로 매핑되는지

## 주의사항
> - Hash Ring 상태가 모든 WS서버 인스턴스에서 동일해야 한다. 환경 변수 `REDIS_PUBSUB_NODES`로 노드 목록을 고정하여 불일치를 방지한다. (docs/domain-insights.md "Consistent Hash Ring 상태 불일치" 참조)
> - Guava `Hashing.murmur3_128()`의 seed 값을 명시적으로 지정하여 재현 가능하도록 한다 (기본 seed: 0).
> - 프론트엔드 시각화에서는 백엔드가 계산한 Hash Ring 상태를 API로 전달받아 렌더링한다. 프론트엔드에서 해시 함수를 재구현하지 않는다. (docs/domain-insights.md "MurmurHash3 선택의 잠재적 문제" 참조)
> - 시각화 목적의 가상 노드 수(150개)와 실제 운영 환경의 적정 수가 다를 수 있음을 인지한다. (docs/lessons.md "Consistent Hash Ring" 참조)
