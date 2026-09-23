# 기사가 가장 멀리 닿은 stop 을 찾는다 — **인덱스를 넣지 않는다** (EXPLAIN)

CLAUDE.md 불변규칙 11 에 따른 근거 자료다. 그 규칙은 넣는 판단만이 아니라 **넣지 않기로 한
판단도 행 수와 함께** 기록하게 한다 — 기록이 없으면 다음 사람은 「검토했는데 안 넣은 것」과
「생각하지 못한 것」을 구별할 수 없다.

## 계기

[ADR-048](../adr/ADR-048-replan-reads-its-own-db.md) 결정 1 이 §6.8 재계획의 편차를
**자기 DB** 에서 읽게 했다. 그 편차의 출처가 이 질의다:

```sql
SELECT seq, planned_arrival, actual_at FROM route_stops
 WHERE route_id = ? AND actual_at IS NOT NULL
 ORDER BY seq DESC
 LIMIT 1
```

`JdbcRouteMutations.lastSettledStop` 그대로다. **빈도**는 at-risk 하나당
`1 + 후보 라우트 수` 다(원 라우트 + §6.8 2단계의 후보 전부). 계획당 라우트가 71 대면 at-risk
하나에 최대 72 번이고, 그것이 「라우트당 10분 쿨다운」 안에서 일어난다.

## 측정 환경

| 항목 | 값 |
|---|---|
| PostgreSQL | 18.2 (공식 이미지, 기본 설정) · Testcontainers |
| 호스트 | macOS 27.0 · aarch64 · Docker Desktop |
| 스키마 | `V1`–`V10` 적용 |
| 질의 | `JdbcRouteMutations.lastSettledStop` 그대로 |
| 준비 | 적재 후 `ANALYZE routes, route_stops, route_stop_orders` |
| 계측 | `RouteStopOrdersIndexIT.마지막으로_닿은_stop_을_찾는_질의는_새_인덱스가_필요하지_않다` |

**행 수는 운영 규모에서 잡았다** — 같은 클래스의 앞 측정과 같은 seed 다.

| 테이블 | 행 수 |
|---|---|
| `routes` | 71 (피크일 한 캠프) |
| `route_stops` | **8,520** (라우트당 120, 설계 상한) |
| `route_stop_orders` | 14,910 |
| 그중 닿은 stop (`actual_at IS NOT NULL`) | 2,130 (라우트당 30 — 기사가 4분의 1쯤 간 상태) |

`ANALYZE` 는 생략하지 않았다. 통계가 없으면 `reltuples = -1` 이고 플래너는 기본 추정치로
**짐작하며 인덱스를 고른다** — 그때의 「인덱스를 탔다」는 아무것도 증명하지 않는다.
테스트는 그래서 **「통계가 있다」를 첫 어설션으로 말한다.**

---

## 결과

```
Limit  (cost=0.29..13.93 rows=1 width=18) (actual time=0.009..0.010 rows=1.00 loops=1)
  Buffers: shared hit=5
  ->  Index Scan Backward using route_stops_route_id_seq_key on route_stops
        (cost=0.29..409.55 rows=30 width=18) (actual time=0.009..0.009 rows=1.00 loops=1)
        Index Cond: (route_id = '39f12796-…'::uuid)
        Filter: (actual_at IS NOT NULL)
        Rows Removed by Filter: 90
        Index Searches: 1
        Buffers: shared hit=5
Planning Time: 0.042 ms
Execution Time: 0.013 ms
```

`V1` 의 `UNIQUE (route_id, seq)` 가 만든 인덱스를 **역순으로** 탄다. 버퍼 5, 실행 0.013 ms.
at-risk 하나당 최악 72 번이어도 **1 ms 아래**다.

## 넣지 않기로 한 것

**부분 인덱스 `(route_id, seq DESC) WHERE actual_at IS NOT NULL` 을 더하지 않는다.**

근거 셋.

1. **이미 한 건 읽고 끝난다.** 위 계획의 비용은 `Rows Removed by Filter: 90` 이 전부이고,
   그 90 은 <em>아직 닿지 않은 꼬리</em>다 — 라우트당 최대 119(기사가 첫 stop 에 막 닿은
   경우)이고 그래도 같은 인덱스 페이지 몇 장이다. 버퍼 5 는 부분 인덱스를 얹어도 줄지 않는다.
2. **쓰기가 잦은 컬럼이다.** `actual_at` 은 stop 방문마다 쓰인다(피크 673 건/초,
   [측정](phase5-delivery-status-throughput.md)). 술어에 그 컬럼을 둔 부분 인덱스는
   **갱신마다 항목이 들고 나므로** 읽기에서 얻지 못한 것을 쓰기에서 잃는다.
3. **술어를 리터럴로 적을 수 있지만 그게 값을 만들지 않는다.** CLAUDE.md 는 부분 인덱스의
   술어를 쿼리에 리터럴로 적으라고 하고 이 질의는 `IS NOT NULL` 이라 그 조건은 이미
   만족한다 — 즉 «쓸 수 있는데 쓸 값이 없는» 경우다.

**다시 볼 조건**: 라우트당 stop 이 설계 상한 120 을 넘어가거나(§6.9 의 `routeStopCap` 이
올라가는 경우), 후보 라우트 수가 계획당 수백 대로 늘어 at-risk 하나의 조회가 수백 번이 되는
경우. 둘 다 지금은 아니다 — `peak` 데이터셋의 실측 최대는 라우트당 90 stop 이고 캠프당
라우트 71 대다.

## 함께 본 것 — 쿨다운

`UPDATE routes SET last_replanned_at = ? WHERE id = ? AND (…)` 는 `routes` PK 한 건이다.
따로 재지 않았다. **근거: 추정** — PK 조회에 인덱스가 붙는지는 이 저장소에서 물을 질문이 아니다.
