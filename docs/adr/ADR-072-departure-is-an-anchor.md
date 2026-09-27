# ADR-072 — 출발도 앵커다 · dispatch 가 `delivery.route-departed` 를 소비한다

| 항목 | 내용 |
|---|---|
| 상태 | Accepted (2026-09-28) |
| 결정일 | 2026-09-28 |
| 관련 문서 | `docs/DESIGN.md` §4.1 토픽 표 · §6.8 「편차」 · `contracts/events/delivery.route-departed.v1.schema.json` · `docs/IMPLEMENTATION_PLAN.md` 7-0 B1 · `docs/benchmarks/phase7-window-scenarios.md` §4 항목 4 |
| 관련 ADR | [ADR-048](ADR-048-replan-reads-its-own-db.md) 결정 1 (재계획은 자기 DB 로 푼다 — 편차 = 마지막으로 닿은 stop · 닿은 stop 이 없으면 «모름») · [ADR-050](ADR-050-route-departure-is-an-event.md) (출발은 이벤트다 — 소비자가 ops 뿐이었다) · [ADR-047](ADR-047-delivery-status-is-a-fact-not-a-revision.md) (사실은 개정으로 거르지 않는다) |

---

## 맥락

7-4 의 `peak-day` 3차와 `overload-day` 에서 at-risk 재계획의 `no-anchor` 가 29/32 · 24/28 이었다(7-0 B1 켜짐). 그 조건은 구조적이다:

- at-risk 는 설계상 **출발 지연**에서 첫 stop 전에 발화한다(§5.4 — `DEPARTED_CAMP` 가 첫 편차의 출처이고, 그 갈래가 없으면 위험 감지가 첫 배송까지 늦는다).
- 그 순간 dispatch 에는 닿은 stop 이 없다 — 편차는 `route_stops.actual_at` 에서만 나오고(ADR-048 결정 1), 출발은 dispatch 에 오지 않는다.
  출발을 아는 것은 tracking 뿐이고 그 사실은 `delivery.route-departed` 로 ops 에만 갔다(ADR-050 — 「소비자 ops 뿐」).
- 그래서 가장 흔한 지연 원인에 대한 재계획이 가장 자주 「모름」이다. ADR-048 은 그 구멍이 「첫 `ARRIVED` 뒤 stop 하나 뒤에 닫힌다」고 적었지만,
  그 stop 하나가 출발 지연 라우트에서는 이미 늦은 구간이다.

「소속도 시각도 자기 DB 에서」(ADR-048)의 마지막 빈칸이 출발이다.

## 결정

1. **dispatch 가 `delivery.route-departed` 를 소비한다.** 멱등 소비자(불변규칙 2), 소비자 이름 `dispatch-service`. 계약은 그대로다 — 이미 required
   다섯 칸(`routeId` · `campId` · `revision` · `plannedDeparture` · `departedAt`)을 싣는다. 계약 설명의 소비자 목록만 고친다.
2. **출발 사실을 자기 DB 에 둔다 — `routes.departed_at`(V13).** 처음 온 값만 남는다(`COALESCE` — `route_stops.actual_at` 과 같은 규칙, ADR-048 결정 1).
   개정 번호로 거르지 않는다 — 출발은 사실이다(ADR-047 결정 3). 그 라우트가 dispatch 에 없으면(보존이 지웠다) 무시하고
   `dawnline_event_stale_total{consumer="dispatch", eventType="delivery.route-departed"}` 로 센다.
3. **앵커는 닿은 stop 이 먼저, 없으면 출발이다.** 편차 = 마지막으로 닿은 stop 의 `actual_at − planned_arrival`; 닿은 stop 이 없으면
   `routes.departed_at − routes.planned_departure`(V7 — dispatch 가 계획한 출발). 둘 다 없을 때만 `no-anchor` 다.
   페이로드의 `plannedDeparture` · at-risk 의 `deviationSeconds` 는 **대조값**으로 남는다(ADR-048 — 진실이 «소속은 dispatch · 시각은 tracking» 으로 갈리지 않게).
4. **남는 창은 B1 의 수가 답한다.** at-risk 와 출발은 **다른 토픽**이라 at-risk 가 먼저 소비되면 여전히 `no-anchor` 다. 0 이 아니면 그 수가 두
   토픽 사이 순서 창의 크기다 — 7-4 turbulent 실행이 잰다(출발 지연이 있는 실행이다).

## 근거

- **출발은 편차의 첫 출처다.** §5.4 가 그렇게 설계했고, 그 사실을 재계획이 모르면 설계한 위험 감지가 재계획으로 이어지지 않는다.
- **계약은 이미 있다.** 새 토픽도 새 칸도 없다 — 소비자를 하나 더할 뿐이다(ADR-050 결정 4 가 「소비자가 먼저 정의한다」로 만든 계약).
- **자기 DB 의 칸이다 — 페이로드가 아니다.** at-risk 의 `deviationSeconds` 를 앵커로 쓰면 편차의 출처가 tracking 이 된다(ADR-048 기각 (8)).
  출발 시각은 tracking 의 관측이지만 계획 출발과의 차는 dispatch 가 자기 계획으로 잰다 — 출발 전 재계획이 계획 출발을 바꿨으면 그 차이가
  드러난다(대조 카운터).

## 고려한 대안과 기각 이유

| 대안 | 기각 이유 |
|---|---|
| at-risk 페이로드의 `deviationSeconds` 로 앵커를 채운다 | 편차의 출처가 tracking 이 된다 — ADR-048 기각 (8) 그대로 |
| 도구 부하를 낮춘 실행으로 먼저 가른다(리포트 §4 의 둘째 안) | 29/32 는 구조다 — 출발 지연에서 첫 stop 전에 발화하는 것이 설계다. 부하와 무관하다 |
| at-risk 에 출발 시각을 싣는다(계약 변경) | 같은 사실을 두 토픽에 싣는다 — 출발은 이미 자기 이벤트가 있다 |
| 출발을 `route_stops` 의 가짜 0번 stop 으로 적는다 | stop 이 아닌 것을 stop 표에 둔다 — `UNFINISHED_STOP` · 보존 · 발행의 조각이 모두 그것을 피해야 한다 |

## 결과

- **장점**: 출발 지연 라우트의 at-risk 가 첫 stop 전에도 재계획된다. `no-anchor` 는 두 토픽의 순서 창만 남는다.
- **비용**: dispatch 소비가 하나 는다(라우트당 한 건). `routes` 행 갱신이 하나 는다 — 라우트 행 잠금은 재계획 · 재배정의 쓰기와 짧게 겹칠 수 있다
  (이 쓰기는 라우트 행 하나만 잡아 교착의 고리에 들지 않는다, ADR-068 결정 2).
- **되돌리는 방법**: 리스너를 지우고 앵커의 둘째 갈래를 지운다. `routes.departed_at` 은 남아도 읽는 쪽이 없다.

## 검증

| 표본 | 기대 | 결과 |
|---|---|---|
| `RouteDepartedIT` — 계약 봉투로 발행 | `routes.departed_at` 이 적힌다, 다른 사건의 늦은 출발은 덮지 않는다 | ✅ |
| 같은 IT — 없는 라우트의 출발 | 처리되고(`processed_events`) `dawnline_event_stale_total{eventType="delivery.route-departed"}` +1 | ✅ |
| `ReplanRouteServiceTest` — 닿은 stop 없음 + 출발(늦음) | `APPLIED`, 대조 카운터 0 (앵커 = 출발의 편차 = 페이로드) | ✅ |
| 같은 테스트 — 닿은 stop 있음 + 정시 출발 | 앵커 = 닿은 stop — 대조 카운터 0 (출발이었다면 갈렸다) | ✅ |
| 같은 테스트 — 둘 다 없음 | `NO_ANCHOR` (기존 테스트 그대로) | ✅ |
| `RouteDepartedPayloadTest` — 계약 예시 · 토픽 · 소비자 이름 · 메트릭 태그 | 두 칸을 읽는다 | ✅ |
| 음성 표본 N1 — 앵커의 출발 갈래를 뺀다 | 「출발이 있으면」 테스트 빨강 | ✅ 그것 하나 (`NO_ANCHOR`) |
| 음성 표본 N2 — `COALESCE` 를 뺀다 | 「두 번째 출발은 덮지 않는다」 빨강 | ✅ 그것 하나 (00:17 이 00:12 를 덮었다) |

두 음성 표본 모두 복원 뒤 `cmp` 일치. dispatch 단위 · 통합(138) 초록.
