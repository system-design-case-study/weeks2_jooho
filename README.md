## 1. 주변 친구 시스템이란?

실시간으로 친구들의 위치를 공유하고, **일정 반경 내에 있는 친구를 탐지하여 알려주는** 위치 기반 서비스.

### 문제의 규모 (원서 기준)
| 항목 | 수치 |
|------|------|
| 동시 접속자 | 1,000만 명 |
| 위치 업데이트 | 초당 1,300만 건 |
| 평균 친구 수 | 400명 |
| 반경 기준 | 5마일 (약 8km) |

### 핵심 질문
- 1,000만 명이 동시에 위치를 보내면 어떻게 처리하는가?
- 내 친구 400명의 위치를 어떻게 실시간으로 받는가?
- 반경 내 친구만 어떻게 효율적으로 필터링하는가?

---

## 2. 이 챕터에서 중요하게 생각해야 하는 것

### (1) 실시간 양방향 통신 — WebSocket

HTTP는 요청-응답 모델이라 클라이언트가 폴링해야 한다. 위치 업데이트는 **서버가 먼저 클라이언트에게 push**해야 하므로 WebSocket이 필수적이다.

- 클라이언트 -> 서버: 내 위치 전송
- 서버 -> 클라이언트: 친구 위치 수신
- **하나의 연결로 양방향 통신** (HTTP 폴링 대비 지연 시간, 리소스 효율 모두 우수)

### (2) 서버 간 메시지 전파 — Redis Pub/Sub

WebSocket 서버가 1대라면 간단하지만, **수평 확장하면 서버 A에 연결된 사용자가 서버 B의 사용자 위치를 받을 수 없다.**

```
사용자 A (Server 1) ─X─> 사용자 B (Server 2)  // 직접 전달 불가
사용자 A (Server 1) ──> Redis Pub/Sub ──> 사용자 B (Server 2)  // 브로커 경유
```

Redis Pub/Sub의 특성:
- **Fire-and-forget**: 구독자가 없으면 메시지 유실 (위치 업데이트에는 적합 — 어차피 최신 위치가 의미 있으므로)
- **채널 기반 라우팅**: 사용자별 채널 할당 (`user:{userId}:location`)
- 친구 관계가 있으면 상대방의 채널을 subscribe

### (3) 채널 분산 — Consistent Hash Ring

Redis Pub/Sub 서버 1대로 1,300만 건/초를 처리할 수 없다. **여러 Redis 인스턴스에 채널을 분산**해야 한다.

이때 단순 `hash % N` 방식의 문제:
- Redis 서버 추가/제거 시 **거의 모든 채널의 매핑이 변경**됨
- 대규모 재배치 = 순간적 메시지 유실

Consistent Hash Ring은 서버 변경 시 **영향받는 채널을 최소화**(K/N만 재배치)한다.

### (4) 거리 계산은 subscriber 측에서

- **publish 시에는 거리 계산을 하지 않는다** (모든 구독자에게 broadcast)
- **subscribe 측 WebSocket Handler**가 수신 후 거리 계산 → 반경 내이면 클라이언트 전달, 아니면 drop
- 이유: publish 시점에 "누가 가까운지"를 계산하려면 모든 친구의 현재 위치를 조회해야 해서 비효율적

### (5) 위치 캐시의 TTL 기반 비활성 관리

- Redis에 사용자 위치를 저장할 때 **TTL(Time To Live)** 설정
- 위치 업데이트마다 TTL 갱신
- 업데이트가 없으면 자동 만료 → 해당 사용자는 비활성으로 간주
- **별도의 "오프라인 알림" 로직 없이** 캐시 만료로 자연스럽게 처리

---

## 3. 우리가 한 것

### 프로젝트 목표
책의 아키텍처를 **1,000 x 1,000 XY 좌표 평면** 위에서 시각화하는 학습 프로젝트. 분산 시스템의 데이터 흐름을 눈으로 확인한다.

### 아키텍처 구성

```
[Frontend (React + Canvas + D3.js)]
         | WebSocket
         v
[Nginx - WebSocket Proxy]
         |
    +----+----+
    |         |
[WS Server 1] [WS Server 2]   (Spring Boot, Raw WebSocket)
    |    \   /    |
    |     \ /     |
    |      X      |
    |     / \     |
    |    /   \    |
[Redis Pub/Sub 1] [Redis Pub/Sub 2]   (Consistent Hash Ring으로 채널 분산)
         |
[Redis Location Cache]   (TTL 기반 위치 캐시)
         |
[PostgreSQL]  [Cassandra]
(친구 관계)   (위치 이력)
```

### 기술 스택 & 선택 이유

| 항목 | 선택 | 이유 |
|------|------|------|
| WebSocket 방식 | Raw WebSocket (TextWebSocketHandler) | Redis Pub/Sub가 브로드캐스트 담당 → STOMP 불필요 |
| Redis 클라이언트 | Lettuce (imperative) | Spring Boot 기본 탑재, 스레드 안전 |
| 해시 함수 | MurmurHash3 (Guava) | MD5 대비 3~5배 빠름, 우수한 분포 |
| 위치 맵 시각화 | HTML5 Canvas | 60fps 실시간 렌더링에 적합 |
| Hash Ring 시각화 | D3.js + SVG | DOM 이벤트 지원, 요소 수가 적어 성능 충분 |
| 위치 이력 DB | Cassandra | 시계열 데이터에 최적, 쓰기 성능 우수 |

### 구현한 핵심 모듈

**Backend:**
- `ConsistentHashRing` — TreeMap + ceilingEntry 기반, 가상 노드 150개/물리 노드
- `DistanceCalculator` — Euclidean 거리 계산, sqrt 생략 최적화 (`dx*dx + dy*dy <= r*r`)
- `RedisPubSubManager` — 모든 WS 서버가 모든 Redis 인스턴스에 subscribe하는 비대칭 구조
- `LocationCacheService` — Redis TTL 기반 위치 캐시
- `LocationHistory` — Cassandra 시계열 저장 (Clustering Key: timestamp DESC)

**Infrastructure:**
- Docker Compose 8컨테이너 (WS x2, Redis Pub/Sub x2, Redis Cache x1, PostgreSQL, Cassandra, Nginx)
- Nginx WebSocket proxy 설정 (HTTP/1.1 Upgrade)
- JVM 메모리 제한 (-Xmx256m)

---

## 4. 핵심 개념 상세 설명

### 4-1. WebSocket — 실시간 양방향 통신

**HTTP vs WebSocket:**

```
[HTTP 폴링]
Client: "위치 변경됐어?" → Server: "아니요"    (0.5초마다 반복)
Client: "위치 변경됐어?" → Server: "아니요"
Client: "위치 변경됐어?" → Server: "네! (300, 450)"
→ 문제: 대부분의 요청이 "변경 없음". 리소스 낭비.

[WebSocket]
Client ←──── 상시 연결 ────→ Server
Server: "(300, 450) 변경됨!" → Client   (변경 시에만 즉시 push)
→ 장점: 변경이 있을 때만 통신. 지연 시간 최소.
```

**WebSocket 초기 연결 시 처리 흐름:**
1. 클라이언트가 WebSocket 연결 수립
2. 서버가 사용자 위치를 Redis Cache에 저장
3. DB에서 친구 목록 로드
4. 친구들의 현재 위치를 Cache에서 배치 조회
5. 거리 계산 후 반경 내 친구 목록을 클라이언트에 전송
6. **모든 친구의 Redis Pub/Sub 채널을 subscribe** (온라인/오프라인 무관)
7. 자신의 위치를 자신의 채널에 publish

**왜 Raw WebSocket을 선택했는가:**
- STOMP는 내장 브로커를 통한 메시지 라우팅을 제공하지만, 이 프로젝트에서는 Redis Pub/Sub가 그 역할을 대신함
- 프로토콜 수준을 직접 제어하면서 학습 효과 극대화
- 메시지 타입은 JSON의 `type` 필드로 구분 (LOCATION_UPDATE, INIT, SYSTEM_STATE 등)

---

### 4-2. Redis Pub/Sub — 서버 간 메시지 브로커

**왜 필요한가?**

WebSocket 서버는 **Stateful**하다. 각 서버가 자신에게 연결된 클라이언트만 알고 있다.

```
[문제 상황]
사용자 A ──연결──> WS Server 1
사용자 B ──연결──> WS Server 2

A가 위치를 변경하면, Server 1만 알고 있음.
B는 Server 2에 있으므로 A의 위치 변경을 모름.
```

**Redis Pub/Sub 해결:**
```
1. 각 사용자마다 전용 채널: user:A:location, user:B:location
2. B가 A의 친구 → B의 WS Server(Server 2)가 user:A:location 채널을 subscribe
3. A가 위치 변경 → Server 1이 user:A:location에 publish
4. Redis가 모든 subscriber에게 전달 → Server 2가 수신 → B에게 전달
```

**Fire-and-forget 특성이 위치 서비스에 적합한 이유:**
- 위치 데이터는 최신 값만 의미 있음 (이전 위치는 새 위치에 의해 무효화)
- 메시지 하나가 유실되어도 다음 업데이트에서 복구됨
- 메시지 지속성(Redis Streams)이 필요한 채팅과는 근본적으로 다름

---

### 4-3. Consistent Hash Ring — 채널 분산 전략

**문제: 단순 해시(hash % N)의 한계**

```
Redis 서버 3대일 때:  hash("userA") % 3 = 1  → Redis-1
Redis 서버 4대로 변경: hash("userA") % 4 = 2  → Redis-2  (변경됨!)

→ 서버 1대 추가 시 약 75%의 채널 매핑이 변경됨
→ 대규모 재배치 = 순간적 메시지 유실 + 부하 폭증
```

**Consistent Hash Ring의 해결 방식:**

```
         0 (= 2^32)
         |
    Redis-1 ●────── Redis-2 ●
         |                    |
         |     Hash Ring      |
         |    (원형 공간)      |
         |                    |
    Redis-3 ●────────────────+
         |
      2^32 / 2

1. 해시 공간을 원형(0 ~ 2^32-1)으로 구성
2. 각 Redis 서버를 해시하여 Ring 위에 배치
3. 사용자 채널을 해시 → 시계방향으로 가장 가까운 서버에 할당
4. 서버 추가/제거 시 인접 구간의 채널만 재배치 (K/N)
```

**가상 노드 (Virtual Node):**
- 물리 노드 3개만 Ring에 배치하면 불균등 분배 발생 (최대 3배 차이)
- 물리 노드당 150개의 가상 노드를 생성하여 Ring 위에 분산 배치
- 3개 x 150개 = 450개 포인트 → 거의 균일한 분포

**Java 구현 핵심:**
```java
// TreeMap의 ceilingEntry: O(log N)으로 시계방향 다음 노드 탐색
TreeMap<Long, VirtualNodeInfo<T>> ring = new TreeMap<>();

// 채널이 매핑될 노드 찾기
Map.Entry<Long, VirtualNodeInfo<T>> entry = ring.ceilingEntry(hash);
if (entry == null) {
    entry = ring.firstEntry();  // wrap-around: Ring의 처음으로
}
```

---

### 4-4. Pub/Sub 비대칭성 — 가장 중요한 설계 포인트

**Hash Ring은 publish 대상만 결정한다. Subscribe는 전혀 다른 문제다.**

```
[Publish 경로] — Hash Ring이 결정
사용자 A의 채널 → hash("A") → Consistent Hash Ring → Redis-1에 publish

[Subscribe 경로] — 모든 WS 서버가 모든 Redis에 연결
WS Server 1 ──subscribe──> Redis-1, Redis-2
WS Server 2 ──subscribe──> Redis-1, Redis-2

→ WS 서버 2개 x Redis 2개 = 4개의 연결 조합
→ 각 조합마다 올바른 채널을 구독해야 함
```

**왜 이런 비대칭이 생기는가:**
- 사용자 B가 사용자 A를 subscribe하려면, A의 채널이 어느 Redis에 있는지 알아야 함
- A의 채널은 Redis-1에 매핑되어 있음
- 하지만 B가 연결된 WS Server 2도 Redis-1을 subscribe 해야 함
- **결론: 모든 WS 서버가 모든 Redis 인스턴스에 연결해야 수신 가능**

---

### 4-5. 거리 계산 — Subscriber 측 필터링

**거리 계산 공식 (Euclidean Distance):**
```
distance = sqrt((x2 - x1)^2 + (y2 - y1)^2)
```

**최적화 — sqrt 생략:**
```java
// sqrt는 비싼 연산. 비교만 하면 되므로 양변을 제곱
// distance <= radius  →  distance^2 <= radius^2
dx * dx + dy * dy <= searchRadius * searchRadius
```

**왜 subscriber 측에서 계산하는가:**
```
[방법 A: Publish 시 계산] (비효율)
A가 위치 변경 → A의 친구 400명 위치를 Cache에서 조회 → 400번 거리 계산 → 가까운 친구에게만 publish
→ publish 시점에 400번의 Cache 조회 + 400번 계산 필요

[방법 B: Subscribe 시 계산] (효율적) ← 채택
A가 위치 변경 → 채널에 broadcast → 각 subscriber가 수신 → 자신의 위치와 비교 → 1번만 계산
→ 각 subscriber가 O(1)로 판단. 추가 Cache 조회 불필요.
```

---

### 4-6. 위치 캐시 & 이력 저장 — 이중 저장 전략

**Redis Cache (최신 위치):**
- Key: `location:{userId}` → Value: `{x, y, timestamp}`
- TTL: 600초 (10분)
- 업데이트마다 TTL 갱신 → 비활성 사용자 자동 만료
- **읽기 최적화**: 친구 접속 시 배치 조회로 현재 온라인 친구 위치 확보

**Cassandra (위치 이력):**
- Partition Key: `user_id` → Clustering Key: `timestamp DESC`
- 같은 사용자의 위치 이력이 물리적으로 같은 노드에 저장
- 최신 이력부터 조회하므로 DESC 정렬
- **쓰기 최적화**: 초당 1,300만 건의 append-only 쓰기에 Cassandra가 적합

```
[데이터 흐름]
위치 업데이트 도착
  ├──> Redis Cache 갱신 (동기, 빠름)
  ├──> Redis Pub/Sub publish (동기, fire-and-forget)
  └──> Cassandra 이력 저장 (비동기, 유실 허용)
```

---

## 5. 전체 데이터 흐름 요약

```
[사용자 A가 위치를 (300, 450)으로 변경]

1. Client A → WebSocket → WS Server 1
   "내 위치: (300, 450)"

2. WS Server 1 → Redis Cache
   SET location:A {x:300, y:450} EX 600

3. WS Server 1 → Consistent Hash Ring 조회
   hash("A") → Redis Pub/Sub 1번에 매핑

4. WS Server 1 → Redis Pub/Sub 1
   PUBLISH user:A:location {userId:A, x:300, y:450, path:[{node:"ws-1", ts:...}]}

5. Redis Pub/Sub 1 → WS Server 2 (subscribe 중)
   메시지 수신: {userId:A, x:300, y:450, ...}

6. WS Server 2: 거리 계산
   사용자 B의 위치 (280, 430)
   dx=20, dy=20, dx*dx+dy*dy = 800 <= 200*200 = 40000 → 반경 내!

7. WS Server 2 → Client B
   "친구 A가 (300, 450)에 있습니다"

8. (비동기) WS Server 1 → Cassandra
   INSERT INTO location_history (user_id, timestamp, x, y, ws_server)
```

---

## 6. 설계 시 고려한 Trade-off

### WebSocket 서버는 Stateful이다

| 특성 | Stateless (REST API) | Stateful (WebSocket) |
|------|---------------------|---------------------|
| 수평 확장 | 서버 추가만 하면 됨 | 연결 상태 관리 필요 |
| 서버 제거 | 즉시 가능 | Connection Draining 필요 |
| 로드밸런싱 | Round-robin 가능 | Sticky Session 또는 Pub/Sub 필요 |
| 장애 복구 | 다른 서버가 대체 | 모든 연결 재수립 필요 |

→ 이 복잡성을 감수하는 이유: **실시간 push의 지연 시간이 HTTP 폴링보다 압도적으로 낮기 때문**

### Redis Pub/Sub vs Redis Streams

| 항목 | Pub/Sub | Streams |
|------|---------|---------|
| 메시지 지속성 | 없음 (fire-and-forget) | 있음 (로그 저장) |
| 구독자 없을 때 | 메시지 유실 | 메시지 보존 |
| 소비자 그룹 | 없음 | 있음 (XREADGROUP) |
| 복잡도 | 낮음 | 높음 |
| **위치 업데이트에 적합?** | **적합** (최신 값만 의미) | 과도함 |

### 가상 노드 수의 Trade-off

| 가상 노드 수 | 분배 균일성 | 메모리 | Ring 조회 성능 |
|-------------|-----------|--------|--------------|
| 10개/노드 | 낮음 (최대 3배 불균형) | 30개 | O(log 30) |
| 150개/노드 | 높음 (5% 이내 편차) | 450개 | O(log 450) |
| 1000개/노드 | 매우 높음 | 3000개 | O(log 3000) |

→ **150개**: 균일 분포와 메모리 사이의 최적점