# `fulfillment_orders` 인덱스 EXPLAIN (V2)

CLAUDE.md 불변규칙 11 — "인덱스 추가 금지(설계서 명시분 외). 필요하면 EXPLAIN 결과를 PR 설명에
첨부하고 설계서에 반영한다. **넣지 않기로 한 판단도 행 수와 함께 기록한다**" — 에 따른 근거 자료다.
[ADR-022](../adr/ADR-022-fulfillment-order-aggregate.md) 결정 5 와
[ADR-023](../adr/ADR-023-fulfillment-retention.md) 결정 1·3 이 "EXPLAIN 은 Phase 2-4 에" 라고
미뤄 둔 측정이며, **그 측정이 ADR-022 의 부분 인덱스 결정을 뒤집었다**(아래 3절).

## 측정 환경

| 항목 | 값 |
|---|---|
| PostgreSQL | 18.2 (공식 이미지, 기본 설정) |
| 호스트 | Docker Desktop 29.1.3 / 4 vCPU · 6.8 GB (macOS arm64, 14코어·64 GB 중 할당분) |
| `fulfillment_orders` | 4,650,000 행 / 886 MB |
| `waves` | 3,600 행 / 432 kB |
| 상태 분포 | `PLANNED` 4,557,000 (98%) · `CANCELLED` 69,750 · `UNSERVICEABLE` 23,250 |
| 만료 행 | 150,057 (`updated_at < now() − 30일` — 하루치) |
| 준비 | 적재 후 `VACUUM ANALYZE`, 인덱스 변경마다 `ANALYZE` |

**행 수의 근거**: §8.1 피크는 150,000 주문/일이고 보존은 30일이므로 정상 상태는 4.5M 행이다
(ADR-023). 31일치를 채워 만료 행이 정확히 하루치가 되게 했다 — 정리기는 하루 한 번 도니까
평상시 만료 행은 그만큼만 쌓여 있고, 그 상태에서 재는 것이 의미 있는 측정이다.
웨이브는 하루 40개(캠프 10 × 티어 3 + 컷오프 2회), 웨이브당 주문 3,750건이다.

---

## 1. `ix_fulfillment_orders_cleanup (updated_at)` — 보존 30일 정리 (ADR-023 결정 1)

정리 쿼리. 나이만이 아니라 **종결 상태인지**도 함께 본다 — 진행 중 주문을 지우지 않기 위해서다.

```sql
DELETE FROM fulfillment_orders
 WHERE ctid IN (SELECT fo.ctid FROM fulfillment_orders fo
                 WHERE fo.updated_at < now() - interval '30 days'
                   AND (fo.status IN ('CANCELLED','UNSERVICEABLE')
                        OR EXISTS (SELECT 1 FROM waves w
                                    WHERE w.id = fo.wave_id
                                      AND w.status IN ('PLANNED','PLAN_FAILED')))
                 ORDER BY fo.updated_at
                 LIMIT 1000)
```

| | 계획 | shared buffers | Execution Time |
|---|---|---|---|
| 인덱스 없음 | Seq Scan 4,650,000행 + top-N heapsort | 113,433 | **451.876 ms** |
| 인덱스 있음 | Index Scan, 정렬 없음 | 2,048 | **1.608 ms** |

**하루치 정리 전체로 환산하면** 150,057행 ÷ 배치 1,000 = 약 150배치다.
인덱스 없이는 매 배치가 테이블 전체를 다시 훑으므로 150 × 452 ms ≈ **68초**,
인덱스가 있으면 150 × 1.6 ms ≈ **0.24초**다.

인덱스 크기는 **100 MB** — 886 MB 테이블의 11.3% 로 작지 않다. `updated_at` 은 행마다 값이 달라
btree 중복 제거가 전혀 듣지 않기 때문이다(2절의 `wave_id` 와 대조된다). 그럼에도 넣는 이유는
위의 68초가 **주문 접수 경로와 같은 테이블에서** 일어나는 스캔이기 때문이다.

### 인덱스 없음
```
 Delete on fulfillment_orders (actual time=443.869..443.870 rows=0.00 loops=1)
   Buffers: shared hit=17678 read=97783
   ->  Nested Loop (actual rows=1000.00 loops=1)
         ->  HashAggregate (actual rows=1000.00 loops=1)
               ->  Limit (actual rows=1000.00 loops=1)
                     ->  Sort (actual time=386.899..386.920 rows=1000.00 loops=1)
                           Sort Key: fo.updated_at
                           Sort Method: top-N heapsort  Memory: 88kB
                           ->  Seq Scan on fulfillment_orders fo (actual rows=150108.00 loops=1)
                                 Filter: ((updated_at < (now() - '30 days'::interval)) AND (...))
                                 Rows Removed by Filter: 4499892
                                 Buffers: shared hit=15675 read=97758
                                 SubPlan 2
                                   ->  Seq Scan on waves w (actual rows=1240.00 loops=1)
                                         Buffers: shared hit=18
         ->  Tid Scan on fulfillment_orders (actual rows=1.00 loops=1000)
 Execution Time: 451.876 ms
```

### 인덱스 있음
```
 Delete on fulfillment_orders (actual time=1.240..1.241 rows=0.00 loops=1)
   Buffers: shared hit=2043 read=5
   ->  Nested Loop (actual rows=1000.00 loops=1)
         ->  HashAggregate (actual rows=1000.00 loops=1)
               ->  Limit (actual rows=1000.00 loops=1)
                     ->  Index Scan using ix_fulfillment_orders_cleanup on fulfillment_orders fo
                           Index Cond: (updated_at < (now() - '30 days'::interval))
                           Filter: ((status = ANY ('{CANCELLED,UNSERVICEABLE}')) OR (...))
                           Index Searches: 1
                           Buffers: shared hit=43 read=5
                           SubPlan 2
                             ->  Seq Scan on waves w (actual rows=1240.00 loops=1)
         ->  Tid Scan on fulfillment_orders (actual rows=1.00 loops=1000)
 Execution Time: 1.608 ms
```

`waves` 는 여기서도 순차 스캔이다(1,240행, 18버퍼, 0.08 ms). 4절 참고.

---

## 2. `ix_fulfillment_orders_wave (wave_id)` — 웨이브 후보 조회 (ADR-022 결정 5)

```sql
SELECT order_id, camp_id, fc_id, zone_id, promised_start, promised_end
  FROM fulfillment_orders WHERE wave_id = ? AND status = 'PLANNED'
```

| | 계획 | shared buffers | Execution Time |
|---|---|---|---|
| 인덱스 없음 | Parallel Seq Scan (워커 2) | 113,415 | **81.660 ms** |
| 인덱스 있음 | Bitmap Index Scan → Bitmap Heap Scan | 98 | **0.479 ms** |

약 **170배**, 버퍼는 113,415 → 98 이다. 인덱스 크기는 **32 MB** — 100 MB 인 `updated_at` 인덱스의
1/3 인데 행 수는 같다. 웨이브가 1,240개뿐이라 같은 `wave_id` 가 3,750번씩 반복되고, btree 중복
제거가 그것을 접기 때문이다.

```
 Bitmap Heap Scan on fulfillment_orders (actual time=0.070..0.391 rows=3675.00 loops=1)
   Recheck Cond: (wave_id = '…'::uuid)
   Filter: ((status)::text = 'PLANNED'::text)
   Rows Removed by Filter: 57
   Heap Blocks: exact=92
   Buffers: shared hit=94 read=4
   ->  Bitmap Index Scan on ix_fulfillment_orders_wave (actual rows=3732.00 loops=1)
         Index Cond: (wave_id = '…'::uuid)
         Buffers: shared hit=2 read=4
 Execution Time: 0.479 ms
```

---

## 3. 측정이 뒤집은 것 — 부분 인덱스가 아니라 전체 인덱스여야 한다

ADR-022 결정 5 는 `(wave_id) WHERE status='PLANNED'` 를 골랐다. 근거는 "마감된·취소된 주문은 그
조회의 대상이 아니라 인덱스에서도 뺀다" 였다. 재 보니 그 근거가 두 군데서 무너진다.

| | 부분 `WHERE status='PLANNED'` | 전체 `(wave_id)` |
|---|---|---|
| 인덱스 크기 | 31 MB | 32 MB |
| 웨이브 후보 조회 | 0.452 ms | 0.479 ms |
| **`waves` 90일 삭제 (40행)** | **7,067 ms** | **1.131 ms** |
| ↳ 그중 FK 트리거 | 6,681 ms | 0.566 ms |

**첫째, 부분 조건이 거르는 게 거의 없다.** 정상 상태에서 98% 가 `PLANNED` 다 — 취소는 2%,
배차 불가는 0.5% 다. 게다가 `wave_id` 는 중복 제거가 잘 듣는 컬럼이라 전체를 담아도 32 MB 다.
**부분 조건이 사는 1 MB 를 위해 붙어 있는 셈이다.**

**둘째 — 그리고 이쪽이 결정적이다 — 부분 인덱스는 FK 검사에 쓰이지 못한다.**
`fulfillment_orders.wave_id → waves(id)` 는 부모를 지울 때마다 PostgreSQL 이 참조 행을 찾는다.
플래너는 부분 인덱스의 술어(`status='PLANNED'`)가 그 검사(모든 상태)를 덮는다는 것을 증명할 수
없으므로 **웨이브 한 건마다 4.65M 행을 순차 스캔한다.** 40건 삭제에 FK 트리거만 6.7초다.

이것은 "인덱스 없는 외래 키" 의 교과서적 사례이고, 부분 인덱스는 그 관점에서 **인덱스가 없는
것과 같다.** 그래서 전체 인덱스로 간다 — 1 MB 를 더 쓰고 하루 7초를 돌려받는다.

> ADR-022 에 **[후속 정정 — Phase 2-4]** 로 붙였다. 원문은 고치지 않는다(ADR-002·ADR-017·ADR-020
> 과 같은 방식). 결정 5 가 "EXPLAIN 을 붙인다(Phase 2-4)" 라고 미뤄 둔 그 측정이 결정을 바꾼
> 것이므로, 미뤄 둔 자리에 답이 들어간 셈이다.

---

## 4. `waves` — 인덱스를 넣지 않는다 (행 수와 함께 기록)

```sql
DELETE FROM waves
 WHERE ctid IN (SELECT w.ctid FROM waves w
                 WHERE w.closed_at < now() - interval '90 days'
                   AND w.status IN ('PLANNED','PLAN_FAILED')
                   AND NOT EXISTS (SELECT 1 FROM fulfillment_orders fo WHERE fo.wave_id = w.id)
                 ORDER BY w.closed_at LIMIT 1000)
```

| 표 | 90일치 행 수 | 크기 | 인덱스 | 근거 |
|---|---|---|---|---|
| `waves` | **3,600** (하루 40 × 90) | 432 kB | **넣지 않는다** | 후보를 고르는 `Seq Scan on waves` 가 3,600행에 **0.42 ms · 50버퍼**다. 인덱스가 줄일 것이 없다 |

3절의 전체 `wave_id` 인덱스가 있으면 이 쿼리 전체가 **1.131 ms** 다. 그 인덱스는 `waves` 가
아니라 `fulfillment_orders` 쪽에 있고, 여기서 값을 하는 것은 `NOT EXISTS` 안티조인과 FK 트리거다.

**행 수를 함께 적는 이유**는 규모가 바뀌었을 때 재검토 지점이 되게 하기 위해서다. 캠프가 10에서
100으로 늘면 하루 400행 · 90일치 36,000행이 되고, 그때도 순차 스캔이 0.42 ms 의 10배인 4 ms 정도라
여전히 인덱스가 필요 없다. **재검토가 필요한 규모는 백만 행대**이며, 그것은 캠프 2,500개 수준이다.

### `NOT EXISTS` 가드를 남기는 이유

ADR-023 결정 3 은 "주문 행이 30일에 먼저 사라지므로 FK 는 자연히 만족된다" 고 적었다. 맞는
말이지만 그것은 **두 보존 기간을 그렇게 고른 결과**이지 강제되는 성질이 아니다. 어느 한쪽 기간을
바꾸면 조용히 깨지고, 깨졌을 때의 증상은 정리 배치가 FK 위반으로 매일 실패하는 것이다.
가드가 있으면 그 경우 **웨이브가 안 지워질 뿐 배치는 계속 돈다.** 전체 인덱스가 있으므로 비용은
0.57 ms 다.

---

## 5. 재현

```bash
docker run -d --name fobench -e POSTGRES_PASSWORD=x -e POSTGRES_DB=bench postgres:18.2
cat services/fulfillment-service/src/main/resources/db/migration/V{1,2}__*.sql \
  | docker exec -i fobench psql -U postgres -d bench -v ON_ERROR_STOP=1
# waves 1,240 + fulfillment_orders 4,650,000 적재(31일치) → VACUUM ANALYZE
# → EXPLAIN (ANALYZE, BUFFERS) → DROP INDEX → 반복
```

`ANALYZE` 없이 재면 플래너가 옛 통계로 다른 계획을 고른다. 인덱스를 만들거나 지운 뒤에는 반드시
다시 돌린다. 그리고 **작은 테이블에서는 계획을 단정하면 안 된다** — 순차 스캔이 이기는 구간이
있고 그것은 맞는 판단이다(`phase1-retention-indexes.md` §3 과 같은 이유로, 통합 테스트는 계획을
검증하기 전에 운영에 가까운 크기까지 채운다).
