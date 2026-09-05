# ADR-022 — fulfillment 는 주문 단위 애그리거트를 갖는다 (`fulfillment_orders`), `wave_orders` 는 드롭

| 항목 | 내용 |
|---|---|
| 상태 | Accepted |
| 결정일 | 2026-09-05 |
| 관련 문서 | `docs/DESIGN.md` §4.5 · §4.6 · §5.2 · §9.1 · `CLAUDE.md` 불변 규칙 3·4·6·11 |
| 관련 ADR | [ADR-018](ADR-018-idempotency-lock-in-redis-record-in-db.md) (동시 INSERT 패턴), [ADR-020](ADR-020-cutoff-ownership-wave-grace-promise-revision.md) (약속 개정), [ADR-021](ADR-021-zone-seed-derived-from-geocoder.md) (대체 FC 선택) |

---

## 맥락

§5.2 의 테이블에서 주문에 대한 것은 `wave_orders(wave_id, order_id, fc_id, zone_id, added_at)`
하나뿐이다. 그런데 fulfillment 가 한 주문에 대해 **아는 것은 그보다 많다.**

| fulfillment 가 아는 것 | `wave_orders` 에 있나 |
|---|---|
| 어느 웨이브·FC·권역인가 | ✅ |
| 그 웨이브의 컷오프는 언제인가 (ADR-020) | ❌ |
| 왜 `UNSERVICEABLE` 인가 (§5.2 6단계) | ❌ — 행 자체가 생기지 않는다 |
| 약속이 개정됐는가 (ADR-020 `promiseRevised`) | ❌ |
| 홈 FC 가 어떤 필터에서 떨어져 대체가 일어났는가 (ADR-021) | ❌ |
| 취소됐는가 | ❌ — 행을 지우면 "없던 주문" 과 같아진다 |

즉 `wave_orders` 는 **절반짜리 애그리거트**다. 나머지 절반은 `fulfillment.planned` 이벤트로 나가고
나면 이 서비스의 DB 에 남지 않는다. 그래서 가장 흔한 운영 질문에 답할 수 없다 —

> **"주문 X 는 왜 웨이브에 없나?"**
> `UNSERVICEABLE` 이었나, 취소됐나, `order.placed` 가 아직 안 왔나. 셋이 구별되지 않는다.

여기에 순서 역전이 겹친다. `order.placed` 와 `order.cancelled` 는 키가 같지만(orderId)
**다른 토픽**이라 순서가 보장되지 않는다(§4.5). 취소가 먼저 오면 그 사실을 적어 둘 곳이 없다.

### 처음에 떠올린 답이 왜 틀렸나

자연스러운 반응은 "취소 마커 테이블을 하나 만들고, `wave_orders` 에 `order_id` 인덱스를 붙이자"
이다. 그러면 취소는 처리되고 `order_id` 로 찾는 경로도 생긴다. 그러나

- 같은 주문에 대한 사실이 **두 테이블에 흩어진다** — 웨이브 소속은 여기, 취소는 저기.
- 위의 질문은 **여전히 답할 수 없다.** `UNSERVICEABLE` 은 어느 쪽에도 없다.
- 인덱스가 하나 더 필요해진다(불변규칙 11).

빠져 있던 것은 마커가 아니다. **주문 단위 애그리거트다.** 마커는 그 애그리거트의 특수한
상태(`CANCELLED` + `placed_event_id IS NULL`)일 뿐이다.

---

## 결정

### 1. `fulfillment_orders` — 주문 하나당 행 하나

```sql
CREATE TABLE fulfillment_orders (
  order_id             UUID PRIMARY KEY,
  status               VARCHAR(16) NOT NULL,          -- PLANNED | UNSERVICEABLE | CANCELLED
  wave_id              UUID REFERENCES waves(id),     -- status=PLANNED 일 때만 채운다
  camp_id              UUID,
  fc_id                UUID,
  zone_id              UUID,
  cutoff_at            TIMESTAMPTZ,
  promised_start       TIMESTAMPTZ,
  promised_end         TIMESTAMPTZ,
  promise_revised      BOOLEAN NOT NULL DEFAULT FALSE,
  unserviceable_reason VARCHAR(24),
  fc_fallback_reason   VARCHAR(16),
  placed_event_id      UUID,                          -- NULL 이면 order.placed 가 아직 오지 않았다
  cancelled_at         TIMESTAMPTZ,
  version              BIGINT NOT NULL DEFAULT 0,     -- 낙관적 락
  created_at           TIMESTAMPTZ NOT NULL,
  updated_at           TIMESTAMPTZ NOT NULL
);

CREATE INDEX ix_fulfillment_orders_wave ON fulfillment_orders (wave_id) WHERE status = 'PLANNED';
```

`status` 에 `CHECK` 를 걸지 않는다. 상태 전이는 애그리거트 메서드가 강제하고(불변규칙 6),
DB 제약은 그 규칙의 <em>일부</em>만 복제해 두 곳이 어긋날 여지를 만든다. `orders` 테이블도 같다.

`service_tier` 를 두지 않는다. 웨이브에 실린 주문의 티어는 `waves.service_tier` 로 닿고,
`UNSERVICEABLE`·`CANCELLED` 행은 웨이브가 없으니 티어를 물을 일이 없다. 발행하는
`fulfillment.planned` 의 `serviceTier` 는 처리 중인 `order.placed` 페이로드에서 온다.

### 2. `wave_orders` 는 드롭한다 (V2)

웨이브 소속은 이제 `wave_id IS NOT NULL AND status = 'PLANNED'` 로 정의된다.

**"한 주문은 최대 한 웨이브" 를 PK 가 더 강하게 보장한다.** `wave_orders` 의 복합 PK
`(wave_id, order_id)` 는 같은 주문이 <em>서로 다른 두 웨이브</em>에 들어가는 것을 막지 못한다 —
그 둘은 서로 다른 키다. `fulfillment_orders` 의 `order_id` 단독 PK 는 그것을 구조적으로 막는다.

방금 만든 빈 테이블을 지우는 마이그레이션이 이력에 남는다. 감추지 않는다 — V1 을 고치는 것은
불변규칙 13 이 금지하고(예외 없음), 무엇보다 **그것이 실제로 일어난 일이다.**

### 3. 취소의 두 순서를 나눠 처리한다

| 순서 | 웨이브 상태 | 처리 |
|---|---|---|
| **취소 선착** (`order.cancelled` → `order.placed`) | — | `status=CANCELLED`, `placed_event_id=NULL` 행을 만든다. 뒤에 온 `order.placed` 는 그 행을 보고 **무시**하고 `dawnline_event_rejected_total{reason="cancelled_before_placed"}` 를 올린다 (§4.6 — DLQ 아님) |
| **취소 후착** | `OPEN` | `CANCELLED` 로 전이하고 `waves.order_count` 를 줄인다 (기존 `FOR UPDATE` 짧은 트랜잭션 경로) |
| **취소 후착** | `CLOSING` / `CLOSED` / 이후 | 상태만 `CANCELLED` 로 두고 **카운트는 건드리지 않는다.** `wave.closed` 가 이미 그 `orderCount` 로 나갔고, 지금 줄이면 발행된 이벤트와 DB 가 어긋난다. 후보 제거는 §4.1 대로 **dispatch 가 자기 `order.cancelled` 소비로** 한다 |

세 번째 행이 이 결정의 핵심이다. 마감된 웨이브의 숫자를 나중에 고치면 "그때 그 웨이브에 몇 건이
있었나" 에 두 개의 답이 생긴다. 이미 나간 이벤트가 진실이다.

### 4. 동시 도착은 PK 가 직렬화한다

두 리스너(`order.placed`·`order.cancelled`)가 같은 `order_id` 로 동시에 INSERT 하면 PK 에서
한쪽이 **대기**한다. `INSERT … ON CONFLICT DO NOTHING` 후 재조회하고 상태 머신을 적용한다.

이것은 새 패턴이 아니다 — [ADR-018](ADR-018-idempotency-lock-in-redis-record-in-db.md) 이
`idempotency_keys` 에서 쓰고 근거를 측정으로 남긴 바로 그 패턴이다(`DO NOTHING` 은 충돌 상대가
커밋될 때까지 기다렸다가 0행을 돌려준다).

### 5. 인덱스는 부분 인덱스 하나뿐이다

`ix_fulfillment_orders_wave (wave_id) WHERE status='PLANNED'` — 웨이브 마감 시 후보를 모으는
조회를 위한 것이고, 마감된·취소된 주문은 그 조회의 대상이 아니라 인덱스에서도 뺀다.
불변규칙 11 에 따라 EXPLAIN 을 붙인다(Phase 2-4, 영속성 단계).

**원래 질문이던 "`order_id` 로 찾는 경로" 는 사라진다.** 그것이 PK 가 되었다.

**[후속 정정 — Phase 2-4]** 미뤄 둔 EXPLAIN 이 이 결정을 뒤집었다. **부분 조건을 뺀 전체 인덱스
`ix_fulfillment_orders_wave (wave_id)` 로 간다**
([측정](../benchmarks/phase2-fulfillment-orders-indexes.md) §3).

두 가지가 근거를 무너뜨린다.

1. **부분 조건이 거르는 게 거의 없다.** 정상 상태에서 98% 가 `PLANNED` 다(취소 2%, 배차 불가
   0.5%). 게다가 `wave_id` 는 웨이브 1,240개에 주문 4.65M 이라 중복 제거가 잘 들어, 전체를 담아도
   32 MB · 부분이 31 MB 다. **1 MB 를 위해 붙어 있는 조건이었다.**
2. **부분 인덱스는 FK 검사에 쓰이지 못한다.** `wave_id → waves(id)` 는 부모를 지울 때마다
   PostgreSQL 이 참조 행을 찾는데, 플래너는 부분 인덱스의 술어(`status='PLANNED'`)가 그
   검사(모든 상태)를 덮는다는 것을 증명할 수 없다. 그래서 **웨이브 한 건마다 4.65M 행을 순차
   스캔한다** — ADR-023 의 90일 정리에서 40건 삭제에 FK 트리거만 **6.7초**였다(전체 인덱스는
   0.57 ms). 이 관점에서 부분 인덱스는 인덱스가 없는 것과 같다.

"조회 대상이 아닌 행을 인덱스에서 뺀다" 는 원칙 자체는 옳다. 틀린 것은 **이 컬럼에 그것을 적용한
판단**이었다 — 뺄 행이 2% 뿐이고, 그 대가로 외래 키가 인덱스를 잃었다.

**원래 질문이던 "`order_id` 로 찾는 경로" 는 사라진다.** 그것이 PK 가 되었다.

### 6. 거부 사유는 기존 카운터 쌍으로 낸다 — `event_processed` 에 라벨을 더하지 않는다

`cancelled_before_placed` 같은 사유는 이미 있는 짝으로 낸다.

- `dawnline_event_processed_total{consumer,eventType,outcome="rejected"}` — 무슨 일이 몇 번
- `dawnline_event_rejected_total{reason}` — 왜

`event_processed` 에 `reason` 라벨을 더하지 않는 이유는 취향이 아니다. Micrometer 의
Prometheus 레지스트리는 **같은 이름의 미터가 서로 다른 태그 키 집합을 갖는 것을 거부한다**
(`micrometer-registry-prometheus` 의 실제 메시지: *"Prometheus requires that all meters with the
same name have the same set of tag keys."*). `reason` 을 붙이려면 `ok`·`dup`·`dlq` 에도 전부
붙여야 하고, 그러면 의미 없는 `reason="none"` 이 대부분을 차지한다.

**`dawnline_event_rejected_total` 은 §9.1 표에 없었다.** §4.6·§5.1 본문은 쓰고 있는데 표에만
빠져 있었다. 이 ADR 에서 표에 넣는다.

---

## 고려한 대안과 기각 이유

| 대안 | 기각 이유 |
|---|---|
| **취소 마커 전용 테이블 + `wave_orders(order_id)` 인덱스** | 같은 주문의 사실이 두 곳에 흩어지고, "주문 X 는 왜 웨이브에 없나" 는 여전히 답할 수 없다(`UNSERVICEABLE` 이 어느 쪽에도 없다). 인덱스도 하나 더 든다 |
| **`wave_orders` 를 유지하고 컬럼만 늘린다** | PK 가 `(wave_id, order_id)` 라 **웨이브가 없는 상태를 표현할 수 없다** — `UNSERVICEABLE` 과 취소 선착이 갈 곳이 없다. `wave_id` 를 nullable 로 바꾸면 PK 가 깨진다 |
| **상태를 이벤트로만 두고 DB 에 남기지 않는다** | 재처리와 운영 질의에서 답할 수 없고, 순서 역전을 흡수할 방법이 없다. 이벤트는 흐르는 것이지 물어볼 수 있는 것이 아니다 |
| **`processed_events` 로 취소 선착을 대신한다** | 그것은 멱등성 기록이고 14일 뒤 정리된다(§4.4). "이 이벤트를 처리했다" 와 "이 주문은 취소됐다" 는 의미도 보존 기간도 다르다 |
| **order-service 에 물어본다** | 불변규칙 4 — 코어 서비스 간 동기 호출 금지. 필요한 것은 이벤트 페이로드 스냅샷이나 자기 DB 프로젝션이다 |
| **취소 후착 시 마감된 웨이브의 `order_count` 도 줄인다** | 이미 발행된 `wave.closed` 의 `orderCount` 와 DB 가 어긋난다. "그때 몇 건이었나" 에 답이 둘이 된다 |

---

## 결과

**장점**

- "주문 X 는 왜 웨이브에 없나" 를 fulfillment DB 한 곳에서 답할 수 있다.
- 순서 역전이 **특별한 장치 없이** 흡수된다. 취소 선착은 애그리거트의 한 상태일 뿐이다.
- "한 주문은 최대 한 웨이브" 가 구조적으로 보장된다.
- 인덱스가 늘지 않는다. `order_id` 조회는 PK 다.

**비용**

- 방금 만든 `wave_orders` 를 지우는 V2 가 이력에 남는다.
- **주문마다 행이 하나 쌓인다.** §8.1 피크는 150,000 주문/일이고, 이 표에는 보존 정책이 없다.
  `idempotency_keys` 가 ADR-019 로 7일 보존을 정한 것과 같은 문제이며, `waves` 에도 아직 없다.
  → **남는 결정**: `fulfillment_orders`·`waves` 의 보존 정책. 하류(dispatch)가 계획을 끝낸
  웨이브의 주문 행을 언제까지 두는가. Phase 2 를 닫기 전에 정해야 할 항목으로 남긴다.
- `dawnline_event_rejected_total` 의 태그가 `reason` 하나뿐이라 **어느 소비자가 거부했는지**
  알 수 없다. 지금은 사유 문자열이 소비자를 함의하지만, 거부하는 소비자가 둘 이상 생기면
  `consumer`·`eventType` 을 붙여야 한다(`IdempotentConsumer` 가 그 시점에 둘 다 안다).

**되돌리는 방법**

`fulfillment_orders` 는 이 서비스의 애그리거트 루트가 되므로 되돌리는 것은 사실상 재설계다.
되돌릴 일이 생긴다면 그것은 "fulfillment 가 주문 단위 상태를 갖지 않는다" 는 전제로 돌아가는
때이고, 그때는 순서 역전과 `UNSERVICEABLE` 조회를 어떻게 할지부터 다시 정해야 한다.
