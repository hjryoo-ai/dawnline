# `route_stop_orders` 를 주문으로 찾는다 — `ix_rso_order` (EXPLAIN)

CLAUDE.md 불변규칙 11 에 따른 근거 자료다. 여기서 **인덱스를 하나 넣는다**
(`V9__route_stop_orders_order_index.sql`, DESIGN.md §5.3 반영).

## 계기

[ADR-047](../adr/ADR-047-delivery-status-is-a-fact-not-a-revision.md) 결정 2 가 stop 을
**주문으로** 찾게 했다. 질의 자체는 새것이 아니다 — `JdbcRouteMutations.findAssignedStop` 은
Phase 3-6 의 취소 경로가 이미 쓰고 있었다. **바뀐 것은 호출 빈도**다.

| | 부르는 곳 | 빈도 |
|---|---|---|
| Phase 3-6 | `order.cancelled` | 취소된 주문마다 — 드물다 |
| **Phase 5-5** | `delivery.status` | **stop 방문마다** — 피크 673 건/초([측정](phase5-delivery-status-throughput.md)) |

`route_stop_orders` 의 PK 는 `(stop_id, order_id)` 다. `order_id` 는 **선두 컬럼이 아니라서**
단독 조회에 그 인덱스를 쓸 수 없다.

## 측정 환경

| 항목 | 값 |
|---|---|
| PostgreSQL | 18.2 (공식 이미지, 기본 설정) · Testcontainers |
| 호스트 | macOS 27.0 · aarch64 · 14코어 · Docker Desktop 29.1.3 |
| 스키마 | `V1`–`V8` 적용 |
| 질의 | `JdbcRouteMutations.findAssignedStop` 그대로 (`JOIN route_stops` · `ORDER BY s.id DESC LIMIT 1`) |
| 준비 | 적재 후 `ANALYZE routes, route_stops, route_stop_orders` |
| 계측 | `RouteStopOrdersLookupProbeIT` (측정 뒤 삭제 — ADR-029 전례) |

**행 수는 운영 규모에서 잡았다.** 피크일은 stop 8,411 개 · `route_stop_orders` 약 15,000 행이고
(같은 측정의 §2), **dispatch 에는 라우트 보존 정책이 없다**(§7.1 의 보존 목록에 `routes`·
`route_stops`·`route_stop_orders` 가 없다). 그러므로 이 테이블은 **날마다 쌓인다** — 그래서
1일·10일·30일 세 점에서 잰다.

> **2026-09-25 — 보존이 생겼다**([ADR-059](../adr/ADR-059-dispatch-retention-is-per-plan.md), 7-0c). 라우트 계열은
> 계획 단위로 90일에 지워진다. 이 측정의 30일 점은 그대로 유효하고, 상한은 91일치 약 1,365만 행이다
> ([측정](phase7-dispatch-retention.md) §1). 아래 「보존 정책이 없으므로 상한도 없다」는 측정 당시의 문장으로 남긴다.

`ANALYZE` 는 생략하지 않았다. 통계가 없으면 `reltuples = -1` 이고 플래너는 기본 추정치로
**짐작하며 인덱스를 고른다**(Phase 4 의 같은 자리, `phase4-dispatch-candidates-index.md`).

---

## 1. 결과

| 규모 | stop | 주문 행 | 인덱스 없음 | `ix_rso_order` | 배 |
|---|---:|---:|---|---|---:|
| 1일 | 8,520 | 14,910 | `Seq Scan` · **0.630 ms** | `Index Scan` · **0.250 ms** | 2.5 |
| 10일 | 85,200 | 149,100 | `Seq Scan` · **3.430 ms** | `Index Scan` · **0.258 ms** | 13.3 |
| 30일 | 255,600 | 447,300 | `Parallel Seq Scan` · **5.891 ms** | `Index Scan` · **0.257 ms** | 22.9 |

200회 평균(왕복 포함). 인덱스 쪽은 **규모와 무관하게 평평하다** — 그것이 이 표의 요점이다.

30일 규모의 계획 둘:

```
-- 인덱스 없음
Limit  (cost=7833.24..7833.24 rows=1) (actual time=4.830..5.628 rows=1.00 loops=1)
  ->  Sort  (Sort Key: s.id DESC)
        ->  Nested Loop
              ->  Gather  (Workers Planned: 2, Workers Launched: 2)
                    ->  Parallel Seq Scan on route_stop_orders o
                          Filter: (order_id = '…'::uuid)
                          Rows Removed by Filter: 149100
                          Buffers: shared hit=4479 read=16 written=16
              ->  Index Scan using route_stops_pkey on route_stops s
Execution Time: 5.636 ms

-- ix_rso_order (order_id)
Limit  (cost=16.89..16.89 rows=1) (actual time=0.015..0.015 rows=1.00 loops=1)
  ->  Sort  (Sort Key: s.id DESC)
        ->  Nested Loop
              ->  Index Scan using ix_rso_order on route_stop_orders o
                    Index Cond: (order_id = '…'::uuid)
                    Buffers: shared hit=1 read=3
              ->  Index Scan using route_stops_pkey on route_stops s
Execution Time: 0.023 ms
```

**`Rows Removed by Filter: 149100` 이 워커마다** 붙는다. 한 건을 찾으려고 44만 행을 읽고
세 코어를 쓴다 — 그리고 그것을 **stop 방문마다** 한다.

## 2. 그래서 넣는다

| | 인덱스 없음 | `ix_rso_order` |
|---|---|---|
| 30일 규모에서 673 건/초를 소비하면 | 673 × 5.891 ms = **3.96 초/초** | 673 × 0.257 ms = **0.17 초/초** |
| 필요한 동시 DB 세션 (이 조회만으로) | **4 개 이상** | 0.2 개 |
| 인덱스 크기 | — | 13 MB (테이블 35 MB) |

왼쪽 칸은 「느리다」가 아니라 **「한 스레드로는 못 따라간다」**다. 그리고 그 값은 테이블이
자라면서 **계속 나빠진다** — 보존 정책이 없으므로 상한도 없다.

쓰기 비용은 계획 영속화(`route_stop_orders` 벌크 INSERT)에 붙는다. 5,000건 계획의 영속화 예산은
3초다(§6.7, [ADR-029](../adr/ADR-029-optimizer-io-is-bulk-not-orm.md)). **그 몫은 따로 재지
않았다** — 아래 §4 에 적는다.

## 3. 넣지 **않기로** 한 것도 적는다

- **`(order_id, stop_id)` 복합 인덱스로 커버링을 노리지 않는다.** 질의는 `stop_id` 를 받아
  곧바로 `route_stops` 를 PK 로 찾으므로 힙 방문이 어차피 한 번 있고, 30일 규모에서 이미
  0.257 ms 다. 크기만 늘린다.
- **`route_stops (route_id)` 는 넣지 않는다.** `progressOf` 가 그 컬럼으로 집계하지만
  ([§7.2 폴백](../DESIGN.md)), 부르는 빈도가 stop 방문마다이면서도 한 라우트는 120행이고
  **`routes` 의 FK 인덱스가 없는 것과는 다른 문제**다. 이 판단은 **재지 않았다** — 5-3 의 부분
  재계획이 이 경로의 첫 실사용자이고, 그때 같은 방식으로 측정한다. 지금 넣으면 근거 없이
  넣는 것이다.
  **2026-09-23 후기 — 부르는 쪽이 없어졌다.** `progressOf` 는 `route:{id}:progress` 를 채우려고만
  있었고, Phase 6-0c 에서 그 키와 함께 지웠다. 결론(넣지 않는다)은 그대로지만 근거가 「빈도 대비
  행 수」에서 **「그 컬럼으로 집계하는 질의가 없다」**로 바뀌었고, 그래서 예고한 5-3 측정도 하지
  않았다 — 잴 질의가 없다. 다시 필요해지면 그때가 재는 시점이다.
- **부분 인덱스(`WHERE status = 'PLANNED'`)를 쓰지 않는다.** `order_id` 는 상태와 무관하게
  찾아야 하고(취소된 stop 을 찾아내는 것이 ADR-047 결정 4 의 조건이다), 술어 컬럼을 리터럴로
  적어야 한다는 제약도 여기서는 값을 못 한다.

## 4. 이 측정이 답하지 않는 것

- **인덱스 하나가 계획 영속화에 더하는 쓰기 비용.** 재지 않았다. `dawnline_plan_persist_seconds`
  가 이미 그 값을 내고 있으므로([ADR-029](../adr/ADR-029-optimizer-io-is-bulk-not-orm.md)),
  5-3 의 재계획 측정에서 같은 지표로 확인한다. **근거: 추정** — B-tree 하나가 3초 예산 안에서
  문제가 되지 않으리라는 것은 연역이지 측정이 아니다.
- **동시성.** 단일 세션 200회 반복이다. 실제 소비는 리스너 4개가 동시에 돈다 — 그 수치는
  [처리량 측정](phase5-delivery-status-throughput.md)이 따로 냈다.
- **캐시가 식은 상태.** 세 규모 모두 `shared hit` 이 대부분이다. 차가운 버퍼에서는 순차 스캔
  쪽이 더 나빠진다(읽을 페이지가 많다) — 즉 위 표는 **인덱스에 불리한 쪽으로** 치우쳐 있다.
