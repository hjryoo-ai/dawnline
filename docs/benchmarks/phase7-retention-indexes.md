# 보존 정리 질의의 인덱스 — `shipments` · `rm_orders` (EXPLAIN, 7-0b)

CLAUDE.md 불변규칙 11에 따른 근거 자료입니다.
[ADR-058](../adr/ADR-058-shipment-and-read-model-retention.md)이 정리 배치 셋(`shipments`, `route_revisions`, `rm_*`)을
만들면서 인덱스 판단을 이 측정으로 미뤄 두었습니다. 결론은 **인덱스 둘을 넣고 셋은 넣지 않는다**입니다.
넣지 않은 판단도 행 수와 함께 적습니다.

| 표 | 운영 크기 | 판단 |
|---|---:|---|
| `shipments` | 30일치 465만 | **`ix_ship_updated (updated_at)`** — tracking `V4` |
| `rm_orders` | 90일치 1,365만 | **`ix_rmo_updated (updated_at)`** — ops-api `V7` |
| `route_revisions` | 90일치 11만 | 넣지 않는다 |
| `rm_routes` | 90일치 11만 | 넣지 않는다 |
| `rm_waves` | 90일치 3,640 | 넣지 않는다 |

## 측정 환경

| 항목 | 값 |
|---|---|
| PostgreSQL | 18.2 (공식 이미지, 기본 설정), Testcontainers |
| 호스트 | macOS 27.0, arm64, 14코어. Docker Desktop 29.1.3에 4 vCPU와 6.8 GB 할당 |
| 스키마 | tracking `V1`–`V3`, ops-api `V1`–`V6`. 측정 대상 인덱스가 없는 상태에서 시작 |
| 질의 | `JdbcTrackingRetention`, `JdbcReadModelRetention`의 상수 그대로. 상태 값은 리터럴이고 임계와 `LIMIT`은 바인드 |
| 준비 | 채운 뒤 `VACUUM ANALYZE`. 인덱스를 만든 뒤 다시 `ANALYZE`. 두 경우 모두 `reltuples`가 행 수와 같음을 확인 |
| 계측 | 측정용 IT는 측정 뒤 삭제했습니다(ADR-029 전례). `EXPLAIN (ANALYZE, BUFFERS)`는 롤백하는 트랜잭션 안에서 실행 |

**행 수는 운영 규모에서 잡았습니다.**

- 피크일은 15만 주문입니다(§8.2).
- 만료 행이 **정확히 하루치**가 되도록 보존 기간보다 하루 더 채웠습니다. `shipments`는 31일, `rm_orders`는 91일입니다.
  정리기는 하루에 한 번 돌므로, 평상시 만료 행은 그만큼만 쌓여 있습니다.
- 오래된 행을 먼저 넣었습니다. 실제 표의 물리적 순서와 같습니다.

**분포**

| 표 | 분포 |
|---|---|
| `shipments` | 완료 94%, 실패 5%, 취소 1%. 오늘치는 비종결 2/3. 라우트당 120 |
| `rm_orders` | 배차 불가 3%, 취소 0.1%(그중 1%는 배송됨). 배송 결과는 실패 5%. 걸린 행(결과 없음) 0.01%. 라우트당 120, 웨이브당 3,750 |
| `rm_routes` | 하루 1,250 |
| `rm_waves` | 하루 40 |

**계획은 둘 다 쟀습니다: custom과 generic.**

- 정리기는 같은 문장을 배치마다(하루 약 150번) 다시 부릅니다. 드라이버의 준비된 문장은 다섯 번째 실행부터 generic
  계획으로 갈 수 있습니다.
- generic 계획은 임계값을 모르므로 추정이 다릅니다. 인덱스가 없을 때 custom은 14만 행, generic은 151만 행으로
  추정했습니다(실제 15만).
- 인덱스는 **두 계획 모두에서** 쓰여야 값을 합니다. `force_custom_plan`과 `force_generic_plan`으로 따로 쟀습니다.

---

## 1. `shipments` — 30일치 4,649,975행, 916 MB

### 1.1 종결 30일 삭제 (배치 1,000)

```sql
DELETE FROM shipments WHERE ctid IN (SELECT s.ctid FROM shipments s
 WHERE s.status IN ('COMPLETED', 'FAILED', 'CANCELLED') AND s.updated_at < ?
 ORDER BY s.updated_at LIMIT ?)
```

| | 계획 | 버퍼 | 배치 하나 | 하루치 전체(150배치, 실측) |
|---|---|---|---|---|
| 인덱스 없음, custom | Seq Scan 465만 + top-N heapsort | hit 3,515 · read 70,076 | **510.3 ms** | — |
| 인덱스 없음, generic | 같음 | hit 2,499 · read 70,076 | **198.7 ms** | **26.4 초** (배치 170–185 ms) |
| `(updated_at)`, custom | Index Scan, 정렬 없음 | hit 2,097 | **0.90 ms** | — |
| `(updated_at)`, generic | 같음 | — | **0.84 ms** | **0.18 초** (배치 1–2 ms) |

```
-- 인덱스 없음 (custom)
->  Sort (actual time=505.185..505.207 rows=1000.00 loops=1)
      Sort Key: s.updated_at
      Sort Method: top-N heapsort  Memory: 88kB
      ->  Seq Scan on shipments s (actual time=0.089..497.369 rows=150000.00 loops=1)
            Filter: (((status)::text = ANY ('{COMPLETED,FAILED,CANCELLED}'::text[])) AND (updated_at < (now() - '30 days'::interval)))
            Rows Removed by Filter: 4500000
Execution Time: 510.275 ms

-- ix_ship_updated (custom)
->  Limit (actual time=0.010..0.149 rows=1000.00 loops=1)
      ->  Index Scan using ix_probe_ship_updated on shipments s (actual time=0.010..0.116 rows=1000.00 loops=1)
            Index Cond: (updated_at < (now() - '30 days'::interval))
            Filter: ((status)::text = ANY ('{COMPLETED,FAILED,CANCELLED}'::text[]))
            Buffers: shared hit=51
Execution Time: 0.897 ms
```

(측정 때의 이름은 `ix_probe_*`였습니다. 정의는 같습니다.)

### 1.2 상한 365일 삭제 — 지울 것이 없어도 날마다 돈다

| | 계획 | 배치 하나 |
|---|---|---|
| 인덱스 없음 | Seq Scan 465만, 결과 0행 | **404.8 ms** (custom) · **193.2 ms** (generic) |
| `(updated_at)` | Index Scan, 결과 0행 | **0.011 ms** · **0.012 ms** |

상한은 평상시에 지울 행이 없습니다. 그래도 인덱스가 없으면 **표 전체를 읽고서야 없다는 것을 압니다.**

### 1.3 `route_revisions` 90일 삭제 — 113,750행, 13 MB

```sql
DELETE FROM route_revisions WHERE ctid IN (SELECT r.ctid FROM route_revisions r
 WHERE r.applied_at < ? AND NOT EXISTS (SELECT 1 FROM shipments s WHERE s.route_id = r.route_id)
 ORDER BY r.applied_at LIMIT ?)
```

| | 계획 | 배치 하나 |
|---|---|---|
| 인덱스 없음 | Seq Scan 11만 → Nested Loop Anti Join(`ix_ship_route` Index Only Scan) | **8.8 ms** (custom) · **3.6 ms** (generic) |
| `(applied_at)` 시험 | Index Scan → 같은 반조인 | **1.3 ms** · **1.3 ms** |

가드의 반대쪽은 이미 `ix_ship_route (route_id, stop_seq)`의 선두 칸이 받습니다. Index Only Scan, Heap Fetches 0입니다.
만료는 하루 1,250행이라 **하루 두 배치, 20 ms 이하**입니다. 인덱스가 아끼는 것은 하루 15 ms이고, 그 대가로 개정마다
인덱스를 하나 더 옮깁니다. **넣지 않습니다.** 재검토 지점은 이 표가 100만 행을 넘을 때입니다(하루 라우트 수 × 90).

### 1.4 판단 — `ix_ship_updated (updated_at)`, 99 MB

- **넣습니다.**
  - 인덱스가 없으면 하루치 정리가 기사 스캔이 쓰는 표에서 916 MB를 150번 읽습니다(26.4초).
  - `fulfillment_orders (updated_at)`와 같은 판단입니다([ADR-023](../adr/ADR-023-fulfillment-retention.md),
    [phase2 측정](phase2-fulfillment-orders-indexes.md) §1). 그쪽은 68초 → 0.24초였습니다.
- **부분 인덱스가 아닙니다.**
  - 상한 질의는 상태를 보지 않습니다.
  - 종결 조건이 거르는 행은 오늘의 비종결, 약 3%뿐입니다(§7.1 「부분 인덱스는 걸러내는 비율이 클 때만」).
- **쓰기의 대가 — HOT.**
  - `updated_at`은 값이 바뀐 모든 쓰기가 옮기는 칸입니다. 이 칸을 키로 가진 인덱스는 HOT 갱신을 막습니다.
  - 이 측정에서는 인덱스가 **없어도** 스캔 모양의 갱신(상태, ETA, 나이) 50,000건이 **HOT 0건**이었습니다. 채운 직후이고
    fillfactor가 100이라 페이지에 자리가 없었기 때문입니다.
  - 자리가 생기는 운영의 모양에서 잃는 몫은 재지 않았습니다(**근거: 추정**). 7-4 peak-day가 쓰기 경로를 잴 때
    함께 보고, 7-0 대조표에 행으로 남깁니다.

## 2. `rm_orders` — 90일치 13,650,073행, 3,851 MB (힙 2,770 MB)

### 2.1 종결 90일 삭제 (배치 1,000)

```sql
DELETE FROM rm_orders WHERE ctid IN (SELECT o.ctid FROM rm_orders o
 WHERE o.updated_at < ?
   AND (o.order_status IN ('CANCELLED', 'UNSERVICEABLE') OR o.delivery_outcome IS NOT NULL)
 ORDER BY o.updated_at LIMIT ?)
```

| | 계획 | 배치 하나 | 하루치 전체 |
|---|---|---|---|
| 인덱스 없음, custom | Seq Scan 1,365만 + top-N heapsort | **1,307 ms** | — |
| 인덱스 없음, generic | 같음 | **555 ms** | 약 **76초**(연속 세 배치 실측 506 · 508 · 506 ms × 150) |
| `(updated_at)`, custom | Index Scan, 정렬 없음 | **0.92 ms** | — |
| `(updated_at)`, generic | 같음 | **0.85 ms** | **0.18초**(146,985행, 실측) |

### 2.2 상한 365일 삭제와 걸린 행의 셈

| | 인덱스 없음 | `(updated_at)` |
|---|---|---|
| 상한 삭제(결과 0행) | Seq Scan · **1,104 ms** (custom) · **528 ms** (generic) | Index Scan · **0.010 ms** · **0.012 ms** |
| 걸린 행 셈(15행) | Parallel Seq Scan(워커 2) · **420 ms** · **388 ms** | Index Scan · **10.0 ms** · **14.0 ms** |

### 2.3 `rm_routes` · `rm_waves` 90일 삭제

| 표 | 행 | 계획(인덱스 없음) | 배치 하나 |
|---|---:|---|---|
| `rm_routes` | 113,750 | Seq Scan → Nested Loop Anti Join(`ix_rmo_route` Index Only Scan) | **9.7 ms** · **3.8 ms** |
| `rm_waves` | 3,640 | Seq Scan → Nested Loop Anti Join(`ix_rmo_wave` Index Only Scan) | **0.39 ms** · **0.17 ms** |

가드의 반대쪽은 재집계가 이미 쓰는 두 인덱스가 받습니다
([phase6 측정](phase6-rm-orders-aggregate-index.md)). 두 표는 하루 1,250행과 40행이 만료됩니다.
**넣지 않습니다.** 재검토 지점은 `rm_routes`가 100만 행을 넘을 때입니다. §5.5 「나머지 셋」의 재검토 지점과 같은 수이고,
90일 보존으로는 닿지 않습니다.

### 2.4 판단 — `ix_rmo_updated (updated_at)`, 292 MB (표 전체의 7.6%, 만드는 데 1.6초)

- **넣습니다.** 인덱스가 없으면 하루치 정리가 약 76초이고, 일곱 토픽의 쓰기가 모이는 표에서 3.9 GB를 150번 읽습니다.
  KPI 뷰(§5.5)도 이 표 위에 있습니다.
- **정리 전체**(종결 146,985행, 상한 0, 라우트 1,235, 웨이브 25, 걸린 행 셈 15)가 **0.2초**입니다.
  라우트와 웨이브가 1,250과 40이 아닌 이유는 걸린 주문 15건의 라우트와 웨이브가 가드에 남았기 때문입니다.
  의도한 동작입니다.
- **쓰기의 대가.** 이 표에서는 이미 거의 모든 쓰기가 인덱스 칸을 건드립니다: `route_id`, 결과의 시각(표현식 인덱스),
  부분 인덱스의 술어 칸(`order_status`, `delivery_outcome`). 그래서 HOT으로 잃는 몫이 작다고 봅니다.
  **근거: 추정** — 쓰기 경로의 칸을 세어 판단했고, HOT 비율은 재지 않았습니다.

## 3. 지키는 것

- `TrackingRetentionIndexIT`와 `ReadModelRetentionIndexIT`
  - 하루치(15만 행)를 보존 기간에 걸쳐 채우고 `ANALYZE` 합니다.
  - 첫 어설션으로 **통계가 있다**를 말합니다.
  - 어댑터의 상수 그대로 문장을 준비하고, custom과 generic **두 계획 모두**가 인덱스를 타는지 봅니다.
- 음성 표본: 두 마이그레이션에서 인덱스를 빼면 두 IT가 빨갛습니다(custom 계획의 `Seq Scan`).
- 넣지 않은 셋은 이 문서와 두 마이그레이션의 머리말(`V4`, `V7`)에 행 수와 재검토 지점을 적었습니다.
