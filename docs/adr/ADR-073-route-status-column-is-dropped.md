# ADR-073 — `routes.status` 를 지운다 · 라우트의 끝남은 stop 의 사실에서 나온다

| 항목 | 내용 |
|---|---|
| 상태 | Accepted (2026-09-28) |
| 결정일 | 2026-09-28 |
| 관련 문서 | `docs/DESIGN.md` §5.3 DDL · `contracts/openapi/dispatch-service.yaml` · `contracts/openapi/ops-api.yaml` · `docs/IMPLEMENTATION_PLAN.md` 7-0 A31 · `docs/benchmarks/phase7-window-scenarios.md` §4 항목 3 |
| 관련 ADR | [ADR-061](ADR-061-unfinished-work-has-no-window.md) (`rm_routes.completed_at` — 사건 시점에 다시 세는 칸이 허용된 이유) · [ADR-068](ADR-068-replan-write-locks-and-moves-rows.md) 후속 A (「끝나지 않았다」는 한 조각) · [ADR-071](ADR-071-delivery-facts-live-on-the-order-row.md) (stop 의 상태는 주문들에서) |

---

## 맥락

`routes.status`(V1, `PLANNED | DISPATCHED | COMPLETED`)는 계획이 `'PLANNED'` 리터럴로 INSERT 한 뒤 **바꾸는 문장이 없다**(`UPDATE routes` 여섯 중 0 —
코드 읽기). 그런데 `GET /api/v1/routes/{routeId}` 가 그 칸을 `RouteView.status` 로 내보내고 ops-api 가 `RouteDetail.status` 로 통과시킨다. 7-4 의 네 실행에서
라우트 775대가 전부 `PLANNED` 였다 — 기사가 끝까지 돈 687대를 포함해(7-0 A31 켜짐, 근거: 관측). 쓰는 쪽이 없는 칸은 읽는 쪽이 믿는 순간 거짓이 된다.

「끝났는가」를 묻는 읽는 쪽은 이미 셋 있고 셋 다 그 칸을 보지 않는다 — 보존(계획 단위 종결), 차량 비활성화의 409, 재계획 · 재배정의 받는 쪽 잠금.
모두 `route_stops` 의 한 조각(`JdbcDispatchRetention.UNFINISHED_STOP`)으로 판정한다(ADR-068 후속 A).

## 결정

1. **칸을 지운다** — V14 `ALTER TABLE routes DROP COLUMN status`. `RouteView.status`(dispatch OpenAPI) · `RouteDetail.status`(ops-api OpenAPI)도 뺀다.
2. **라우트의 끝남이 필요한 읽기는 조각으로 파생한다** — `UNFINISHED_STOP` 하나. 지금 그 칸을 읽는 화면은 없다: ops-web 의 라우트 상태는 ops-api 의
   읽기 모델(`RouteSummary.status` — `ASSIGNED | DEPARTED`, 이벤트로 투영)이고, 라우트 상세 패널은 stop 의 상태만 쓴다.
3. **사건 시점에 쓰지 않는다.** 쓰면 같은 사실(끝나지 않은 stop 이 있는가)의 둘째 출처가 된다. `rm_routes.completed_at` 이 허용된 이유는 **읽을 때 다시
   세는 것이 비쌌기** 때문이다(ADR-061 — ops 의 목록이 라우트마다 집계를 하게 된다). 여기서는 읽는 쪽 셋이 이미 조각으로 판정하고 그 조각은 싸다
   (라우트의 stop 몇십 줄, `UNIQUE (route_id, seq)`).

## 근거

- **둘째 출처를 만들지 않는다** — 이 Phase 가 계속 지워 온 부류다(ADR-070 의 `eta_at`, ADR-071 의 stop 칸).
- **계약에서 칸을 빼는 것은 이 경우에 안전하다.** 두 OpenAPI 의 소비자는 저장소 안에 있다(ops-api 의 생성 클라이언트, ops-web 의 생성 타입) — 빌드가
  남은 참조를 컴파일 오류로 잡는다(ADR-052 · ADR-056). 그 칸을 분기에 쓰는 코드는 없었다.

## 고려한 대안과 기각 이유

| 대안 | 기각 이유 |
|---|---|
| 사건 시점에 쓴다(`rm_routes.completed_at` 처럼) | 같은 사실의 둘째 출처 — 읽는 쪽 셋이 이미 싼 조각으로 판정한다. ADR-061 의 허용 조건(읽기 재계산이 비싸다)이 여기엔 없다 |
| 칸은 두고 API 에서만 뺀다 | 쓰는 쪽 없는 칸이 스키마에 남는다 — 다음 사람이 읽을 자리다 |
| API 에 파생 값으로 남긴다(조각으로 계산해 `status`) | 읽는 화면이 없다 — 필요해지면 그때 파생으로 더한다 |

## 결과

- **장점**: 거짓을 말하는 칸이 사라진다. 끝남의 정의는 한 조각이다.
- **비용**: 두 OpenAPI 에서 칸이 빠진다(저장소 밖의 소비자는 없다). V14 는 되돌릴 수 없는 삭제지만 칸의 값은 언제나 `PLANNED` 였다 — 잃는 정보가 없다.
- **되돌리는 방법**: 새 V 로 칸을 더하고 조각에서 파생해 채운다.

## 검증

| 표본 | 기대 | 결과 |
|---|---|---|
| `./gradlew build` — 계약 대조(OpenAPI 생성물 ↔ 커밋된 yaml) · ops-api 생성 클라이언트 컴파일 | 초록 | ✅ — 두 yaml 은 `updateOpenApi` 로 다시 만들었다(dispatch `RouteView` −2줄, ops-api `RouteDetail` −3줄 · `required` 에서도 빠졌다) |
| `DispatchApiTest` — 라우트 상세 | `$.status` 가 없다 | ✅ |
| dispatch · ops-api 통합 테스트 | 초록 | ✅ — dispatch 는 픽스처 둘(`DispatchRetentionIT` · `RouteStopOrdersIndexIT`)이 `routes.status` 를 직접 넣고 있었다: 빼고 초록. ops-api 83 |
| ops-web `npm test`(타입 검사 · 컴포넌트) | 초록 — 픽스처에서 칸을 뺀다 | ✅ 14 — 화면은 그 칸을 읽지 않았다 |
