# ops-api 조회 표면 — 셋은 인덱스를 더하지 않고, 예외 목록은 부분 인덱스 하나 (EXPLAIN)

CLAUDE.md 불변규칙 11 에 따른 근거 자료다. 조회 넷 중 셋은 **인덱스를 넣지 않고**, 넣지 않은 판단을 행 수와 함께
적는다 — 「검토했는데 안 넣은 것」과 「생각하지 못한 것」을 구별할 수 있게(DESIGN.md §5.5 「조회」). 넷째인 예외
목록은 **창을 없애면서**(2026-09-24 정정) 부분 인덱스 `ix_rmo_cancelled_delivered`(V4)를 더한다 — 아래 「예외 목록 —
창 없이」.

## 계기

묶음 C 가 캠프 대시보드와 라우트 지도를 위해 ops-api 에 조회 여섯을 붙였다. 그중 읽기 모델을 읽는 넷의 질의다.

| 질의 (`JdbcReadModelViews`) | 부르는 곳 | 기존 인덱스 |
|---|---|---|
| `CAMPS_SQL` — `rm_waves` 를 캠프로 묶는다 | 대시보드의 캠프 고르기 | 없음 |
| `WAVES_SQL` — `camp_id = ? AND cutoff_at` 창 | 대시보드 | 없음 |
| `ROUTES_SQL` — `rm_routes WHERE plan_id = ?` | 지도의 라우트 목록 | 없음 |
| `CANCELLED_BUT_DELIVERED_SQL` — 캠프 + 상태 리터럴, **창 없음**, 전체 수는 창 함수 | 대시보드의 예외 목록 | `ix_rmo_cancelled_delivered` (**V4**, 이 측정으로 더했다) |

## 측정 환경

| 항목 | 값 |
|---|---|
| PostgreSQL | 18.2 (공식 이미지, 기본 설정) · `docker run` 한 컨테이너 |
| 호스트 | macOS 27.0 · aarch64 · 14코어 · Docker Desktop |
| 스키마 | `V1__ops` + `V2__ops_kpi` + `V3__ops_wave_depot` 그대로 (예외 목록은 `V4__ops_cancelled_delivered` 전후) |
| 준비 | 규모마다 `TRUNCATE` 후 새로 적재, `ANALYZE` 뒤 `reltuples` 가 행 수와 같음을 확인 |
| 계측 | `EXPLAIN (ANALYZE)` 세 번, 앞의 둘은 예열이고 셋째를 적는다 |

**행 수는 운영 규모다.** 피크일은 캠프 10 × 3,000 × 5 = 15만 주문이고(§8.2), 웨이브는 캠프 10 × 5 = **하루 50**,
라우트는 웨이브당 25(부록 A 의 캠프 20대 + 야간조) = **하루 1,250** 이다. `rm_*` 에는 보존 정책이 없으므로
30일과 **1년** 둘을 잰다.

## 결과

| 질의 | 30일 | 1년 | 계획 |
|---|---|---|---|
| 캠프 목록 | `rm_waves` 1,500 행 · **0.32 ms** | 18,250 행 · **3.04 ms** | `Seq Scan` + `HashAggregate` |
| 웨이브 창 (48시간, 캠프 하나 → 10 행) | **0.09 ms** | **0.74 ms** | `Seq Scan on rm_waves` |
| 계획의 라우트 (25 행) | `rm_routes` 37,500 행 · **1.22 ms** | 456,250 행 · **6.82 ms** | `Seq Scan` / `Parallel Seq Scan on rm_routes` |

## 예외 목록 — 창 없이 (2026-09-24 정정)

처음 판은 예외 목록의 창을 KPI 와 같은 24 버킷으로 두어 `ix_rmo_delivery_hour` 를 탔다(캠프 하나 3.58 ms). **창을
없앤 이유는 해소 여부를 이 시스템이 모르기 때문이다** — 환불·회수를 기록하는 칸도 사건도 없어서, 창으로 자르면
처리되지 않은 건이 시간이 지났다는 이유만으로 목록에서 사라진다(DESIGN.md §5.5).

분포는 `KpiViewsIndexIT` 와 같고(캠프 10, 실패 5%, 배차 불가 3%, 개정 2%) 취소를 `g % 997 = 5` 로 더했다 — 997 은
10 과 서로소라 캠프마다 고르다. 그중 배송된 것 **4,158 행(0.09%)**, 캠프당 약 415 행이 예외 목록이다. 처음 적재는
`g % 1000 = 1` 이었고 그것은 `g % 10 = 1` 을 함의해 **한 캠프에 몰렸다** — 측정 전에 분포를 세어 보고 고쳤다.

| 계획 | 30일 (`rm_orders` 4,500,045 행, 캠프 439 행) | 1년치 캠프 몫 (5,339 행) |
|---|---|---|
| V4 없음 — `ix_rmo_delivery_hour` 의 **캠프 접두** | **98.3 ms** · 43만 6천 행을 읽고 필터로 146×3 만 남긴다 | 재지 않았다 — 캠프 접두가 캠프의 전 기간이므로 행 수에 비례한다(근거: 추정, 30일의 12배 ≈ 1.2 s) |
| **V4 부분 인덱스** `(camp_id) WHERE order_status = 'CANCELLED' AND delivery_outcome = 'COMPLETED'` | **0.31 ms** · 인덱스 **48 kB** | **5.3 ms** |

1년치 열은 표 전체를 1년으로 채우지 않고 **그 캠프의 예외 행만** 1년치(`439 × 365 / 30`)로 늘려 쟀다. 부분 인덱스는
술어를 만족하는 행만 담고 비트맵 힙 스캔은 그 행의 블록만 읽으므로, 다른 행의 수는 이 계획의 비용에 들어가지 않는다.

```
-- V4 없음 (30일) — 캠프 접두만 인덱스 조건이다
Parallel Bitmap Heap Scan on rm_orders (actual time=22.398..81.636 rows=146.33 loops=3)
  Recheck Cond: (camp_id = '…04'::uuid)
  Rows Removed by Index Recheck: 545699
  Filter: (((order_status)::text = 'CANCELLED'::text) AND ((delivery_outcome)::text = 'COMPLETED'::text))
  Rows Removed by Filter: 145308
  Buffers: shared hit=40 read=82617
  ->  Bitmap Index Scan on ix_rmo_delivery_hour (actual time=12.657..12.657 rows=436364.00 loops=1)
        Index Cond: (camp_id = '…04'::uuid)
Execution Time: 98.275 ms

-- V4 부분 인덱스 (30일)
Limit (actual time=0.274..0.286 rows=201.00 loops=1)
  ->  Sort (actual time=0.273..0.278 rows=201.00 loops=1)
        Sort Key: delivered_at DESC, order_id
        Sort Method: top-N heapsort  Memory: 53kB
        ->  WindowAgg (actual time=0.181..0.207 rows=439.00 loops=1)
              ->  Bitmap Heap Scan on rm_orders (actual time=0.020..0.137 rows=439.00 loops=1)
                    Recheck Cond: ((camp_id = '…04'::uuid) AND ((order_status)::text = 'CANCELLED'::text) AND ((delivery_outcome)::text = 'COMPLETED'::text))
                    Heap Blocks: exact=100
                    ->  Bitmap Index Scan on ix_rmo_cancelled_delivered (actual time=0.010..0.010 rows=439.00 loops=1)
                          Index Cond: (camp_id = '…04'::uuid)
Execution Time: 0.310 ms
```

**키는 캠프 하나다.** 처음에는 `(camp_id, delivered_at DESC, order_id)` — 질의의 `ORDER BY` 그대로 — 로 만들었고
같은 0.31 ms 였다. 전체 수(`count(*) OVER ()`)를 같은 질의에서 읽으므로 캠프의 행을 어차피 전부 읽고, 계획에는
정렬(top-N)이 그대로 남았다 — 정렬 칸은 값을 하지 않았다. 전체 수를 따로 세는 두 번째 질의로 가르면 정렬 칸이
`LIMIT` 에서 멈추게 하겠지만, 두 질의는 서로 다른 순간을 말할 수 있다(목록 201 행 · 전체 200).

## 판단

- **셋은 인덱스를 넣지 않는다.** 1년치에서도 가장 느린 것이 6.8 ms(계획의 라우트)이고, 부르는 곳은 사람이 화면을
  여는 빈도다. 인덱스는 투영의 쓰기마다 값을 치른다 — `rm_routes` 는 `delivery.status` 마다 갱신된다.
- **예외 목록은 부분 인덱스 하나를 더한다**(V4, DESIGN.md §5.5 의 DDL). 창이 없으면 기존 인덱스는 캠프 접두만
  쓰고 캠프의 전 기간을 거른다(30일 98 ms, 1년이면 그 배수). 부분 인덱스는 술어를 만족하는 행만 담아 30일 48 kB 이고,
  쓰기 비용은 **그 술어를 만족하게 되는 갱신에만** 든다 — 드문 조합이다. 처음 판(창 = KPI 24 버킷, 인덱스 없음)의
  판단은 「창 밖의 건은 알림이 맡는다」였는데, 알림은 **건수**를 말하고 **어느 주문인지**는 말하지 않는다 — 운영자는
  개별 답이 필요하다(§6.3).
- **강제 수단**: `KpiViewsIndexIT.예외_목록이_희소_행의_부분_인덱스를_탄다` 가 **운영 코드의 문장 그대로**
  (`JdbcReadModelViews.CANCELLED_BUT_DELIVERED_SQL`)를 `PREPARE` 하고 `plan_cache_mode = force_generic_plan` 으로
  **일반 계획**을 본다 — 운영은 캠프를 바인드로 넘기므로 리터럴을 끼운 `EXPLAIN` 은 운영이 쓰지 않는 계획이다.
  음성 표본 둘: 인덱스의 술어를 `delivery_outcome = 'FAILED'` 로 바꾸면, 질의의 술어를 `order_status || '' = …` 로
  (리터럴 비교가 아니게) 바꾸면 — 둘 다 빨갛다.

## 재검토 지점

- `rm_routes` 가 **100만 행**을 넘을 때(피크일 규모로 약 2년 — 보존 정책이 없다).
- 지도가 **주기 폴링**을 시작할 때 — 지금은 화면을 열 때와 커맨드 뒤에만 읽는다.
- 예외 목록에 **해소**가 기록되기 시작할 때 — 그때 목록은 「해소되지 않은 것」이 되고 술어가 바뀐다(부분 인덱스도).
- 캠프 하나의 예외 행이 **수만**을 넘을 때 — 전체 수를 함께 읽는 비용이 목록보다 커진다. 그때 수를 따로 센다.
