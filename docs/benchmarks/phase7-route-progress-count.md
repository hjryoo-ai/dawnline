# 라우트 진행 집계 — `dawnline_routes` 의 질의 (EXPLAIN)

> **2026-09-25 둘째 판이 첫 판을 대체했다** — 아래 「둘째 판」. 첫 판(7-1, #68)은 읽기가 라우트마다 `rm_orders` 를 찾았고
> 그 탐색을 묶는 창이 창 밖의 진행 중 라우트를 뺐다. 지금은 완료를 쓰기 때 적고(`rm_routes.completed_at`,
> [ADR-061](../adr/ADR-061-unfinished-work-has-no-window.md)) 읽기는 `rm_routes` 만 읽는다. 첫 판의 기록은 그대로 둔다 —
> 「왜 `EXISTS` 가 아니었나」와 「왜 읽기에서 창을 걷지 않았나」의 근거다. **첫 판의 숫자 하나는 잘못 읽었다**: 아래
> 「`rm_routes` 병렬 순차 스캔(약 53 ms)」은 스캔이 아니라 **JIT 컴파일**이었다(둘째 판 표의 넷째 줄).
> 둘째 판은 같은 날 리뷰에서 한 번 더 바뀌었다 — 할 일이 없는 라우트는 완료가 아니라 다섯째 값 `void` 이고
> (`rm_routes.live_count = 0`), `assigned` 도 창이 없다. 맨 아래 「둘째 판의 개정」이 지금의 문장과 숫자다.

## 첫 판 — 읽을 때 판정 (7-1, #68)

CLAUDE.md 불변규칙 11 에 따른 근거 자료다. 여기서 **인덱스를 더하지 않는다.** 대신 질의의 모양을 정한다
(`JdbcRouteCounts.COUNT_SQL`, DESIGN.md §9.1 `dawnline_routes`).

### 계기

§9.4 Delivery 의 「라우트 진행」에 대응하는 §9.1 행이 없어 `dawnline_routes{camp, status}` 를 더했다
(2026-09-25, 7-1 · [ADR-060](../adr/ADR-060-metrics-come-from-the-table.md) 맥락 6). `rm_routes.status` 는 둘뿐이라
「완료」는 판정이다 — 출발했고 그 라우트에 결과가 없는 주문(취소 제외)이 남지 않았다. 그 판정이 `rm_orders` 를
라우트마다 찾는다. 부르는 곳은 `KpiGauges.refreshNow`(당시 `OnTimeRatioGauges`) 하나이고 빈도는 **1분마다**다.

처음 판은 `EXISTS` 였고 비용을 「창이 라우트를 하루치로 묶으니 탐색도 하루치」라고 적었다 — **추정이었다.** 쟀더니
틀렸다.

### 측정 환경

| 항목 | 값 |
|---|---|
| PostgreSQL | 18.2 (공식 이미지, 기본 설정) · `docker run` 한 컨테이너 |
| 호스트 | macOS 27.0 · aarch64 · 14코어 · Docker Desktop 29.1.3 |
| 스키마 | ops-api `V1` – `V7` 그대로 |
| 준비 | 새로 적재한 뒤 `ANALYZE` — `reltuples` 가 `rm_orders` 13,612,352 · `rm_routes` 112,520 |
| 계측 | `force_generic_plan` 으로 `EXPLAIN (ANALYZE, BUFFERS)` 세 번 — 첫 번째(차가운 캐시)와 셋째를 적는다 |

**행 수는 운영 규모다.** 보존 90일(ADR-058) × 피크일 라우트 1,250 = 112,500 라우트, 라우트당 주문 121
(§5.5 의 「90일치 1,365만 행」). 캠프 10. 계획 출발은 90일에 고르게, 마지막 두 시간은 출발 전, 마지막 열 시간은
주문의 절반이 아직 결과 없음, 실패 2%. 계획이 오지 않은 라우트 20. `rm_orders` 힙 1,636 MB, `ix_rmo_route` 108 MB.

### 결과

창(계획 출발 ≥ 첫 버킷, 23시간 전 정시)에 든 라우트는 1,258, 결과 그룹은 31.

| 형태 | 계획 | 처음 | 캐시가 찬 뒤 | 버퍼 |
|---|---|---|---|---|
| `CASE … WHEN EXISTS (SELECT 1 FROM rm_orders o WHERE o.route_id = r.route_id AND …)` | **해시 서브플랜** — `rm_orders` 병렬 순차 스캔(3만 1,781 행을 남기고 1,358만 행을 거른다) | 1,513 ms | 221 ms | 읽기 19만 4,749 |
| `LEFT JOIN LATERAL (SELECT … FROM rm_orders o WHERE o.route_id = r.route_id AND r.status = 'DEPARTED' AND … LIMIT 1)` | 라우트마다 `ix_rmo_route` 비트맵 스캔(1,153 회), 첫 행에서 멈춘다 | 154 ms | **91 ms** | 적중 11만 7,435 · 읽기 0 |

두 형태의 결과는 같다 — 두 결과의 `EXCEPT` 가 양쪽 다 0 행이다.

**`EXISTS` 가 창을 무력하게 만든 이유.** 플래너는 상관 서브쿼리를 라우트마다 도는 대신 「결과 없는 주문의
`route_id` 집합」을 한 번 만들어 해시로 대조했다. 그 집합을 만드는 데 `rm_orders` 전체를 읽는다 — 창은 바깥의
`rm_routes` 만 거르고 안쪽의 크기는 보존 기간이 정한다. 매분 1.5 GB 를 훑는 질의가 공유 버퍼를 밀어낸다.

**`LATERAL … LIMIT 1` 이 막는 것.** 바깥 행을 참조하는 `LATERAL` 은 해시로 바꿀 수 없고, `LIMIT 1` 이 첫 행에서
멈추게 한다. `r.status = 'DEPARTED'` 를 안쪽에 두면 출발 전 라우트는 한 번의 필터(`One-Time Filter`)로 끝난다.
남은 비용의 절반은 `rm_routes` 병렬 순차 스캔(약 53 ms, 11만 행 중 1,258 을 남긴다)이다.

### 판단

- **인덱스를 더하지 않는다.** 91 ms 가 1분에 한 번이다. `rm_orders (route_id) WHERE delivery_outcome IS NULL`
  같은 부분 인덱스는 비트맵 힙 방문(라우트당 약 97 행을 걸러 낸다)을 줄이겠지만 쓰기마다 비용이 들고, 지금 크기에서
  사는 것이 없다. `rm_routes (planned_departure)` 도 더하지 않는다 — 11만 행 순차 스캔이 53 ms 다.
- **재검토 지점**: 창 안 라우트가 피크일의 몇 배가 될 때(캠프 증설), 또는 이 질의가 1분 갱신 주기의 의미 있는
  몫(1초 이상)이 될 때. 그리고 이 질의를 `EXISTS` 로 되돌리는 변경은 이 문서의 표를 다시 잰 뒤에만 한다 —
  작은 픽스처의 IT 에서는 두 형태의 계획이 갈리지 않아서 테스트가 그 회귀를 잡지 못한다.

## 둘째 판 — 쓸 때 판정 (2026-09-25, ADR-061)

### 계기

첫 판의 범위는 `unknown` 을 뺀 전부를 KPI 창(계획 출발 ≥ 첫 버킷)으로 잘랐다. 출발한 지 30시간 된 라우트가 아직 끝나지
않았다면 그것이 가장 먼저 볼 라우트인데 창 밖이라 `in_progress` 에서 빠진다. 끝나지 않은 일에는 창이 없다. 그러나 읽기에서 창을
걷으면 탐색이 보존 기간 전체로 커진다 — 그래서 완료를 **쓰기 때** 판정해 칸에 적고, 읽기는 그 칸만 본다.

### 측정 환경

첫 판과 같다(PostgreSQL 18.2 공식 이미지 기본 설정 · `docker run` 한 컨테이너 · macOS 27.0 aarch64 14코어 · Docker Desktop
29.1.3). 스키마는 ops-api `V1`–`V7` 에 후보 `V8`. 적재 뒤 `ANALYZE` — `reltuples` 가 `rm_orders` 13,612,792 · `rm_routes`
112,520. 질의는 `PREPARE` 후 `force_generic_plan` 으로 `EXPLAIN (ANALYZE, BUFFERS)` 세 번.

**분포.** 기준 시각 2026-09-25 12:00Z. 90일 × 피크일 1,250 라우트, 라우트당 주문 121, 캠프 10. 마지막 두 시간은 출발 전,
마지막 열 시간은 주문의 절반이 결과 없음, 실패 2%. **창 밖의 끝나지 않은 라우트 55**(이틀 이전의 약 2,000 번째마다 결과 없는
주문 하나), 계획이 오지 않은 라우트 20. 참값(라우트마다 `EXISTS` 로 판정):

| 진행 | 창 안 | 창 밖 |
|---|---|---|
| `assigned` | 104 | 0 |
| `in_progress` | 416 | **55** |
| `completed` | 677 | 111,248 |
| `unknown` | 20 (창 없음) | |

### 결과

| 질의 | 결과의 `in_progress` | 처음 | 캐시가 찬 뒤 | 비고 |
|---|---|---|---|---|
| 첫 판 그대로(창 있음) | **416** — 창 밖 55 를 뺀다 | 108 ms | 69 ms | 버퍼 적중 8,055 |
| 첫 판에서 창만 뺀다 | 471 | 933 ms | 891 ms | 라우트 11만 × `ix_rmo_route` — 버퍼 적중 43.6만 · **읽기 22.2만(약 1.7 GB)** |
| **둘째 판** — `rm_routes` 한 번(아래 문장) | **471** | 8.2 ms | **5.2 ms** | 순차 스캔 4,894 페이지. 비용 추정 6,960 — JIT 없음 |
| 첫 판 그대로, `jit = off` | 416 | | 12.8 ms | 켠 쪽 98.8 ms. **JIT 184 ms**(인라인 96 · 최적화 49 · 방출 39, 병렬 작업자 합) — 비용 추정 85만이 `jit_above_cost` 10만을 넘는다 |

둘째 판의 결과(진행별 합: `assigned` 104 · `in_progress` 471 · `completed` 677 · `unknown` 20)는 참값과 같다.

```sql
SELECT camp_id,
       CASE WHEN revision IS NULL OR status IS NULL THEN 'UNKNOWN'
            WHEN status = 'ASSIGNED' THEN 'ASSIGNED'
            WHEN completed_at IS NULL THEN 'IN_PROGRESS'
            ELSE 'COMPLETED' END AS progress,
       count(*) AS routes
  FROM rm_routes
 WHERE (completed_at IS NULL AND status IS DISTINCT FROM 'ASSIGNED')   -- 끝나지 않은 것: 창 없음
    OR planned_departure >= ?                                          -- 끝난 것 · 출발 전: 창
 GROUP BY 1, 2
```

앞의 술어에 드는 행은 창과 무관하게 들고, 나머지는 창이 거른다. `unknown` 행은 `completed_at` 이 언제나 `NULL` 이고
(`revision` 이 없으면 다시 세지 않는다) `status` 가 `NULL` 이거나 `DEPARTED` 라서 앞의 술어에 든다.

**쓰기의 대가.** 재집계(`JdbcRouteRows.RECOUNT_SQL`)는 이미 결과 사실마다 한 번 돈다. 두 부분 질의(완료 수 · 실패 수)가
세 값을 내는 한 부분 질의가 됐다. 라우트 하나(주문 121)에서 두 번씩 잰 값:

| 형태 | 계획 | 실행 |
|---|---|---|
| 이전 — 칸 둘 | 부분 계획 둘 × `ix_rmo_route` | 0.07–0.15 ms |
| 지금 — 칸 셋 | 부분 계획 하나 × `ix_rmo_route`(121 행) | 0.04–0.19 ms |

같은 범위다. **백필**(V8 의 `UPDATE`, 계획이 도착한 11만 2,500 라우트 전부)은 1.3 초다.

### 판단

- **인덱스를 더하지 않는다.** 끝나지 않은 라우트의 부분 인덱스
  `rm_routes (camp_id) WHERE completed_at IS NULL AND status IS DISTINCT FROM 'ASSIGNED'`(16 kB, 491 행)를 만들어 쟀다.
  - 위 문장(`OR`)은 그 인덱스를 쓰지 않는다 — 7.8 ms(첫 실행) · 5.2 ms.
  - 두 분기를 `UNION ALL` 로 가르면 앞 분기가 그 인덱스를 쓴다(비트맵, 0.07 ms). 그러나 창 쪽 분기가 순차 스캔이라 전체가
    **4.9 ms** 다.
  - 인덱스가 값을 하려면 `planned_departure` 인덱스까지 둘이 필요하고, 사는 것은 1분에 5 ms 다. 문장은 한 모양으로 둔다.
- **순차 스캔의 크기에는 상한이 있다.** 보존 90일(ADR-058)이 `rm_routes` 를 약 11만 행에서 멈춘다. 끝나지 않은 라우트는
  보존이 지우지 않으므로 그만큼 더 남는다 — 드물어야 하는 행이고, 많아지면 그 자체가 볼 일이다.
- **계획 모양의 회귀가 없어졌다.** 첫 판의 「`EXISTS` 로 되돌리면 해시 서브플랜이 `rm_orders` 전체를 읽는다 — 작은 픽스처로는
  못 잡는다」는 질의가 `rm_orders` 를 읽지 않으므로 성립하지 않는다. IT(`KpiViewsIT`)가 **계획에 `rm_orders` 가 없다**를 본다 —
  계획 선택이 아니라 문장의 구조라서 작은 픽스처에서도 참 · 거짓이 갈린다.
- **재검토 지점**: 순차 스캔이 1분 갱신의 의미 있는 몫이 될 때 — 대략 100 ms, `rm_routes` 약 200만 행(보존 기간이 늘거나 캠프가
  크게 늘 때). 그때 위의 두 인덱스를 다시 잰다.

### 둘째 판의 개정 — `void` (같은 날, 리뷰)

**바뀐 것.** 위 문장은 빈 라우트 · 전부 취소된 라우트의 `completed_at` 에 계획 출발을 적어 `completed` 로 셌다. 일어나지 않은
완료에 시각을 만드는 것이라 기각됐다(ADR-061 대안 F). 재집계가 비취소 주문 수 `live_count` 를 함께 적고, 0 이면 `void` 다 —
`completed_at` 은 `NULL` 로 남는다. `assigned` 의 창은 재계획이 비운 라우트를 치우려던 것이라 함께 걷었다.

```sql
SELECT camp_id,
       CASE WHEN revision IS NULL OR status IS NULL THEN 'UNKNOWN'
            WHEN live_count = 0 THEN 'VOID'
            WHEN status = 'ASSIGNED' THEN 'ASSIGNED'
            WHEN completed_at IS NULL THEN 'IN_PROGRESS'
            ELSE 'COMPLETED' END AS progress,
       count(*) AS routes
  FROM rm_routes
 WHERE (completed_at IS NULL AND live_count IS DISTINCT FROM 0)   -- 끝나지 않은 것(출발 전 포함): 창 없음
    OR planned_departure >= ?                                     -- 완료 · void: 창
 GROUP BY 1, 2
```

**측정 환경.** 위와 같은 컨테이너 구성 · 같은 적재에 둘을 더했다: void 라우트 40(창 안 20 · 창 밖 20 — 반은 빈 `ASSIGNED`,
반은 주문 셋이 전부 취소된 `DEPARTED`)과 창 밖(3일 전)의 떠나지 않은 라우트 7(결과 없는 주문 둘). 적재 뒤 V8(칸 둘 · 백필),
`ANALYZE` — `reltuples` 가 `rm_orders` 13,612,522 · `rm_routes` 112,567. 참값(라우트마다 `EXISTS` 로 판정):

| 진행 | 창 안 | 창 밖 |
|---|---|---|
| `assigned` | 104 | **7** |
| `in_progress` | 416 | **55** |
| `completed` | 677 | 111,248 |
| `void` | 20 | 20 |
| `unknown` | 0 | 20 (창 없음) |

**결과.**

| 항목 | 값 |
|---|---|
| 질의의 결과(진행별 합) | `assigned` 111 · `in_progress` 471 · `completed` 677 · `void` 20 · `unknown` 20 — **참값과 같다**(창 없는 셋은 창 밖까지, 창 있는 둘은 창 안만) |
| 질의(`force_generic_plan`, 9회) | 순차 스캔 5,011 페이지(39 MB), 비용 추정 7,174 — JIT 없음. **7.1–10.7 ms**. 표가 `shared_buffers` 의 1/4 를 넘어 순차 스캔이 링 버퍼로 읽으므로 캐시가 찬 뒤에도 페이지의 대부분이 `read`(OS 캐시)다 — 위의 5.2 ms 와의 차이는 백필이 모든 행을 한 번 고쳐 쓴 페이지와 측정 간 편차다 |
| 재집계(라우트 하나, 주문 121, 4회) | 칸 넷을 한 부분 질의로 — **0.08–0.21 ms**(첫 실행이 0.21). 위의 칸 셋 0.04–0.19 ms 와 같은 범위 |
| 백필(V8, 계획이 도착한 112,547 라우트) | 적재 직후 첫 실행 **6.7 초** · 캐시가 찬 뒤(롤백한 재실행) **1.4 초** |

**판단은 그대로다** — 인덱스를 더하지 않는다. 문장은 여전히 `rm_routes` 순차 스캔 한 번이고, 술어 하나가 칸 하나를 더
볼 뿐이다. 재검토 지점도 그대로다(대략 100 ms, `rm_routes` 약 200만 행).
