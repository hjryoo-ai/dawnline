# 보존·게이지 인덱스 EXPLAIN 비교 (V000_4)

CLAUDE.md 불변규칙 11 — "인덱스 추가 금지(설계서 명시분 외). 필요하면 EXPLAIN 결과를 PR 설명에
첨부하고 설계서에 반영한다" — 에 따른 근거 자료다. 두 인덱스는 `docs/DESIGN.md` §5.1 DDL 에
먼저 반영한 뒤 마이그레이션 `V000_4__retention_indexes.sql` 로 들어갔다.

## 측정 환경

| 항목 | 값 |
|---|---|
| PostgreSQL | 18.2 (공식 이미지, 기본 설정) |
| 호스트 | Docker, 일회용 컨테이너 |
| `outbox_events` | 200,000 행 (전부 발행 완료, 격리 행 0) / 36 MB |
| `processed_events` | 500,000 행 (보존 만료 479,842 / 유효 20,158) / 40 MB |
| 준비 | 각 측정 전 `VACUUM ANALYZE` |
| 반복 | 3회, 아래는 대표 실행 |

격리 행 0 은 "장애가 없는 평상시" 다. 이 인덱스가 필요한 이유가 정확히 그 상태의 비용이므로
일부러 이 조건에서 측정했다.

---

## 1. `ix_outbox_failed` — 격리 게이지 (§9.1 `dawnline_outbox_failed`)

쿼리: `SELECT count(*) FROM outbox_events WHERE failed_at IS NOT NULL`
호출 빈도: **서비스당 5초마다 1회** (`dawnline.messaging.outbox.metrics-interval-ms`)

| | 계획 | shared buffers | Execution Time |
|---|---|---|---|
| 인덱스 없음 | Parallel Seq Scan (워커 2) | 4,652 | **5.24 ms** |
| 인덱스 있음 | Index Only Scan, Heap Fetches 0 | **1** | **0.025 ms** |

약 **210배** 빠르고 버퍼는 4,652 → 1 이다. 부분 인덱스 크기는 8 KB — 격리 행이 없으면
인덱스도 사실상 비어 있기 때문이다. 즉 평상시에 가장 싸다.

### 인덱스 없음
```
 Aggregate  (cost=6485.44..6485.45 rows=1 width=8) (actual time=4.396..5.220 rows=1.00 loops=1)
   Buffers: shared hit=4652
   ->  Gather  (cost=1000.00..6485.43 rows=1 width=0) (actual time=4.394..5.218 rows=0.00 loops=1)
         Workers Planned: 2
         Workers Launched: 2
         Buffers: shared hit=4652
         ->  Parallel Seq Scan on outbox_events  (cost=0.00..5485.33 rows=1 width=0) (actual time=3.645..3.645 rows=0.00 loops=3)
               Filter: (failed_at IS NOT NULL)
               Rows Removed by Filter: 66667
               Buffers: shared hit=4652
 Planning:
   Buffers: shared hit=77
 Planning Time: 0.118 ms
 Execution Time: 5.241 ms

```

### 인덱스 있음
```
 Aggregate  (cost=4.14..4.15 rows=1 width=8) (actual time=0.004..0.005 rows=1.00 loops=1)
   Buffers: shared hit=1
   ->  Index Only Scan using ix_outbox_failed on outbox_events  (cost=0.12..4.14 rows=1 width=0) (actual time=0.002..0.002 rows=0.00 loops=1)
         Heap Fetches: 0
         Index Searches: 1
         Buffers: shared hit=1
 Planning:
   Buffers: shared hit=95
 Planning Time: 0.135 ms
 Execution Time: 0.025 ms

```

---

## 2. `ix_processed_events_cleanup` — 보존 14일 정리 (§4.4)

쿼리: 정리 배치 한 번 (`ProcessedEventCleaner`, 기본 `batch-size=1000`)
```sql
DELETE FROM processed_events
 WHERE ctid IN (SELECT ctid FROM processed_events
                 WHERE processed_at < :threshold
                 ORDER BY processed_at LIMIT 1000);
```
호출 빈도: 일 1회 실행 × 배치 반복 (기본 상한 100배치)

| | 계획 | 스캔 buffers | Execution Time |
|---|---|---|---|
| 인덱스 없음 | Seq Scan → top-N heapsort (479,842행 정렬) | 5,155 | **99.2 ms** |
| 인덱스 있음 | Index Scan (LIMIT 에서 조기 종료) | **16** | **1.37 ms** |

약 **72배** 빠르다. 인덱스 크기는 11 MB (테이블 40 MB).

여기서 중요한 것은 배수가 아니라 **배치 설계와의 상호작용**이다. 인덱스가 없으면 매 배치가
테이블 전체를 훑고 정렬한다 — 즉 락 시간을 줄이려고 배치로 쪼갠 것이 오히려 총 비용을
배치 수만큼 곱한다. 100배치면 인덱스 없이 약 10초, 있으면 약 0.14초다. 배치 삭제라는
설계 선택이 이 인덱스를 전제로 성립한다.

### 인덱스 없음
```
 Delete on processed_events  (cost=40191.44..40997.94 rows=0 width=0) (actual time=99.146..99.148 rows=0.00 loops=1)
   Buffers: shared hit=7169
   ->  Nested Loop  (cost=40191.44..40997.94 rows=1000 width=36) (actual time=98.574..98.987 rows=1000.00 loops=1)
         Buffers: shared hit=6158
         ->  HashAggregate  (cost=40191.44..40193.44 rows=200 width=36) (actual time=98.567..98.617 rows=1000.00 loops=1)
               Group Key: "ANY_subquery".ctid
               Batches: 1  Memory Usage: 137kB
               Buffers: shared hit=5158
               ->  Subquery Scan on "ANY_subquery"  (cost=40176.44..40188.94 rows=1000 width=36) (actual time=98.293..98.434 rows=1000.00 loops=1)
                     Buffers: shared hit=5158
                     ->  Limit  (cost=40176.44..40178.94 rows=1000 width=14) (actual time=98.282..98.347 rows=1000.00 loops=1)
                           Buffers: shared hit=5158
                           ->  Sort  (cost=40176.44..41374.32 rows=479153 width=14) (actual time=98.281..98.301 rows=1000.00 loops=1)
                                 Sort Key: processed_events_1.processed_at
                                 Sort Method: top-N heapsort  Memory: 127kB
                                 Buffers: shared hit=5158
                                 ->  Seq Scan on processed_events processed_events_1  (cost=0.00..13905.00 rows=479153 width=14) (actual time=1.531..47.462 rows=479842.00 loops=1)
                                       Filter: (processed_at < (now() - '14 days'::interval))
                                       Rows Removed by Filter: 20158
                                       Buffers: shared hit=5155
         ->  Tid Scan on processed_events  (cost=0.00..4.01 rows=1 width=6) (actual time=0.000..0.000 rows=1.00 loops=1000)
               TID Cond: (ctid = "ANY_subquery".ctid)
               Buffers: shared hit=1000
 Planning:
   Buffers: shared hit=186
 Planning Time: 0.223 ms
 Execution Time: 99.191 ms

```

### 인덱스 있음
```
 Delete on processed_events  (cost=51.75..858.25 rows=0 width=0) (actual time=1.325..1.326 rows=0.00 loops=1)
   Buffers: shared hit=2011 read=5
   ->  Nested Loop  (cost=51.75..858.25 rows=1000 width=36) (actual time=0.494..1.111 rows=1000.00 loops=1)
         Buffers: shared hit=1011 read=5
         ->  HashAggregate  (cost=51.75..53.75 rows=200 width=36) (actual time=0.492..0.568 rows=1000.00 loops=1)
               Group Key: "ANY_subquery".ctid
               Batches: 1  Memory Usage: 137kB
               Buffers: shared hit=11 read=5
               ->  Subquery Scan on "ANY_subquery"  (cost=0.43..49.25 rows=1000 width=36) (actual time=0.056..0.339 rows=1000.00 loops=1)
                     Buffers: shared hit=11 read=5
                     ->  Limit  (cost=0.43..39.25 rows=1000 width=14) (actual time=0.052..0.245 rows=1000.00 loops=1)
                           Buffers: shared hit=11 read=5
                           ->  Index Scan using ix_processed_events_cleanup on processed_events processed_events_1  (cost=0.43..18604.71 rows=479273 width=14) (actual time=0.051..0.182 rows=1000.00 loops=1)
                                 Index Cond: (processed_at < (now() - '14 days'::interval))
                                 Index Searches: 1
                                 Buffers: shared hit=11 read=5
         ->  Tid Scan on processed_events  (cost=0.00..4.01 rows=1 width=6) (actual time=0.000..0.000 rows=1.00 loops=1000)
               TID Cond: (ctid = "ANY_subquery".ctid)
               Buffers: shared hit=1000
 Planning:
   Buffers: shared hit=204
 Planning Time: 0.282 ms
 Execution Time: 1.372 ms

```

---

## 3. 인덱스가 이기지 않는 구간

`ix_outbox_failed` 는 테이블이 **작으면 쓰이지 않는다**. 통계를 갱신한 상태에서 플래너의 선택은
행 수에 따라 이렇게 갈렸다.

| 행 수 | 선택된 계획 |
|---|---|
| 0 · 10 · 100 | Seq Scan |
| 500 · 1,000 · 2,000 · 5,000 | Index Only Scan |

작은 테이블에서 순차 스캔이 더 싼 것은 맞는 판단이므로 문제가 아니다. 다만 **통합 테스트가
빈 테이블에서 계획을 단정하면 안 된다**는 뜻이라, `ProcessedEventRetentionIT` 는 계획을
검증하기 전에 운영에 가까운 크기까지 행을 채우고 `ANALYZE` 한다.

## 4. 재현

```bash
docker run -d --name pg -e POSTGRES_PASSWORD=x -e POSTGRES_DB=bench postgres:18.2
cat libs/messaging/src/main/resources/db/migration/common/V000_{1,2,3}__*.sql \
  | docker exec -i pg psql -U postgres -d bench -v ON_ERROR_STOP=1
# 행 채우기 → VACUUM ANALYZE → EXPLAIN (ANALYZE, BUFFERS) → CREATE INDEX → 반복
```
