# KPI 뷰의 버킷 질의 — `ix_rmo_delivery_hour` · `ix_rmo_intake_hour` (EXPLAIN)

CLAUDE.md 불변규칙 11 에 따른 근거 자료다. 여기서 **인덱스를 둘 넣는다**
(`V2__ops_kpi.sql`, DESIGN.md §5.5 「KPI — 두 축, 뷰」 반영).

## 계기

KPI 시간 버킷이 증감 표(`rm_kpi_hourly`)에서 `rm_orders` 위의 뷰 둘로 바뀌었다(ADR-051 결정 4 의
가장 순수한 형태 — 쓰는 쪽이 없다). 대가는 **읽을 때마다 다시 세는 것**이고, 읽는 쪽은 둘이다.

| 질의 | 부르는 곳 | 빈도 |
|---|---|---|
| `SELECT * FROM kpi_delivery_hourly WHERE camp_id = ? AND bucket_hour >= ? AND bucket_hour < ?` (24 버킷) | 캠프 대시보드(묶음 C) | 화면 갱신마다 |
| `SELECT * FROM kpi_intake_hourly WHERE camp_id = ? AND bucket_hour >= ? AND bucket_hour < ?` (24 버킷) | 캠프 대시보드(묶음 C) | 화면 갱신마다 |
| `JdbcDeliveryKpis.SUM_BY_CAMP_SQL` — 전 캠프, 24 버킷 합 | `OnTimeRatioGauges` | 1분마다 |

## 측정 환경

| 항목 | 값 |
|---|---|
| PostgreSQL | 18.2 (공식 이미지, 기본 설정) · `docker run` 한 컨테이너 |
| 호스트 | macOS 27.0 · aarch64 · 14코어 · Docker Desktop 29.1.3 |
| 스키마 | `V1__ops` + `V2__ops_kpi` (1 절의 표는 최종 파일 그대로, 인덱스를 지운 상태와 비교) |
| 준비 | 규모마다 **새로 적재**(`TRUNCATE` 후 `INSERT`) 뒤 `ANALYZE` — `reltuples` 가 행 수와 같음을 확인 |
| 계측 | `PREPARE` 한 질의를 `EXPLAIN (ANALYZE)` 로 세 번, 앞의 둘은 예열이고 셋째를 적는다 |

**삭제로 규모를 줄이면 안 된다.** 처음에 30일치를 지워 10일·3일을 만들었더니 `VACUUM` 뒤에도 힙이
817 MB 그대로라 순차 스캔 시간이 규모를 따라 줄지 않았다(10일치 게이지 104 ms). 아래 표는 전부 새로
적재한 값이다.

**행 수는 운영 규모다.** 피크일 캠프 10 × 3,000 × 5 = **15만 주문**(§8.2)이고 `rm_orders` 에는 보존
정책이 없다(`phase6-rm-orders-aggregate-index.md` 와 같은 전제). 분포: 캠프 10 · 실패 5% · 배차 불가 3%
(캠프·결과 없음) · 개정 2% · 접수 뒤 5–7시간에 결과.

## 1. 결과 — peak 30일 (4,500,000 행)

| 질의 | 인덱스 없음 | 인덱스 | 계획 (인덱스) |
|---|---|---|---|
| 배송 축, 캠프 하나 24 버킷 | `Parallel Seq Scan` · **219.1 ms** | **6.18 ms** | `Bitmap Index Scan on ix_rmo_delivery_hour` · 14,545 행 |
| 접수 축, 캠프 하나 24 버킷 | `Parallel Seq Scan` · **188.3 ms** | **5.50 ms** | `Bitmap Index Scan on ix_rmo_intake_hour` · 14,545 행 |
| 게이지, 전 캠프 24 버킷 | `Parallel Seq Scan` · **490.7 ms** | **37.8 ms** | `Bitmap Index Scan on ix_rmo_delivery_hour` · 145,459 행 |

게이지의 37.8 ms 는 창 안의 행(전 캠프 하루치)을 **전부 읽는** 값이라 더 줄일 곳이 없다 — 1분에 한 번이다.
인덱스 크기는 각각 **32 MB**(30일).

```
-- ix_rmo_delivery_hour — 뷰의 bucket_hour 술어가 인덱스의 식 그대로 내려간다
Bitmap Index Scan on ix_rmo_delivery_hour (actual time=0.373..0.373 rows=14545.00 loops=1)
  Index Cond: ((camp_id IS NOT NULL) AND (camp_id = '…'::uuid)
    AND (date_trunc('hour'::text, COALESCE(delivered_at, failed_at), 'UTC'::text) >= '2026-09-23 00:00:00+00'::timestamp with time zone)
    AND (date_trunc('hour'::text, COALESCE(delivered_at, failed_at), 'UTC'::text) <  '2026-09-24 00:00:00+00'::timestamp with time zone))
```

## 2. 규모별 계획 선택

1–10일 줄은 최종 뷰의 한 단계 앞(배송 축 모집단에 `order_status <> 'CANCELLED'` 가 들기 전)으로 잰 값이다.
그 술어는 힙 행의 필터라 계획의 선택을 바꾸지 않는다 — 30일 줄은 최종 `V2__ops_kpi.sql` 로 다시 잰
값이고(위 표와 같다) 선택이 같다.

| 규모 | 배송 축(캠프) | 접수 축(캠프) | 게이지(전 캠프) |
|---|---|---|---|
| 1일 (150,000) | `ix_rmo_delivery_hour` · 4.4 ms | **`ix_rmo_delivery_hour` 의 캠프 접두** · 9.4 ms | `Seq Scan` · 30.1 ms |
| 3일 (450,000) | `ix_rmo_delivery_hour` · 6.7 ms | `ix_rmo_intake_hour` · 5.3 ms | `Seq Scan` · 55.6 ms |
| 10일 (1,500,000) | `ix_rmo_delivery_hour` · 5.9 ms | `ix_rmo_intake_hour` · 5.6 ms | `ix_rmo_delivery_hour` · 21.6 ms |
| 30일 (4,500,000) — 최종 V2 | `ix_rmo_delivery_hour` · 6.18 ms | `ix_rmo_intake_hour` · 5.50 ms | `ix_rmo_delivery_hour` · 37.8 ms |

- 게이지가 3일 이하에서 순차 스캔을 고르는 것은 **맞다** — 창이 표의 3분의 1 이상이다.
- 1일치에서 접수 축이 배송 축 인덱스의 캠프 접두를 빌리는 것은 표 전체가 하루라 버킷 범위가 걸러
  주는 것이 없기 때문이다. 그래서 **`KpiViewsIndexIT` 는 3일치로 잰다** — 각 질의가 자기 인덱스를 고르는
  가장 작은 규모다.

## 3. 음성 표본 — 뷰의 식과 인덱스의 식이 어긋나면

뷰를 `date_trunc('hour', COALESCE(failed_at, delivered_at), 'UTC')` 로 적고(인수 순서만 바꿨다) 같은
질의를 돌렸다(30일).

```
Parallel Bitmap Heap Scan on rm_orders (actual time=137.782..143.105 rows=4848.33 loops=3)
  ->  Bitmap Index Scan on ix_rmo_intake_hour (actual time=11.255..11.256 rows=436363.00 loops=1)
        Index Cond: (camp_id = '…'::uuid)
Execution Time: 158.301 ms
```

**순차 스캔이 아니다** — 다른 인덱스의 캠프 접두만 타고 캠프의 30일치 43만 행을 거른다. 「Seq Scan 이
없다」나 「인덱스를 탄다」로는 이것을 못 잡는다. 그래서 `KpiViewsIndexIT` 는 **`Index Cond` 에 버킷 식이
있는지**를 보고, 이 음성 표본을 V2 에 넣어 빨간 것을 확인했다.

## 4. 그래서 넣는다 — 그리고 넣지 않은 것

- `CREATE INDEX ix_rmo_delivery_hour ON rm_orders (camp_id, date_trunc('hour', COALESCE(delivered_at, failed_at), 'UTC'))`
- `CREATE INDEX ix_rmo_intake_hour ON rm_orders (camp_id, date_trunc('hour', placed_at, 'UTC'))`
- **`(camp_id, delivered_at)` 는 넣지 않았다**(처음 제안한 모양). 배송 축의 버킷이
  `COALESCE(delivered_at, failed_at)` 이라 실패 행을 못 잡고, 원시 시각 인덱스는 뷰가 내리는
  `date_trunc(…)` 술어와 만나지 않는다.
- **`delivered_at`·`failed_at` 둘로 나눠 `UNION` 하지 않았다.** 식 인덱스 하나로 뷰의 술어가 그대로
  내려가고(위 계획), 뷰가 두 갈래가 되면 두 식을 각각 인덱스와 맞춰야 한다 — 어긋날 자리가 둘이 된다.
- **부분 인덱스(`WHERE delivery_outcome IS NOT NULL`)로 하지 않았다.** 결과가 없는 행은 대부분 곧
  결과를 얻으므로 줄어드는 크기가 작고, 술어가 뷰의 `WHERE` 와 글자로 맞아야 하는 조건이 하나 더 는다.
- **쓰기 비용.** `delivery.status` 는 `delivered_at`·`failed_at` 을 쓰므로 그 갱신은 HOT 이 아니다(인덱스
  키가 바뀐다). 두 칸을 쓰지 않는 갱신(`eta_at` 등)은 HOT 자격이 그대로다. 쓰기 경로의 시간은 이
  측정에 없다 — 읽기 쪽 이득(수십 배)이 결정의 근거이고, 쓰기 경로가 문제되면 여기서 다시 잰다.

## 5. 지키는 것

`KpiViewsIndexIT` — 3일치(45만 행)를 채우고 `ANALYZE` 한 뒤 **통계가 있다**를 첫 어설션으로 말하고, 두
뷰의 캠프·24 버킷 질의가 각자의 인덱스를 타며 그 `Index Cond` 에 버킷 식이 글자 그대로 있는지 본다.
