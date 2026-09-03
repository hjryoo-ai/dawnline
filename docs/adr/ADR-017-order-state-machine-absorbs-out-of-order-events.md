# ADR-017 — 주문 상태 머신이 순서 뒤바뀜을 흡수한다

| 항목 | 내용 |
|---|---|
| 상태 | Accepted |
| 결정일 | 2026-09-02 |
| 관련 문서 | `docs/DESIGN.md` §4.1 · §4.5 · §4.6 · §5.1 · `CLAUDE.md` 불변 규칙 2·6 |
| 관련 ADR | [ADR-006](ADR-006-at-least-once-idempotent-consumer.md) (at-least-once·멱등 소비자) |

---

## 맥락

order-service 는 두 이벤트를 소비해 주문 상태를 옮긴다(§5.1).

| 토픽 | 파티션 키 | 발행자 |
|---|---|---|
| `dawnline.order.dispatched.v1` | **orderId** | dispatch-service (Phase 3) |
| `dawnline.delivery.status.v1` | **routeId** | tracking-service (Phase 5) |

키가 다르므로 두 토픽의 레코드는 서로 다른 파티션에 들어간다. §4.5가 정한 대로
**서로 다른 키 간 순서는 보장되지 않는다**. 즉 한 주문에 대해 이런 순서가 실제로 가능하다.

```
delivery.status(COMPLETED)   ← 먼저 도착
order.dispatched             ← 나중 도착
```

이때 주문은 아직 `PLANNED` 이고, 기존 상태 머신에는 `PLANNED → DELIVERED` 전이가 없다.
`markDelivered` 가 `IllegalStateTransitionException` 을 던지고, §4.6의 재시도 3회(200ms·1s·5s)를
소진한 뒤 **정상적으로 배송 완료된 주문이 DLQ 로 간다**. 재시도가 도움이 되지 않는 이유는
`order.dispatched` 가 6초 안에 온다는 보장이 어디에도 없기 때문이다.

§4.5는 이 상황을 예고하며 "소비자는 이를 전제로 설계한다 → 상태 머신으로 흡수"라고만 적어 두었다.
**무엇을 어떻게 흡수하는지는 정해져 있지 않았다.** 이 ADR이 그것을 정한다.

기존 상태 머신은 정의되지 않은 전이를 <em>전부</em> 거부한다. 그 엄격함 자체는 불변 규칙 6이
요구하는 것이고 지킬 값어치가 있다 — 느슨하게 만들어 "아무 전이나 허용"으로 가면 진짜 버그가
숨는다. 문제는 엄격함이 아니라 **표가 불완전하다**는 것이다.

## 결정

### 1. `PLANNED → DELIVERED` 와 `PLANNED → FAILED` 를 정식 전이로 추가한다

억지 보정이 아니라 **사실을 반영하는 것**이다. 배송이 완료됐다면 배송은 시작된 것이고,
`DISPATCHED` 를 거치지 않았다는 것은 그 사건을 알리는 메시지가 아직 안 왔다는 뜻일 뿐이다.
주문의 실제 진행은 이미 그 지점을 지났다.

### 2. 전이 시도의 결과를 세 갈래로 나눈다

진행 단계(progress)를 정의한다: `PLACED(0) → PLANNED(1) → DISPATCHED(2) → DELIVERED·FAILED(3)`.
`CANCELLED` 는 이 축 위에 있지 않다.

| 상황 | 판정 | 처리 |
|---|---|---|
| 표에 있는 전이 | 적용 | 상태 변경 후 커밋 |
| 목표 상태의 진행 단계가 현재보다 **앞이거나 같음** | 철 지난 이벤트 | 무시하고 커밋, `debug` 로그 + `dawnline_event_stale_total{consumer,eventType}` |
| 그 밖 | 비즈니스 규칙 위반 | 무시하고 커밋, `warn` + `dawnline_event_processed_total{outcome=rejected}` |

두 번째가 뒤늦게 도착한 `order.dispatched` 를 처리한다. 주문이 이미 `DELIVERED`(3)인데
`DISPATCHED`(2)로 가라는 요청은 과거를 가리키므로 버린다.

세 번째는 §4.6 표 3행("비즈니스 규칙 위반 → DLQ 아님, 무시하고 warn + 메트릭")의 경로를 그대로
쓴다. `libs/messaging` 의 `EventRejectedException` 이 이미 그 계약이다 —
`IdempotentConsumer` 가 잡아 `outcome=rejected` 로 기록하고 트랜잭션을 커밋한다.

### 3. `CANCELLED` 를 진행 축에서 뺀 것은 의도적이다

취소된 주문에 `order.dispatched` 가 오는 상황은 §4.5가 직접 든 예다. 이것을 "철 지난 이벤트"로
분류해 조용히 버리면 안 된다. **취소된 주문의 소포가 실제로 차에 실려 있다**는 뜻이고, 누군가
회수해야 한다. `rejected` 메트릭으로 올려 알림이 걸리게 한다.

## 근거

- **DLQ 는 사람이 손댈 것이 있을 때만 쓴다.** 순서 뒤바뀜은 정상 동작이므로 운영자가 할 일이 없다.
  정상 배송이 DLQ 에 쌓이면 진짜 문제가 그 안에 묻힌다.
- **재시도로는 못 고친다.** `order.dispatched` 의 도착 시각에 상한이 없다. 재시도 창을 늘리면
  리스너 스레드만 오래 잡고 결과는 같다.
- **상태 머신의 엄격함은 유지된다.** 표를 넓힌 것이지 검사를 없앤 것이 아니다.
  `DELIVERED → PLANNED` 같은 진짜 역행은 여전히 거부된다.
- **불변 규칙 2와 겹치지 않는다.** `processed_events` 는 <em>같은 이벤트</em>의 중복 배달을 막고,
  이 규칙은 <em>다른 이벤트</em>의 순서 뒤바뀜을 다룬다. 둘은 서로를 대신하지 못한다.

## 고려한 대안과 기각 이유

| 대안 | 기각 이유 |
|---|---|
| **대기 후 재시도** (§4.6 백오프에 맡긴다) | `order.dispatched` 가 6초 안에 온다는 보장이 없다. 늦으면 정상 배송이 DLQ 로 간다. 지연이 커질수록 실패율이 올라가는데, 지연이 커지는 때가 하필 피크다 |
| **`delivery.status` 의 키를 orderId 로 변경** | 근본 해결이지만 대가가 크다. 한 stop 에 여러 주문이 묶이므로(§6.2) 발행자가 주문당 하나씩 쪼개야 하고, tracking·ops 가 의존하는 <em>라우트 단위</em> 순서 보장이 깨진다. 한 소비자의 편의를 위해 다른 두 소비자의 계약을 바꾸는 셈이다 |
| **모든 전이를 허용하고 마지막 이벤트가 이기게** | 상태 머신을 사실상 없애는 것이다. 불변 규칙 6 위반이고, `DELIVERED → PLACED` 같은 진짜 버그가 조용히 통과한다 |
| **이벤트에 시퀀스 번호를 넣어 소비자가 재정렬** | 버퍼와 타임아웃이 필요하고, 아직 안 온 이벤트를 언제까지 기다릴지가 다시 미정이다. 계약도 무거워진다. 이 문제의 크기에 비해 과하다 |

## 결과

**좋아지는 것**

- 순서가 뒤바뀌어도 정상 배송이 DLQ 로 가지 않는다.
- 취소된 주문의 배송처럼 <em>진짜</em> 이상한 상황만 알림에 남는다.
- 상태 머신의 전이표가 여전히 한 곳(`OrderStatus.allowedTransitions()`)에 있고, 그 표 전체를
  훑는 테스트가 계속 성립한다.

**비용**

- 전이표가 넓어졌다. `PLANNED → DELIVERED` 는 정상 흐름에서는 나타나지 않는 경로라, 이 표만
  보고는 왜 있는지 알 수 없다. 그래서 §5.1과 이 ADR을 코드 주석에서 가리킨다.
- `DISPATCHED` 를 건너뛴 주문은 그 상태에 머문 기록이 남지 않는다. 주문 타임라인(§5.1
  `GET /orders/{id}`)에 배송 시작 시각이 비는 경우가 생긴다. `order.dispatched` 가 나중에
  도착하면 그 시각을 별도로 채우는 것은 Phase 5(추적 화면)에서 필요해지면 다룬다.
- 진행 단계 비교가 상태 머신에 개념 하나를 더한다. 상태가 늘 때 전이표와 진행 단계 <em>둘 다</em>
  갱신해야 하며, 빠뜨리면 철 지난 판정이 틀린다. 테스트가 두 정의의 일관성을 검사한다.


---

## [후속 — Phase 1-6] stale 을 세는 카운터를 따로 둔다

구현하며 한 가지를 더 정했다. 철 지난 이벤트를 `debug` 로그로만 남기면 **그 일이 얼마나 자주
일어나는지 아무도 모른다.** 순서 뒤바뀜 자체는 정상이지만, 빈도가 갑자기 늘면 어딘가에서 지연이
커졌다는 신호다 — 그것을 보려면 세어야 한다.

`dawnline_event_stale_total{consumer,eventType}` 를 추가한다(§9.1). 기존
`dawnline_event_processed_total{outcome=rejected}` 와 합치지 않는 이유는 알림 때문이다:
**stale 은 늘 조금씩 늘고, rejected 는 0이어야 한다.** 한 카운터에 섞으면 어느 쪽에도 임계값을
걸 수 없다.

`outcome` 라벨에 `stale` 을 더하는 방법도 있었지만 그러지 않았다. 그 라벨은 "멱등 소비의 결과"
(ok/dup/rejected/dlq)를 뜻하고, stale 은 그 축이 아니라 **상태 머신의 판정**이다.
이벤트는 정상적으로 <em>처리</em>됐고(커밋됐고) 다만 상태를 바꾸지 않았을 뿐이다.


## [후속 — Phase 1-6] 건너뜀은 `PLANNED` 에서만이 아니다

이 ADR 의 §1 은 `PLANNED → DELIVERED`·`PLANNED → FAILED` 두 전이만 추가했다. 리스너를 만들며
전이표를 다시 보니 **같은 결함이 한 칸 앞에 그대로 남아 있었다.**

order-service 가 소비하는 이벤트는 셋이고(§4.1) 모두 다른 토픽이다.

| 토픽 | 키 |
|---|---|
| `dawnline.fulfillment.planned.v1` | orderId |
| `dawnline.order.dispatched.v1` | orderId |
| `dawnline.delivery.status.v1` | routeId |

앞의 둘은 키가 같지만 <em>토픽이 다르므로</em> 같은 파티션이 아니다. 순서 보장은 파티션 안에서만
성립한다(§4.5). 따라서 `fulfillment.planned` 가 늦으면 주문은 `PLACED` 인 채로 `order.dispatched`
나 `delivery.status` 를 먼저 받는다. 원래 표대로면 그 순간 정상 배송이 "비즈니스 규칙 위반" 으로
분류돼 `warn` 과 알림용 메트릭에 올라간다 — 이 ADR 이 없애려던 바로 그 결과다.

**규칙을 상태 쌍이 아니라 축으로 적는다.** 진행 축에서 앞으로 가는 전이는 전부 허용한다.

```
PLACED     → PLANNED, DISPATCHED, DELIVERED, FAILED   (+ CANCELLED)
PLANNED    → DISPATCHED, DELIVERED, FAILED            (+ CANCELLED)
DISPATCHED → DELIVERED, FAILED
```

취소는 이 규칙의 예외로 남는다 — 이벤트가 아니라 명령이고, `DISPATCHED` 이후에는 소포가 이미
차에 실린 뒤라 막아야 한다. `OrderStatusTest` 가 두 가지를 함께 검사한다: 표가 축 규칙과 정확히
같은가, 그리고 취소 제약이 표에서 축 규칙을 어떻게 벗어나는가.

**잃는 것**: `PLACED → DELIVERED` 가 "계획도 안 된 주문이 배달됐다" 는 진짜 이상 상황일 가능성도
있는데, 이제 그것을 정상으로 받는다. 그러나 <em>구분할 방법이 없다</em> — 늦은
`fulfillment.planned` 와 구별되지 않기 때문이다. 구분할 수 없는 것을 구분하는 척하는 규칙보다
받아들이는 규칙이 낫고, 정말 이상한 상황은 `CANCELLED` 축 밖 판정이 계속 잡는다.
