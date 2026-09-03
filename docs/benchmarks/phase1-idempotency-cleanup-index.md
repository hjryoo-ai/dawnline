# `ix_idempotency_keys_cleanup` EXPLAIN 비교 (V1)

CLAUDE.md 불변규칙 11 — "인덱스 추가 금지(설계서 명시분 외). 필요하면 EXPLAIN 결과를 PR 설명에
첨부하고 설계서에 반영한다" — 에 따른 근거 자료다. 인덱스는 `docs/DESIGN.md` §5.1 DDL 에 먼저
반영한 뒤 `V1__orders.sql` 에 들어갔다(ADR-019).

## 측정 환경

| 항목 | 값 |
|---|---|
| PostgreSQL | 18.2 (공식 이미지, 기본 설정) |
| 호스트 | Docker, 일회용 컨테이너 |
| `idempotency_keys` | 1,050,000 행 / 337 MB |
| 만료 행 | 233,332 (약 하루치) |
| 준비 | 각 측정 전 재적재 + `ANALYZE` |

**행 수의 근거**: §8.1 피크는 150,000 주문/일이고 보존은 7일이므로 정상 상태의 테이블은
약 100만 행이다. 정리기는 하루에 한 번 도니까 평상시 만료 행은 "하루치" 정도만 쌓여 있다.
그 상태에서 재는 것이 의미 있는 측정이다.

정리 쿼리는 `ProcessedEventCleaner` 와 같은 모양이다 — 복합 PK 가 있는 테이블에서 `LIMIT` 을 건
삭제를 하려면 `ctid` 를 거쳐야 한다.

```sql
DELETE FROM idempotency_keys
 WHERE ctid IN (SELECT ctid FROM idempotency_keys
                 WHERE expires_at < :threshold
                 ORDER BY expires_at
                 LIMIT :batchSize)
```

---

## 1. 배치 하나 (LIMIT 1000)

| | 계획 | shared buffers | Execution Time |
|---|---|---|---|
| 인덱스 없음 | Seq Scan 1,050,000행 + top-N heapsort | 38,210 | **78.196 ms** |
| 인덱스 있음 | Index Scan, 정렬 없음 | 2,314 | **1.186 ms** |

인덱스 크기는 **7,128 kB** — 337 MB 테이블의 2.1% 다.

정렬이 사라지는 것이 핵심이다. `ORDER BY expires_at LIMIT 1000` 은 인덱스가 없으면 100만 행을
전부 읽어 상위 1000개를 골라야 한다. 인덱스가 있으면 왼쪽 끝에서 1000개를 읽고 멈춘다.

### 인덱스 없음
```
 Delete on idempotency_keys (actual rows=0.00 loops=1)
   Buffers: shared hit=15884 read=22326 dirtied=311 written=1
   ->  Nested Loop (actual rows=1000.00 loops=1)
         ->  HashAggregate (actual rows=1000.00 loops=1)
               ->  Subquery Scan on "ANY_subquery" (actual rows=1000.00 loops=1)
                     ->  Limit (actual rows=1000.00 loops=1)
                           ->  Sort (actual rows=1000.00 loops=1)
                                 Sort Key: idempotency_keys_1.expires_at
                                 Sort Method: top-N heapsort  Memory: 107kB
                                 ->  Seq Scan on idempotency_keys idempotency_keys_1 (actual rows=233332.00 loops=1)
                                       Filter: (expires_at < now())
                                       Rows Removed by Filter: 816668
                                       Buffers: shared hit=14192 read=22015
         ->  Tid Scan on idempotency_keys (actual rows=1.00 loops=1000)
               TID Cond: (ctid = "ANY_subquery".ctid)
 Execution Time: 78.196 ms
```

### 인덱스 있음
```
 Delete on idempotency_keys (actual rows=0.00 loops=1)
   Buffers: shared hit=2311 read=3
   ->  Nested Loop (actual rows=1000.00 loops=1)
         ->  HashAggregate (actual rows=1000.00 loops=1)
               ->  Subquery Scan on "ANY_subquery" (actual rows=1000.00 loops=1)
                     ->  Limit (actual rows=1000.00 loops=1)
                           ->  Index Scan using ix_idempotency_keys_cleanup on idempotency_keys idempotency_keys_1 (actual rows=1000.00 loops=1)
                                 Index Cond: (expires_at < now())
                                 Index Searches: 1
                                 Buffers: shared hit=311 read=3
         ->  Tid Scan on idempotency_keys (actual rows=1.00 loops=1000)
               TID Cond: (ctid = "ANY_subquery".ctid)
 Execution Time: 1.186 ms
```

---

## 2. 하루치 전량 정리 (233,332행 = 234배치)

배치 하나의 숫자를 234배 한 값은 실제와 다르다. 배치를 반복하면 앞 배치가 지운 행이 남아 있어
뒤 배치가 그것을 다시 지나가야 하기 때문이다. 그래서 전량을 실제로 돌려 봤다.

| | 소요 |
|---|---|
| 인덱스 없음 | **13.47 s** |
| 인덱스 있음 | **0.47 s** |

약 **29배**다. 배치 하나의 66배보다 작다 — 인덱스 쪽에도 이미 지운 항목을 지나가는 비용이
조금 붙기 때문이다.

### 여기서 나온 뜻밖의 결과: 배치마다 커밋하지 않으면 인덱스 효과가 거의 사라진다

같은 측정을 **한 트랜잭션 안에서** 234배치를 도는 방식으로 먼저 해 봤다.

| | 소요 (한 트랜잭션) | 소요 (배치마다 커밋) |
|---|---|---|
| 인덱스 없음 | 13.53 s | 13.47 s |
| 인덱스 있음 | **11.29 s** | **0.47 s** |

한 트랜잭션으로 묶으면 인덱스가 있어도 24배가 아니라 1.2배밖에 빨라지지 않는다. 지운 항목이
아직 커밋되지 않아 죽은 것으로 표시할 수 없고, 그래서 매 배치가 앞 배치들이 지운 항목을 전부
다시 걸어야 하기 때문이다(k번째 배치가 k×1000개를 지나간다 — 배치 수에 대해 제곱이다).
커밋하면 PostgreSQL 이 인덱스 항목을 죽은 것으로 표시할 수 있어(`kill_prior_tuple`) 그 비용이 사라진다.

**배치마다 트랜잭션을 닫는 것은 잠금 시간을 줄이기 위해서만이 아니다.** 그렇게 해야 이 인덱스가
값을 한다. `ProcessedEventCleaner` 와 `IdempotencyKeyCleaner` 가 배치마다 커밋하는 이유가
하나 더 생긴 셈이고, 이 표가 그 근거다.

---

## 3. 결론

인덱스를 넣는다. 근거는 두 가지다.

1. **정리 배치라는 설계가 이 인덱스를 전제로 성립한다.** 없으면 하루치 정리가 13초 동안 테이블을
   234번 훑는다. 그 시간에 쓰기 경로가 같은 페이지를 두고 경쟁한다.
2. **값이 싸다.** 7 MB, 테이블의 2.1%. `expires_at` 은 단조 증가에 가까워 인덱스가 오른쪽 끝에만
   붙으므로 쓰기 비용도 작다.

## 4. 재현

```bash
docker run -d --rm --name pg-idem -e POSTGRES_PASSWORD=x -e POSTGRES_DB=t -p 55444:5432 postgres:18.2
# 1,050,000행 적재 → ANALYZE → EXPLAIN (ANALYZE, BUFFERS) → CREATE INDEX → 반복
# 전량 정리는 배치마다 COMMIT 하는 프로시저로 돌린다 (§2 의 이유)
docker stop pg-idem
```
