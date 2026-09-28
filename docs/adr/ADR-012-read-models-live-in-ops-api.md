# ADR-012 — 읽기 모델은 ops-api 에 모은다 · 예외는 이름 붙인 동기 위임 셋이다

| 항목 | 내용 |
|---|---|
| 상태 | Accepted (2026-09-28 — Phase 6 에 계획했고 쓰지 않았던 ADR 을 구현된 사실에서 쓴다) |
| 결정일 | 2026-09-28 (구현은 Phase 6, 2026-09-22 ~ 09-24) |
| 관련 문서 | `docs/DESIGN.md` §3.3(의존성 규칙) · §5.5(ops-api) · 불변규칙 3 · 4 |
| 관련 ADR | [ADR-051](ADR-051-first-fact-creates-the-row-absence-is-not-a-value.md) (읽기 모델의 행은 먼저 온 사실이 만든다) · [ADR-052](ADR-052-delegation-client-is-generated-from-the-committed-contract.md) (위임 클라이언트) · [ADR-058](ADR-058-shipment-and-read-model-retention.md) (보존) |
| 측정 | `docs/benchmarks/phase6-ops-read-surface.md` · `phase6-rm-orders-aggregate-index.md` · `phase6-kpi-hourly-views-index.md` |

---

## 맥락

운영 콘솔은 주문 · 웨이브 · 라우트 · KPI · 예외를 한 화면에서 본다. 그 사실은 다섯 서비스의 DB 에 흩어져 있고, 서비스 간 DB 접근은 금지다(불변규칙 3).

## 결정

1. **ops-api 가 계약의 모든 토픽(열한 개)을 구독해 자기 DB 에 읽기 모델을 만든다** — `rm_orders` · `rm_waves` · `rm_routes`, KPI 는 뷰 둘
   (`kpi_intake_hourly` · `kpi_delivery_hourly`). 구독 목록은 계약 디렉터리와 대조된다(`ProjectionTopicsTest` — 토픽이 늘면 빨갛다).
   소비는 멱등 게이트를 지나고 거부하지 않는다 — 순서가 뒤집혀 와도 먼저 온 사실이 행을 만든다(ADR-051).
2. **코어 서비스는 조회 API 를 화면을 위해 넓히지 않는다.** 동기 호출은 §3.3 이 허용한 한 방향(ops → 코어)뿐이다.
3. **예외는 이름을 붙여 셋이다** — 읽기 모델에 두면 안 되거나 둘 수 없는 것:
   - `GET /routes/{routeId}` — stop 좌표. 좌표는 개인정보에 가깝고 라우트마다 수백 개라 `rm_routes` 에 두지 않는다(§5.5).
   - `GET /waves/{waveId}/fleet-feasibility` — 계산이다(ADR-067). 읽기 모델에 두면 계산이 두 벌이 된다.
   - `GET /vehicles` — 참조 데이터의 주인이 dispatch 다.
   그리고 **쓰기는 전부 위임**이다(ADR-052 · ADR-055) — ops-api 는 코어의 상태를 바꾸지 않는다.

## 대안

| 안 | 기각 사유 |
|---|---|
| 서비스마다 조회 API, 화면이 조합 | 서비스 간 동기 호출이 늘고 화면 하나가 다섯 서비스의 가용성에 매인다 |
| 읽기 전용 복제본 JOIN | 불변규칙 3 — 스키마가 서비스 경계를 넘는다 |
| `rm_*` 없이 전부 위임 | ADR-051 이 기각했다 — 코어의 가용성이 콘솔의 가용성이 된다 |

## 결과

- 읽기 모델은 궁극적 일관성이다 — 소비 랙만큼 늦다. KPI 뷰의 신선도는 `dawnline_kpi_refresh_age_seconds` 로 보인다(A8, 7-4 판정).
- 보존은 90일, `rm_orders` 는 상한 365일(ADR-058).

## 재검토 지점 (포트폴리오 범위 밖 — 다시 여는 조건)

- **읽기 모델의 재구축이 필요해질 때**(프로젝션 로직 변경 · 손상) — 지금은 토픽 보존 기간 안의 재생만 가능하다. 그 기간을 넘는 재구축은
  코어의 스냅샷 경로가 필요하고, 그때 정한다.
