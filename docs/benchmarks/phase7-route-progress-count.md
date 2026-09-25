# 라우트 진행 집계 — `dawnline_routes` 의 질의 (EXPLAIN)

CLAUDE.md 불변규칙 11 에 따른 근거 자료다. 여기서 **인덱스를 더하지 않는다.** 대신 질의의 모양을 정한다
(`JdbcRouteCounts.COUNT_SQL`, DESIGN.md §9.1 `dawnline_routes`).

## 계기

§9.4 Delivery 의 「라우트 진행」에 대응하는 §9.1 행이 없어 `dawnline_routes{camp, status}` 를 더했다
(2026-09-25, 7-1 · [ADR-060](../adr/ADR-060-metrics-come-from-the-table.md) 맥락 6). `rm_routes.status` 는 둘뿐이라
「완료」는 판정이다 — 출발했고 그 라우트에 결과가 없는 주문(취소 제외)이 남지 않았다. 그 판정이 `rm_orders` 를
라우트마다 찾는다. 부르는 곳은 `OnTimeRatioGauges.refreshNow` 하나이고 빈도는 **1분마다**다.

처음 판은 `EXISTS` 였고 비용을 「창이 라우트를 하루치로 묶으니 탐색도 하루치」라고 적었다 — **추정이었다.** 쟀더니
틀렸다.

## 측정 환경

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

## 결과

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

## 판단

- **인덱스를 더하지 않는다.** 91 ms 가 1분에 한 번이다. `rm_orders (route_id) WHERE delivery_outcome IS NULL`
  같은 부분 인덱스는 비트맵 힙 방문(라우트당 약 97 행을 걸러 낸다)을 줄이겠지만 쓰기마다 비용이 들고, 지금 크기에서
  사는 것이 없다. `rm_routes (planned_departure)` 도 더하지 않는다 — 11만 행 순차 스캔이 53 ms 다.
- **재검토 지점**: 창 안 라우트가 피크일의 몇 배가 될 때(캠프 증설), 또는 이 질의가 1분 갱신 주기의 의미 있는
  몫(1초 이상)이 될 때. 그리고 이 질의를 `EXISTS` 로 되돌리는 변경은 이 문서의 표를 다시 잰 뒤에만 한다 —
  작은 픽스처의 IT 에서는 두 형태의 계획이 갈리지 않아서 테스트가 그 회귀를 잡지 못한다.
