# ops-api 조회 표면 — 인덱스를 더하지 않는다 (EXPLAIN)

CLAUDE.md 불변규칙 11 에 따른 근거 자료다. 여기서는 **인덱스를 넣지 않고**, 넣지 않은 판단을 행 수와 함께
적는다 — 「검토했는데 안 넣은 것」과 「생각하지 못한 것」을 구별할 수 있게(DESIGN.md §5.5 「조회」).

## 계기

묶음 C 가 캠프 대시보드와 라우트 지도를 위해 ops-api 에 조회 여섯을 붙였다. 그중 읽기 모델을 읽는 넷의 질의다.

| 질의 (`JdbcReadModelViews`) | 부르는 곳 | 기존 인덱스 |
|---|---|---|
| `CAMPS_SQL` — `rm_waves` 를 캠프로 묶는다 | 대시보드의 캠프 고르기 | 없음 |
| `WAVES_SQL` — `camp_id = ? AND cutoff_at` 창 | 대시보드 | 없음 |
| `ROUTES_SQL` — `rm_routes WHERE plan_id = ?` | 지도의 라우트 목록 | 없음 |
| `CANCELLED_BUT_DELIVERED_SQL` — 캠프 + 배송 축 버킷 창 + 상태 리터럴 | 대시보드의 예외 목록 | `ix_rmo_delivery_hour` (V2) |

## 측정 환경

| 항목 | 값 |
|---|---|
| PostgreSQL | 18.2 (공식 이미지, 기본 설정) · `docker run` 한 컨테이너 |
| 호스트 | macOS 27.0 · aarch64 · 14코어 · Docker Desktop |
| 스키마 | `V1__ops` + `V2__ops_kpi` + `V3__ops_wave_depot` 그대로 |
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
| 예외 목록 (캠프 하나, 24 버킷) | `rm_orders` 4,500,000 행 · **3.58 ms** | — | `Bitmap Index Scan on ix_rmo_delivery_hour` · 14,546 행 → 필터 뒤 145 |

예외 목록의 분포는 `KpiViewsIndexIT` 와 같고(캠프 10, 실패 5%, 배차 불가 3%, 개정 2%) 취소됐는데 배송된 주문을
0.1% 더했다. 1년치 `rm_orders`(5,475만 행)는 재지 않았다 — 인덱스 조건이 버킷 창으로 한정되므로 읽는 행은 캠프
하루치(약 1.5만)로 규모와 무관하다.

```
-- 예외 목록 — 버킷 식이 인덱스의 식 그대로라 두 경계가 모두 인덱스 조건이다
Bitmap Index Scan on ix_rmo_delivery_hour (actual time=0.350..0.350 rows=14546.00 loops=1)
  Index Cond: ((camp_id = '…'::uuid)
    AND (date_trunc('hour'::text, COALESCE(delivered_at, failed_at), 'UTC'::text) >= '2031-05-20 11:00:00+00'::timestamp with time zone)
    AND (date_trunc('hour'::text, COALESCE(delivered_at, failed_at), 'UTC'::text) <= '2031-05-21 10:00:00+00'::timestamp with time zone))
Filter: (((order_status)::text = 'CANCELLED'::text) AND ((delivery_outcome)::text = 'COMPLETED'::text))
```

## 판단

- **셋은 인덱스를 넣지 않는다.** 1년치에서도 가장 느린 것이 6.8 ms(계획의 라우트)이고, 부르는 곳은 사람이 화면을
  여는 빈도다. 인덱스는 투영의 쓰기마다 값을 치른다 — `rm_routes` 는 `delivery.status` 마다 갱신된다.
- **예외 목록은 새 인덱스 대신 기존 인덱스를 탄다.** 상태 두 칸의 부분 인덱스도 후보였지만 드물게 채워지는
  술어라 크기는 작아도 **설계서에 없는 인덱스가 하나 는다**. 창을 KPI 와 같게 두면 버킷 식이 이미 있는 인덱스를
  쓰고, 창 밖의 건은 `dawnline_cancel_too_late_total` 알림이 맡는다.
- **강제 수단**: 예외 목록의 계획은 `KpiViewsIndexIT.예외_목록이_배송_축_인덱스의_식으로_내려간다` 가 **운영 코드의
  문장 그대로**(`JdbcReadModelViews.CANCELLED_BUT_DELIVERED_SQL`) 본다. 두 경계가 **둘 다** 인덱스 조건인지를 본다 —
  처음 어설션(「식이 들어 있다」)은 아래 경계의 식만 바꾼 음성 표본에서 초록이었다. 한 경계만 식이 맞아도 인덱스는
  타기 때문이다.

## 재검토 지점

- `rm_routes` 가 **100만 행**을 넘을 때(피크일 규모로 약 2년 — 보존 정책이 없다).
- 지도가 **주기 폴링**을 시작할 때 — 지금은 화면을 열 때와 커맨드 뒤에만 읽는다.
- 예외 목록의 창을 KPI 창보다 넓히는 요구가 생길 때 — 그때 부분 인덱스를 다시 잰다.
