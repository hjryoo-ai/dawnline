# `rm_orders` 의 재집계 질의 — `ix_rmo_route` · `ix_rmo_wave` (EXPLAIN)

CLAUDE.md 불변규칙 11 에 따른 근거 자료다. 여기서 **인덱스를 둘 넣는다**
(`V1__ops.sql`, DESIGN.md §5.5 반영).

## 계기

[ADR-051](../adr/ADR-051-first-fact-creates-the-row-absence-is-not-a-value.md) 결정 4 가 개수 칸을
증감이 아니라 **재집계**로 두었다 — 순서가 뒤집히면 증감은 사실을 잃는다(그 ADR 의 변형 C 가
관측했다). 대가는 **이벤트마다 집계 질의 하나**이고, 그 질의가 원하는 인덱스를 결정 4 는 이
커밋으로 미뤄 두었다.

| 질의 | 부르는 곳 | 빈도 |
|---|---|---|
| `SELECT count(*) FROM rm_orders WHERE route_id = ? AND delivery_outcome = '…'` ×2 | `delivery.status`·`route.assigned` | stop 방문마다 — 피크 673 건/초([측정](phase5-delivery-status-throughput.md)) |
| `SELECT count(*) FROM rm_orders WHERE wave_id = ?` | `fulfillment.planned` | 주문마다 — 피크 ~600 rps 버스트(§8.2) |

## 측정 환경

| 항목 | 값 |
|---|---|
| PostgreSQL | 18.2 (공식 이미지, 기본 설정) · Testcontainers |
| 호스트 | macOS 27.0 · aarch64 · 14코어 · Docker Desktop 29.1.3 |
| 스키마 | `V1__ops` (인덱스 없는 상태에서 시작) |
| 질의 | `JdbcRouteRows.RECOUNT_SQL`·`JdbcWaveRows.RECOUNT_SQL` 의 부질의 그대로 (상태 값은 리터럴) |
| 준비 | 적재 후 `ANALYZE rm_orders` — 세 규모 모두 `reltuples` 가 행 수와 같음을 확인 |
| 계측 | 측정용 IT(측정 뒤 삭제 — ADR-029 전례). 예열 20회 뒤 200회 평균, 왕복 포함 |

**행 수는 운영 규모에서 잡았다.** 피크일은 캠프 10 × 3,000 × 5 = **15만 주문**이고(§8.2),
ops 의 `rm_*` 에는 **보존 정책이 없다**(§7.1 의 보존 목록에 없다). 그래서 이 표는 날마다 쌓인다 —
1일·10일·30일 세 점에서 쟀다. 분포는 라우트당 주문 210(stop 120 × 1.78, phase5 측정) ·
웨이브당 1,500 · 실패 5%.

## 1. 결과

| 규모 | 행 | 웨이브, 인덱스 없음 | 라우트, 인덱스 없음 | 웨이브, `ix_rmo_wave` | 라우트, `ix_rmo_route` |
|---|---:|---|---|---|---|
| 1일 | 150,000 | `Seq Scan` · **5.06 ms** | `Seq Scan` ×2 · **9.20 ms** | `Index Only Scan` · **0.25 ms** | `Index Scan` · **0.19 ms** |
| 10일 | 1,500,000 | `Parallel Seq Scan` · **31.1 ms** | **64.5 ms** | **0.31 ms** | **0.21 ms** |
| 30일 | 4,500,000 | `Parallel Seq Scan` · **86.5 ms** | **172.4 ms** | **0.21 ms** | **0.19 ms** |

인덱스 쪽은 **규모와 무관하게 평평하다.** 1일치에서 이미 라우트 재집계 9.2 ms 에 673 건/초를
곱하면 **초당 6.2 초의 DB 시간**이다 — 첫날부터 따라갈 수 없다. 30일치 계획:

```
-- 인덱스 없음 (라우트, 부질의 하나)
Finalize Aggregate (actual time=80.341..80.375 rows=1.00 loops=1)
  ->  Gather
        ->  Partial Aggregate
              ->  Parallel Seq Scan on rm_orders o (actual time=69.768..74.705 rows=66.67 loops=3)
                    Rows Removed by Filter: 1499933
                    Buffers: shared hit=16010 read=75827

-- ix_rmo_route
Aggregate (actual time=0.020..0.020 rows=1.00 loops=1)
  ->  Index Scan using ix_probe_route on rm_orders o (actual time=0.002..0.015 rows=199.00 loops=1)
        Index Cond: (route_id = '…'::uuid)
        Rows Removed by Filter: 11
        Buffers: shared hit=8
```

(측정 때의 이름은 `ix_probe_*` 였다 — 같은 정의다.)

## 2. 그래서 넣는다 — 그리고 넣지 않은 것

- `CREATE INDEX ix_rmo_route ON rm_orders (route_id)` · `CREATE INDEX ix_rmo_wave ON rm_orders (wave_id)`.
- **`(route_id, delivery_outcome)` 복합은 넣지 않았다.** 라우트 하나가 210 행이라 결과 칸 필터는
  힙 8 페이지 안에서 끝난다(0.02 ms). 복합으로 index-only 를 노리면 배송마다 바뀌는 칸이 인덱스
  키가 되어 **쓰기마다** 인덱스를 옮긴다 — 쓰기가 읽기만큼 잦은 표다.
- **잠금 질의(`lock`)에는 새 인덱스가 필요 없다** — `order_id = ANY(?)`·`route_id = ANY(?)`·
  `wave_id = ?` 는 전부 PK 다.
- **다른 `rm_*` 표에는 넣지 않았다.** `rm_routes`·`rm_waves` 는 키로만 찾는다. 화면의 조회 질의
  (캠프별·상태별)는 묶음 C 에서 생기고, 그때 이 문서와 같은 방식으로 잰다 — 지금은 그 질의가 없다.

## 3. 지키는 것

`RmOrdersIndexIT` — 1일치(15만 행)를 채우고 `ANALYZE` 한 뒤, **통계가 있다**를 첫 어설션으로
말하고 두 재집계 계획이 인덱스를 타는지 본다. 인덱스가 사라지거나 질의가 그것을 못 쓰게 바뀌는
것이 거기서 잡혀야 하는 사건이다.
