# dispatch 보존 정리의 계획 — 계획 단위 삭제 (EXPLAIN, 7-0c)

CLAUDE.md 불변규칙 11에 따른 근거 자료입니다.
[ADR-059](../adr/ADR-059-dispatch-retention-is-per-plan.md)가 dispatch 여섯 표를 계획 단위로 지우기로 하면서
인덱스 판단과 트랜잭션 크기를 이 측정으로 미뤄 두었습니다. 결론은 **새 인덱스가 없다**입니다.
모든 삭제 문장이 기존 인덱스의 앞머리를 타고, 인덱스 없이 순차 스캔하는 문장 둘은 하루 한 번이며 행 수와 함께 적습니다.

| 문장 | 운영 크기 | 판단 |
|---|---:|---|
| 고르기 넷 (`route_plans`) | 91일치 3,641 | 넣지 않는다 — 계획 표 순차 스캔 0.1–0.6 ms, 문장 전체 110 ms 이하 |
| 계열 삭제 여섯 | 계획 하나 | 넣지 않는다 — 전부 기존 인덱스의 앞머리 |
| 걸린 계획 셈 | `route_stops` 765만 | 넣지 않는다 — 하루 한 번 0.24–0.39초 |
| 계획 없는 웨이브의 후보(상한) | `dispatch_candidates` 465만 | 넣지 않는다 — 하루 한 번 0.35–0.45초 |

## 측정 환경

| 항목 | 값 |
|---|---|
| PostgreSQL | 18.2 (공식 이미지, 기본 설정), Testcontainers |
| 호스트 | macOS 27.0, arm64, 14코어. Docker Desktop 29.1.3에 4 vCPU와 6.8 GB 할당 |
| 스키마 | dispatch `V1`–`V10`. 이 측정을 위한 인덱스는 만들지 않았다 |
| 질의 | `JdbcDispatchRetention`의 상수 그대로. 상태 값은 리터럴이고 임계 · `LIMIT` · 계획 id 는 바인드 |
| 준비 | 채운 뒤 여섯 표 모두 `VACUUM ANALYZE` — `reltuples` 가 행 수와 같음을 확인(§1) |
| 계측 | 측정용 IT 는 측정 뒤 삭제했습니다(ADR-029 전례). `PREPARE` 뒤 `SET LOCAL plan_cache_mode` 로 custom · generic 을 따로, `EXPLAIN (ANALYZE, BUFFERS) EXECUTE` 는 롤백하는 트랜잭션 안에서 |

**행 수는 운영 규모에서 잡았습니다.**

- 하루 40웨이브(= 40계획), 계획마다 라우트 30 · stop 70 · 주문 125(stop 55개는 주문 둘)입니다. 피크일 15만 주문(§8.2)을
  40웨이브로 나눈 크기입니다.
- 계획 계열은 91일, 후보(웨이브당 3,750) · 설명(계획당 5,000)은 31일 채웠습니다. 정리기는 하루에 한 번 돌므로
  **만료가 정확히 하루치**인 평상시 모양입니다.
- 오늘치 계획(40)의 stop 은 전부 `PLANNED` 입니다 — 배송 중입니다(84,000 stop).
- 걸린 계획 셋: 40일 전 계획 셋의 stop 하나씩을 `PLANNED` 로 남겼습니다.
- 벤치마크 `peak` 크기의 계획 하나를 91일 전에 두었습니다: 라우트 216 · stop 8,411 · 주문 15,000 · 후보 15,000 ·
  설명 15,000. **여섯 표가 전부 남은 채로** 계열째 지웁니다 — 트랜잭션 하나의 최악입니다(§4).

---

## 1. 표 크기 (`VACUUM ANALYZE` 뒤)

| 표 | `reltuples` | 힙 | 인덱스 포함 |
|---|---:|---:|---:|
| `route_plans` | 3,641 | 736 kB | 1,064 kB |
| `routes` | 109,416 | 15 MB | 19 MB |
| `route_stops` | 7,651,055 | 991 MB | 1,587 MB |
| `route_stop_orders` | 13,665,008 | 785 MB | 1,843 MB |
| `dispatch_candidates` | 4,665,033 | 847 MB | 1,017 MB |
| `plan_explanations` | 6,214,715 | 1,183 MB | 1,762 MB |

계열 여섯 표가 **8.1 GB** 입니다(후보 · 설명은 31일치). 보존이 없으면 이 크기에 상한이 없습니다.

## 2. 고르기 — 계획 3,641행을 순차로 읽는다

```sql
SELECT p.id, p.wave_id FROM route_plans p
 WHERE p.finished_at < ?
   AND p.status IN ('PUBLISHED', 'FAILED')
   AND NOT EXISTS (SELECT 1 FROM routes r JOIN route_stops s ON s.route_id = r.id
                    WHERE r.plan_id = p.id AND s.status NOT IN ('CANCELLED', 'COMPLETED', 'FAILED'))
   AND EXISTS (SELECT 1 FROM plan_explanations e WHERE e.plan_id = p.id)   -- 설명 단계
 ORDER BY p.finished_at LIMIT ?
```

| 문장 | 계획 | custom | generic |
|---|---|---:|---:|
| 설명 단계 (30일) | Seq Scan `route_plans` 2,441 → Semi Join `ix_expl_plan_order` Index Only (Heap Fetches 0) → Anti Join `ix_routes_plan` · `route_stops_route_id_seq_key` | **110.4 ms** | **31.2 ms** |
| 후보 단계 (30일) | 같은 모양, Semi Join 이 `ix_cand_wave` Index Only | **53.5 ms** | **24.3 ms** |
| 계열 단계 (90일) | Seq Scan 41 → 같은 Anti Join | **46.4 ms** | **29.9 ms** |
| 상한 (365일) | Seq Scan 3,641, 결과 0 | **0.11 ms** | **0.13 ms** |

- **Semi Join 이 먼저 걸러 냅니다.** 30일을 넘긴 계획은 2,441개지만 설명 · 후보가 남은 것은 41개(하루치 40 + peak)뿐이고,
  Anti Join(「끝나지 않은 stop 이 없다」)은 그 41개에만 돕니다. 계획 41개 × 라우트 약 35 = 1,416번의 인덱스 탐색입니다.
- Anti Join 의 비용은 라우트마다 stop 약 65개를 필터로 읽는 것입니다(`route_stops_route_id_seq_key` 의 앞머리 `route_id`,
  상태는 필터). 버퍼 약 9만 개 hit — 계획 하나에 약 1 ms 입니다.
- `route_plans` 에 `finished_at` 인덱스는 넣지 않습니다. 3,641행(91일 × 40)이고 365일 상한으로도 14,600행입니다.
  **재검토 지점은 10만 행** — 하루 웨이브가 1,000개를 넘을 때입니다.

**첫 실행은 재지 않았습니다(근거: 추정).** 보존을 켜기 전에 쌓인 계획이 있으면 설명 · 후보가 남은 계획이 2,441개 전부이고,
Anti Join 이 그 전부에 돕니다 — 위의 계획 하나 1 ms 로 셈하면 고르기 한 번이 약 2.5초입니다. 한 실행이 단계마다 200계획만
다루므로(`max-plans-per-run`) 밀린 몫은 약 12일에 걸쳐 빠지고, 그동안 고르기는 하루 세 번 몇 초입니다.

## 3. 30일 단계와 상한 — 계획 하나

| 문장 | 계획 | 행 | custom | generic |
|---|---|---:|---:|---:|
| `DELETE FROM plan_explanations WHERE plan_id = ?` | Bitmap `ix_expl_plan_order` | 5,000 | **51.2 ms** | **47.7 ms** |
| `DELETE FROM dispatch_candidates WHERE wave_id = ?` | Bitmap `ix_cand_wave` | 3,750 | **46.3 ms** | **13.1 ms** |
| 계획 없는 웨이브의 후보 (365일, `ctid` 배치) | **Seq Scan 465만**, 결과 0 | 0 | **445 ms** | **347 ms** |

```
-- 계획 없는 웨이브의 후보 (custom)
->  Nested Loop Anti Join (actual time=441.146..441.146 rows=0.00 loops=1)
      Join Filter: (p.wave_id = c.wave_id)
      ->  Seq Scan on dispatch_candidates c (actual time=441.145..441.145 rows=0.00 loops=1)
            Filter: (updated_at < '2025-09-24 23:03:58.017049+00'::timestamp with time zone)
            Rows Removed by Filter: 4665000
      ->  Seq Scan on route_plans p (never executed)
Execution Time: 445.111 ms
```

**계획 없는 웨이브의 후보에는 인덱스를 넣지 않습니다.**

- 상한입니다. 계획이 없는 웨이브의 후보는 계획이 실패 이벤트도 없이 사라진 경우에만 생기고, 평상시 결과는 0행입니다.
- 결과가 0이면 배치 하나에서 끝납니다 — **하루 한 번 0.35–0.45초**. `(updated_at)` 인덱스는 이것을 0.01 ms 로 줄이지만
  그 대가로 후보의 상태 전이(재배정 · 취소)마다 인덱스를 하나 더 옮기고 HOT 을 잃습니다.
- 7-0b 의 `shipments` · `rm_orders` 는 같은 모양에서 인덱스를 넣었습니다([phase7-retention-indexes](phase7-retention-indexes.md)).
  차이는 그쪽 상한이 **본 정리와 같은 칸**을 쓴다는 것입니다 — 본 정리가 날마다 150배치를 도니 인덱스가 값을 했습니다.
  여기서 본 정리는 `wave_id` 로 지우고(`ix_cand_wave`), 순차 스캔은 상한의 한 번뿐입니다.
- **재검토 지점**: 이 배치가 0이 아닌 행을 지우기 시작할 때(로그 「계획 없는 웨이브의 후보 N건 삭제」), 또는 표가
  1,500만 행을 넘을 때(순차 스캔 약 1.5초).

## 4. 계열 삭제 — 계획 하나, 트랜잭션 하나

자식부터: `route_stop_orders` → `route_stops` → `routes` → `plan_explanations` → `dispatch_candidates` → `route_plans`.

```sql
DELETE FROM route_stop_orders o USING route_stops s, routes r
 WHERE o.stop_id = s.id AND s.route_id = r.id AND r.plan_id = ?
DELETE FROM route_stops s USING routes r WHERE s.route_id = r.id AND r.plan_id = ?
DELETE FROM routes WHERE plan_id = ?
DELETE FROM plan_explanations WHERE plan_id = ?
DELETE FROM dispatch_candidates WHERE wave_id = ?
DELETE FROM route_plans WHERE id = ?
```

### 4.1 평상시 — 90일 계획 하나 (설명 · 후보는 30일에 이미 빠졌다)

| 표 | 계획 | 행 | custom | generic | FK 트리거 (custom · generic) |
|---|---|---:|---:|---:|---|
| `route_stop_orders` | `ix_routes_plan` → `route_stops_route_id_seq_key` → `route_stop_orders_pkey` (앞머리 `stop_id`) | 3,750 | 48.8 ms | 18.7 ms | — |
| `route_stops` | `ix_routes_plan` → `route_stops_route_id_seq_key` | 2,100 | 4.5 ms | 1.3 ms | `route_stop_orders_stop_id_fkey` 83.2 · 18.5 ms (2,100번) |
| `routes` | `ix_routes_plan` | 30 | 0.1 ms | 0.04 ms | `route_stops_route_id_fkey` 2.1 · 1.0 ms (30번) |
| `plan_explanations` | `ix_expl_plan_order` | 0 | 0.01 ms | 0.01 ms | — |
| `dispatch_candidates` | `ix_cand_wave` | 0 | 0.02 ms | 0.01 ms | — |
| `route_plans` | `route_plans_pkey` | 1 | 0.01 ms | 0.01 ms | `routes_plan_id_fkey` · `plan_explanations_plan_id_fkey` 0.07 · 0.06 ms |
| **합** | | **5,881** | **약 139 ms** | **약 40 ms** | |

**FK 트리거가 삭제보다 비쌉니다** — `route_stops` 를 지울 때 행마다 `route_stop_orders` 에 남은 참조가 없음을 확인합니다.
그 확인이 `route_stop_orders_pkey (stop_id, order_id)` 의 앞머리를 타므로 행당 약 0.01–0.04 ms 입니다. 자식을 먼저 지웠기
때문에 확인은 전부 「없음」으로 끝납니다. 순서를 바꾸면(`route_stops` 를 먼저) 그 문장이 `route_stop_orders_stop_id_fkey` 위반으로
실패합니다 — 조용히 틀리지 않습니다(`DispatchRetentionIT` 의 음성 표본, 관측).

`dispatch_candidates` 에는 계획으로의 FK 가 없습니다(웨이브 id 로만 잇는다). 그래서 후보는 부모를 먼저 지워도 막히지 않고,
**계열 삭제가 후보를 빠뜨려도 FK 가 알려 주지 않습니다** — `DispatchRetentionIT` 가 계열 삭제 뒤 그 웨이브의 후보가 0인지 봅니다.

### 4.2 최악 — `peak` 계획 하나, 여섯 표가 전부 남았다

| 표 | 행 | custom | generic |
|---|---:|---:|---:|
| `plan_explanations` | 15,000 | 9.1 ms | 5.7 ms |
| `dispatch_candidates` | 15,000 | 4.2 ms | 3.3 ms |
| `route_stop_orders` | 15,000 | 11.9 ms | 9.8 ms |
| `route_stops` | 8,411 | 91.7 ms (그중 FK 트리거 88.3 ms) | 30.8 ms (27.9 ms) |
| `routes` | 216 | 2.8 ms | 1.1 ms |
| `route_plans` | 1 | 0.9 ms | 0.8 ms |
| **합** | **53,628** | **약 121 ms** | **약 51 ms** |

이 모양은 두 경우에만 생깁니다.

1. **상한이 끝나지 않은 계획을 지울 때.** 걸린 계획의 설명 · 후보는 30일 단계가 건너뛰므로(종결이 아니다) 365일에 여섯 표가
   함께 빠집니다.
2. **설정이 `candidates = explanations = plans` 일 때.** 설정 레코드가 허용하는 경계입니다.

평상시의 `peak` 계획은 30일에 설명(15,000)과 후보(15,000)를 **각각 다른 트랜잭션**에서 지우고, 90일에 나머지 넷
**23,628행**을 한 트랜잭션에서 지웁니다. **트랜잭션 크기의 상한은 「계획 하나」이고, `peak` 계획이면 평상시 약 2.4만 행,
최악 약 5.4만 행입니다.** ADR-059 결정 6 의 「약 3만」은 이 측정 전의 추정이었고 이 숫자로 바꿉니다.

계획이 generic 에서 5,008행을 추정하고 15,000행을 만나도 모양이 같습니다 — 추정은 평균 계획에서 왔고, 앞머리 탐색은 행 수와
무관하게 같은 경로입니다.

## 5. 걸린 계획의 셈 — `dawnline_route_plans_stuck`

```sql
SELECT count(*) FROM route_plans p
 WHERE COALESCE(p.finished_at, p.started_at) < ?
   AND NOT (p.status IN ('PUBLISHED', 'FAILED')
            AND NOT EXISTS (SELECT 1 FROM routes r JOIN route_stops s ON s.route_id = r.id
                             WHERE r.plan_id = p.id AND s.status NOT IN ('CANCELLED', 'COMPLETED', 'FAILED')))
```

| | 계획 | 결과 | 시간 |
|---|---|---:|---:|
| custom | Seq Scan `route_plans` + hashed SubPlan: **Parallel Seq Scan `route_stops` 765만**(워커 2) ⋈ Parallel Hash `routes` | 3 | **389 ms** (JIT 144 ms 포함) |
| generic | 같음 | 3 | **239 ms** |

- 플래너는 NOT EXISTS 를 부정한 모양을 **「끝나지 않은 stop 을 가진 계획 id 전부」의 해시**로 바꿨습니다. 그 집합은
  오늘 배송 중인 stop 84,000 + 걸린 3 입니다. `route_stops.status` 에 인덱스가 없으므로 표 전체를 읽습니다.
- **넣지 않습니다.**
  - 하루 한 번입니다.
  - `route_stops` 는 이제 90일 보존이라 크기가 묶였습니다 — 765만 행(991 MB)이 평상시 최대이고, 걸린 계획이 365일까지
    남아도 그 계획의 stop 만큼만 더해집니다.
  - `status` 인덱스(부분이든 아니든)는 기사 스캔이 옮기는 칸을 키로 가져 모든 stop 전이의 HOT 을 막습니다. 전이가 하루
    수십만 번이고 셈은 하루 한 번입니다.
- **재검토 지점**: `route_stops` 가 2,000만 행을 넘을 때(하루 stop 22만 = 현재의 약 2.6배), 또는 셈이 1초를 넘을 때.

## 6. 지키는 것

- `DispatchRetentionIT` (남는다)
  - 계획 2,000 · stop 12만 규모로 채우고 `ANALYZE` 한 뒤 첫 어설션으로 **통계가 있다**(여섯 표 모두 `reltuples` > 1,000)를
    말하고, 계열 삭제 여섯 문장이 custom · generic 두 계획에서 **기존 인덱스를 타고 순차 스캔이 없다**를 봅니다 —
    위 §4.1 의 인덱스 이름 그대로. 문장은 어댑터의 상수를 그대로 준비합니다.
  - 걸린 계획 셈과 고르기가 같은 스냅숏에서 **여집합**인지 봅니다: 지우기 전에 센 걸린 계획 + 종결 계획 = 나이를 넘긴
    계획 전부.
- 순차 스캔 둘(§3 계획 없는 웨이브, §5 셈)은 계획을 검사하지 않습니다. 인덱스가 없으므로 지킬 계획이 없고, 판단의
  근거는 이 문서의 행 수입니다.
