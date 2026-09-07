# `ix_cand_wave` 는 몇 행부터 값을 하는가 (EXPLAIN)

CLAUDE.md 불변규칙 11 — "**넣지 않기로 한 판단도 행 수와 함께 기록한다**" — 에 따른 근거 자료다.
여기서는 인덱스를 넣거나 빼지 않는다. `ix_cand_wave (wave_id, status)` 는 DESIGN.md §5.3 DDL 에
이미 명시돼 있다. 기록하는 것은 **그 인덱스가 언제부터 선택되는가**, 그리고 그 사실을 확인하는
통합 테스트가 왜 2026-09-07 까지 아무것도 확인하지 못하고 있었는가다.

## 계기

`DispatchPersistenceIT.계획_대상_조회가_인덱스를_탄다` 가 CI 에서만 깨졌다(run 33963710346).

```
Seq Scan on dispatch_candidates  (cost=0.00..4.39 rows=1 width=230)
  Filter: ((wave_id = '01a0715c-…'::uuid) AND ((status)::text = 'PENDING'::text))
to contain: "ix_cand_wave"
```

로컬에서는 같은 커밋이 통과했다. 갈린 것은 인덱스가 아니라 **통계였다.**

## 측정 환경

| 항목 | 값 |
|---|---|
| PostgreSQL | 18.2 (공식 이미지, 기본 설정) |
| 호스트 | Docker Desktop 29.1.3 (macOS arm64) |
| 스키마 | `V1__dispatch.sql` 그대로 적용 |
| 질의 | `SELECT * FROM dispatch_candidates WHERE wave_id = ? AND status = 'PENDING' ORDER BY order_id` |
| 준비 | 적재 후 `ANALYZE dispatch_candidates` (1절은 의도적으로 생략) |

질의 모양은 `JpaDispatchCandidateRepository.FIND_PLANNABLE_JPQL` 을 따랐다 — `ORDER BY c.orderId`
까지 포함한다. 빼고 재면 서비스가 돌리지 않는 계획을 인증하게 된다.

---

## 1. 통계가 없으면 계획은 아무것도 말해 주지 않는다

50행을 넣고, `ANALYZE` 전후로 같은 질의를 본다.

| 상태 | `pg_class` (relpages, reltuples) | 계획 |
|---|---|---|
| `ANALYZE` 전 | `0, -1` (통계 없음) | `Index Scan using ix_cand_wave (cost=0.15..8.17)` |
| `ANALYZE` 후 | `2, 50` | `Seq Scan (cost=0.00..2.75)` |

통계가 없을 때 PostgreSQL 14+ 는 `reltuples = -1` 로 두고 기본 추정치로 판단한다. 그 추정치가
**50행짜리 테이블에서도 인덱스를 고른다.** 50행에서 옳은 계획은 순차 스캔이다.

그래서 옛 테스트는 인덱스가 값을 한다는 것을 증명한 적이 없다. 증명한 것은 "플래너가 아직 이
테이블을 본 적이 없다" 였고, autovacuum 이 언제 도느냐에 따라 통과와 실패가 갈렸다. CI 는
전체 스위트를 돌려 autoanalyze 가 먼저 걸렸고, 로컬 단독 실행은 그러지 않았다.

## 2. 교차점 — 400행

대상 웨이브 50건을 고정하고 다른 웨이브를 늘려 가며 (`ANALYZE` 후) 측정.

| 총 행 수 | 웨이브 수 | 계획 |
|---:|---:|---|
| 250 | 5 | `Seq Scan (cost=0.00..9.75)` |
| 300 | 6 | `Seq Scan (cost=0.00..11.50)` |
| 350 | 7 | `Seq Scan (cost=0.00..13.25)` |
| **400** | **8** | **`Bitmap Index Scan on ix_cand_wave (cost=4.79..14.54)`** |
| 2,000 | 40 | `Bitmap Index Scan on ix_cand_wave (cost=4.79..50.72)` |
| 20,000 | 400 | `Bitmap Index Scan on ix_cand_wave (cost=4.80..149.21)` |

**350행 이하에서 순차 스캔은 결함이 아니라 옳은 판단이다.** 2 페이지짜리 테이블을 훑는 비용이
인덱스 페이지 한 장을 무작위로 읽는 비용보다 싸다. 이 표가 있어야 다음 사람이 순차 스캔을 보고
"인덱스가 깨졌다" 고 오해하지 않는다.

## 3. 한 웨이브가 테이블의 큰 몫이면 순차 스캔이 맞다

대상 웨이브를 5,000건(Phase 3 통합 계획과 같은 크기)으로 두고 나머지를 늘려 가며 측정.

| 대상 웨이브 | 총 행 수 | 비중 | 계획 |
|---:|---:|---:|---|
| 5,000 | 10,000 | 50% | `Seq Scan (cost=0.00..363.00)` |
| 5,000 | 25,000 | 20% | `Bitmap Index Scan on ix_cand_wave` |
| 5,000 | 105,000 | 4% | `Bitmap Index Scan on ix_cand_wave` |

운영에서 `dispatch_candidates` 는 여러 캠프·티어·컷오프의 웨이브를 함께 담으므로 한 웨이브의
비중은 낮다. 다만 **스택을 갓 띄운 직후에는 웨이브가 하나뿐이라 비중이 100% 다** — 그때
`make demo` 의 계획이 순차 스캔을 타는 것은 정상이며, 인덱스를 의심할 자리가 아니다.

## 4. 테스트에 반영한 것

`DispatchPersistenceIT.계획_대상_조회가_인덱스를_탄다` 는 이제

1. 20,000행(대상 웨이브 50건 + 다른 웨이브 19,950건)까지 채우고,
2. `ANALYZE` 한 뒤,
3. **전제를 첫 두 어설션으로 스스로 말하고** — `reltuples > 0`(통계가 있다), 행 수 20,000(교차점 위다) —
4. 계획에 `ix_cand_wave` 가 있는지 본다.

전제 어설션이 필요한 이유는 1절이다. `ANALYZE` 를 빼면 **계획 어설션은 그대로 통과한다**
(짐작으로 인덱스를 고르므로). 그 통과를 실패로 바꾸는 것은 전제 어설션뿐이다.
`FulfillmentPersistenceIT` · `FulfillmentRetentionIT` · `ProcessedEventRetentionIT` 의 EXPLAIN
테스트는 이미 크기와 `ANALYZE` 를 갖추고 있었다 — 넷 중 이 하나만 빠져 있었다.

### 검증 (전제가 살아 있는지)

| 실험 | 결과 |
|---|---|
| 다른 웨이브 19,950 → 250 (총 300행) | `Seq Scan` 으로 **실패** — CI 실패와 같은 모양 |
| `analyzeCandidates()` 주석 처리 | "전제: 통계가 있어야 한다" 로 **실패** |
| 원상 복구 | 6개 통과 |
