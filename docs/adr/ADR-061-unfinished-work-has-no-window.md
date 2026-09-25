# ADR-061 — 끝나지 않은 일에는 창이 없다: 라우트의 완료는 쓰기 때 다시 센다

| 항목 | 내용 |
|---|---|
| 상태 | Accepted (2026-09-25) |
| 결정일 | 2026-09-25 |
| 관련 문서 | `docs/DESIGN.md` §5.5 「`rm_routes.completed_at`」 · §9.1 `dawnline_routes` · `docs/benchmarks/phase7-route-progress-count.md` · `docs/IMPLEMENTATION_PLAN.md` 7-1 A6 |
| 관련 ADR | [ADR-051](ADR-051-first-fact-creates-the-row-absence-is-not-a-value.md) (결정 4 — 개수는 증감이 아니라 집계) · [ADR-060](ADR-060-metrics-come-from-the-table.md) (맥락 6 — `dawnline_routes` 가 생긴 자리) · [ADR-058](ADR-058-shipment-and-read-model-retention.md) · [ADR-059](ADR-059-dispatch-retention-is-per-plan.md) (`*_stuck` — 끝나지 않은 일은 센다) |

---

## 맥락

`dawnline_routes{camp, status}` 는 7-1(#68)에서 들어왔다. 「완료」는 `rm_routes.status` 의 칸이 아니라 판정이라
(`ASSIGNED` · `DEPARTED` 둘뿐이다), 1분 갱신이 매번 라우트마다 `rm_orders` 에서 결과 없는 주문을 찾았다
(`LATERAL … LIMIT 1`). 그 탐색 수를 묶으려고 **계획 출발이 KPI 창(24 버킷) 안인 라우트만** 셌다.

1. **창이 가장 먼저 봐야 할 라우트를 뺐다.** 출발한 지 30시간 된 라우트가 아직 끝나지 않았다면 그것이 운영자가 먼저 볼
   라우트인데, 창 밖이라 `in_progress` 에서 빠진다. 그 주문은 90일 뒤에야 `dawnline_rm_orders_stuck` 에 잡힌다. 근거:
   관측(재현됨) — 운영 크기(`rm_routes` 112,520 · `rm_orders` 1,361만)에서 참값은 `in_progress` 471(창 안 416 · 창 밖 55)인데
   #68 의 질의는 416 을 냈다.
2. **읽기 쪽에서 창을 걷으면 비용이 보존 기간을 따라간다.** 같은 질의에서 창만 빼면 매분 11만 라우트를 탐색한다 — 890–933 ms,
   버퍼 읽기 22만(약 1.7 GB). 근거: 관측(재현됨).
3. #68 의 측정 문서가 「남은 비용의 절반은 `rm_routes` 순차 스캔(약 53 ms)」이라고 적은 것은 **JIT 컴파일이었다.** `LATERAL`
   형태의 비용 추정(85만)이 `jit_above_cost`(10만)를 넘어 매 실행 JIT 가 켜졌고, 같은 질의가 `jit = off` 에서 98.8 → 12.8 ms 다.
   근거: 관측(재현됨). 결정을 바꾸지는 않지만 그 문서의 숫자를 읽는 법이 바뀐다.

끝나지 않은 일에 창을 두지 않는 것은 이 저장소에 이미 있는 문장이다 — `dawnline_fulfillment_orders_stuck` 에 상한을 두지 않은
이유(§9.1)와 같다.

## 결정

### 1. 「완료」는 사건 시점에 그 라우트만 다시 세어 적는다 — `rm_routes.completed_at`

판정을 매분 읽기에서 하지 않고, 판정의 입력이 바뀔 때 **그 라우트 하나를** 다시 센다. ADR-051 결정 4(증감이 아니라 집계)를
행 단위로 적용한 것이고 `completed_count` · `failed_count` 와 같은 자리(`RouteRows.recount`)에서 같은 질의가 쓴다.

- **값**: 그 라우트에 결과 없는 비취소 주문이 **없으면** 주문들의 마지막 결과 시각(`max(COALESCE(delivered_at, failed_at))`),
  있으면 `NULL`. 결과가 하나도 없는데 남은 주문도 없으면(빈 라우트 · 전부 취소) 계획 출발 시각이다 — 「끝나지 않았다」로
  남기면 출발한 빈 라우트가 영원히 `in_progress` 다.
- **값은 사실에서 온다 — 처리 시각이 아니다.** 입력은 전부 `rm_orders` 의 사실 칸과 `planned_departure`(개정으로 거른 계획 칸)
  이고, 소비자의 `now()` 를 쓰지 않는다. 그래서 순서와 무관하다 — `ProjectionShuffleIT` 가 이 칸을 다른 집계 칸과 같이 본다.
- **계획이 도착한 라우트만** 적는다(`revision IS NOT NULL`) — `recount` 의 기존 규칙 그대로다. 소속을 모르는 동안의 「남은 것
  없음」은 「끝났다」가 아니라 「모른다」다.

### 2. 다시 세는 자리는 판정의 입력이 바뀌는 사실 셋이다

| 사실 | 바뀌는 입력 | 다시 세는 라우트 |
|---|---|---|
| `route.assigned` | 라우트의 주문 집합 · 계획 출발 | 그 라우트와, 옮겨 온 주문이 떠나온 라우트(기존) |
| `delivery.status`(결과) | 주문의 결과 | 주문의 **지금 계획상** 라우트(기존, ADR-047) |
| `order.cancelled` | 주문이 「남은 주문」에서 빠진다 | 주문의 지금 라우트 — **새로 더한다** |

`order.cancelled` 가 앞에 오고 `route.assigned` 가 뒤에 오면, 뒤의 재집계가 그 취소를 본다 — 어느 순서든 마지막으로 도착한
사실이 전체를 다시 센다.

### 3. 읽기 — 끝나지 않은 것은 창 없이, 끝난 것과 출발 전은 창 안에서

`dawnline_routes` 의 네 값:

- `unknown` — 계획이 오지 않았다(`revision IS NULL OR status IS NULL`). **창 없음.**
- `in_progress` — `status = 'DEPARTED' AND completed_at IS NULL`. **창 없음.**
- `assigned` — `status = 'ASSIGNED'`. 계획 출발이 KPI 창의 첫 버킷 이후.
- `completed` — `status = 'DEPARTED' AND completed_at IS NOT NULL`. 계획 출발이 KPI 창의 첫 버킷 이후.

앞의 둘은 끝나지 않은 일이라 창이 없고, 뒤의 둘은 수를 묶는 창이 맞다. 질의는 `rm_routes` 하나만 읽는다.

`in_progress` 의 출발 판정은 `departed_at` 이 아니라 `status` 다. `departed_at` 은 `delivery.route-departed` 하나만 쓰고,
`status` 의 `DEPARTED` 는 그것과 `delivery.status` 둘이 쓴다(§5.5 — 배송이 있었다면 출발한 것이다). 출발 이벤트가 늦거나
유실된 라우트도 배송이 시작됐으면 진행 중이다.

### 4. 인덱스를 더하지 않는다 — 행 수와 함께 (불변규칙 11)

운영 크기(`rm_routes` 112,520, 백필 뒤 38 MB)에서 새 질의는 `rm_routes` 순차 스캔 한 번이고 **5.2–8.2 ms** 다. 비용 추정이
6,960 이라 JIT 도 켜지지 않는다. 끝나지 않은 라우트의 부분 인덱스(`… WHERE completed_at IS NULL AND status IS DISTINCT FROM
'ASSIGNED'`, 16 kB)를 만들어 두 형태로 쟀다.

- 한 문장의 `OR` 형태: 플래너가 그 인덱스를 쓰지 않는다.
- `UNION ALL` 로 가른 형태: 그 인덱스를 쓰지만 창 쪽 분기가 여전히 순차 스캔이라 **4.9 ms** 다.

인덱스가 값을 하려면 `planned_departure` 인덱스까지 둘이 필요하고, 사는 것은 1분에 5 ms 다. 보존 90일이 `rm_routes` 를 약
11만 행에서 멈추게 하므로(ADR-058) 이 순차 스캔의 크기에는 상한이 있다.

**#68 의 「계획 모양 회귀를 작은 픽스처가 못 잡는다」는 사라진다.** 질의가 `rm_orders` 를 읽지 않으므로 되돌아갈 해시
서브플랜이 없다. 대신 IT 가 **계획에 `rm_orders` 가 없다**를 본다 — 그것은 계획 선택이 아니라 문장의 구조라서 작은 픽스처에서도
참·거짓이 갈린다.

### 5. 쓰기의 대가는 재지 않을 만큼 작다 — 쟀다

재집계는 이미 결과 사실마다 한 번 돈다. 칸 둘을 세던 두 부분 질의가 셋을 세는 한 부분 질의가 됐다 — 라우트 하나(주문 121)
에서 **0.04–0.19 ms**, 이전 형태 0.07–0.15 ms. 같은 범위다. 백필(V8)은 운영 크기에서 1.3 초다.

## 고려한 대안과 기각 이유

| 대안 | 기각 이유 |
|---|---|
| **A** — 읽기에서 창만 걷는다 | 매분 890–933 ms · 1.7 GB(맥락 2). 비용이 보존 기간을 따라 커진다 |
| **B** — 창을 넓힌다(예: 7일) | 창 밖이 여전히 있다. 끝나지 않은 일에는 어떤 길이의 창도 맞지 않는다 — 8일째의 라우트가 같은 자리에 선다 |
| **C** — 남은 주문 수(`pending_count`)를 칸으로 둔다 | 결과 사실마다 값이 바뀌어 그 칸을 술어로 쓰는 인덱스가 생기면 HOT 갱신을 매번 잃는다. `completed_at` 은 라우트당 한 번 `NULL → 값` 으로 바뀐다. 그리고 완료 시각은 지도와 런북이 쓸 사실이다 |
| **D** — `completed_at` 을 처리 시각(`clock.instant()`)으로 적는다 | 처리 순서를 탄다 — 뒤섞으면 값이 달라지고 `ProjectionShuffleIT` 가 빨갛다. 판정 키는 언제나 사실을 낸 쪽의 시계다(§5.5) |
| **E** — `in_progress` 를 `departed_at IS NOT NULL` 로 판정한다 | 출발 이벤트 하나에만 기댄다. 그것이 늦거나 유실되면 배송 중인 라우트가 어느 값에도 들지 않는다(결정 3) |

## 결과

- `rm_routes` 에 칸 하나(`completed_at`, V8). 핸들러가 쓰지 않는 집계 칸이라 `RouteColumn` 에 없다.
- `order.cancelled` 핸들러가 라우트 행을 잠근다 — 잠금 순서(주문 → 라우트)는 그대로다.
- `dawnline_routes{status="in_progress"}` 가 창 밖의 끝나지 않은 라우트를 센다. 대시보드의 패널 식은 바뀌지 않는다.
- 끝나지 않은 라우트는 보존이 지우지 않는다(결과 없는 주문이 남아 있어 라우트 정리의 가드가 막는다) — 그래서 `in_progress` 는
  해소되거나 365일 상한에 닿을 때까지 남는다. 그것이 이 결정의 요점이다.

## 재검토 지점

- `rm_routes` 순차 스캔이 1분 갱신의 의미 있는 몫이 될 때 — 대략 100 ms, 행 수로 약 200만(보존 기간이 늘거나 캠프가 크게
  늘 때). 그때는 결정 4 의 두 인덱스를 다시 잰다.
- 재배송이 들어와 한 주문이 결과를 둘 갖게 될 때 — 결과 시각의 `max` 가 무엇을 뜻하는지 다시 정한다(§5.5 의
  `ck_rmo_outcome_time_exclusive` 와 같은 조건).
- 출발 전(`assigned`)도 창이 있다. 계획 출발을 한참 넘기고도 출발하지 않은 라우트는 창 밖으로 빠진다. 지금은 빈 라우트(재계획이
  주문을 전부 옮긴 라우트)가 영원히 `assigned` 로 남지 않게 하는 쪽을 택했다. 출발 지연이 운영 절차에 들어오면 이 값도 다시 본다.
