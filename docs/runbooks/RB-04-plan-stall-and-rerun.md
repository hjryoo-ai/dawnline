# RB-04 — 계획 정체 · 강제 재실행

| 항목 | 내용 |
|---|---|
| 대상 | dispatch 의 웨이브 계획 — `wave.closed` 가 시작하고 `plan.completed` · `plan.failed` 로 끝난다 |
| 알림 | `DawnlinePlanDurationP95` ([README](README.md) 1). 알림 밖에서 오는 곳 둘: `dawnline_fulfillment_orders_stuck` · `dawnline_route_plans_stuck` 이 0 이 아니다(§9.1 — 볼 곳이 여기다) |
| 관련 설계 | §5.3(계획 상태 머신 · 계획 하나는 트랜잭션 하나) · §6.7(시간 예산 · 열화 사다리) · §8.4(「dispatch 계획 중 크래시」 행) · ADR-024 · ADR-034 · ADR-036 |

**먼저 본다 — SQL** 계획을 기다리는 웨이브와 실패한 계획이 있는가 — 느린 것과 멈춘 것은 대응이 다르다. 계획 하나는 트랜잭션 하나라(§5.3)
도는 중인 계획은 `route_plans` 에 **행이 없다** — 기다리는 것은 웨이브 쪽에서 본다:

```bash
sql fulfillment "SELECT id, camp_id, service_tier, closed_at FROM waves
                  WHERE status = 'CLOSED' AND closed_at < now() - interval '5 minutes' ORDER BY closed_at LIMIT 20"
sql dispatch "SELECT id, wave_id, status, strategy, mode, mode_reason, started_at, finished_at, failure_reason
                FROM route_plans
               WHERE status = 'FAILED' AND finished_at > now() - interval '1 day'
               ORDER BY finished_at DESC LIMIT 20"
```

있으면 1(멈췄다 · 실패했다), 없고 알림만 울리면 2(느리다). `FAILED` 를 하루로 자르는 이유: 재실행하지 않은 옛 실패가 남는다 — 로컬 볼륨에는
후보 없이 끝난(`NO_CANDIDATES`) 9월 초의 계획이 수십 개 있어, 자르지 않으면 지금의 실패가 그 아래에 묻힌다.

명령의 `dc` · `prom` · `logs` · `sql` 은 [README](README.md) 의 공통 준비다.

---

## 1. 멈췄다 · 실패했다

| 상태 | 뜻 | 대응 |
|---|---|---|
| **행이 없다** — 웨이브는 `CLOSED` | 도는 중이거나, `wave.closed` 를 아직 소비하지 않았다. **계획 하나는 트랜잭션 하나라**(§5.3, ADR-024 후속 정정) 도는 동안의 `REQUESTED` · `PLANNING` 은 다른 세션에 보이지 않는다 | 예산은 30초다(§6.7). 몇 분이 지나도 없으면 소비를 본다 — 그룹 랙(RB-01 §2.1)과 재시도 나이(`dawnline_event_retry_age_seconds{service="dispatch-service"}`, RB-02 §3). 도는 중인지는 `sql dispatch "SELECT now() - xact_start, left(query, 60) FROM pg_stat_activity WHERE usename = 'dawnline_dispatch' AND state <> 'idle'"` |
| dispatch 가 계획 중에 죽었다 | 트랜잭션이 롤백됐다 — **남은 행이 없다** | **저절로 된다** — 재기동하면 커밋되지 않은 `wave.closed` 가 다시 전달돼 처음부터 계획한다(로그 「웨이브 계획: waveId=… 결과=PUBLISHED」). dispatch 가 떠 있는지부터 본다. 관측: 결과 쓰기에서 죽여도 행 0, 재기동 26초 뒤 `PUBLISHED`(`make chaos-kill`) |
| `FAILED` | 계획이 실패로 끝났다 — `failure_reason` 을 본다 | 아래 1.1 |
| `REQUESTED` · `PLANNED` 가 오래 남음 | 결과 쓰기와 발행 사이에서 멈췄다 — 한 트랜잭션이라 정상에서는 보이지 않는다 | dispatch 로그의 예외. DB 장애 뒤라면 [RB-02](RB-02-database-outage.md) |

### 1.1 `FAILED` — 사유별

| `failure_reason` | 뜻 | 재실행하면 |
|---|---|---|
| `NO_CANDIDATES` | 계획할 후보가 하나도 없다 — 웨이브의 주문이 전부 취소됐거나 후보가 적재되지 않았다 | 후보가 생기지 않으면 같은 결과다. 후보를 먼저 본다: `sql dispatch "SELECT status, count(*) FROM dispatch_candidates WHERE wave_id = '<waveId>' GROUP BY 1"`. 0 이면 `fulfillment.planned` 의 소비를 본다(RB-05 §2) |
| `RULE_VIOLATION` | **데이터가 아니라 코드 문제다** — 최종 산출물이 하드 룰을 어겼다(로그 「계획이 하드 룰을 어겼습니다」의 첫 위반) | 같은 결과가 날 가능성이 높다. 전략이나 모드를 바꿔 재실행하는 것은 우회이고 원인은 남는다 — 결함으로 올린다 |
| 그 밖(예외 · 가용 차량 없음) | dispatch 로그의 예외 | 원인을 고친 뒤 1.2 |

### 1.2 재실행 — ops-api 를 거친다

```bash
TOKEN=$(make -s token ROLE=OPS_OPERATOR ACTOR=<내 이름>)
curl -s -X POST -H "Authorization: Bearer $TOKEN" \
  "http://localhost:$OPS_API_PORT/api/v1/plans/<waveId>/run?mode=FULL" | jq
```

- **`FAILED` 만 다시 돈다.** `PUBLISHED` 는 `ALREADY_PUBLISHED` 로 아무것도 하지 않는다(멱등, §5.3). 처음 도는 중인 계획은 아직
  행이 없어 404 다(§5.3 — 한 트랜잭션) — 도는 계획을 둘로 만들지 않는다.
- 성공하면 `plan.completed` 가 다시 나가고 fulfillment 의 웨이브가 `PLAN_FAILED → PLANNED` 로 돌아온다 — 그 경로는 이것 하나다(ADR-024 결정 3).
- `mode=FAST` 는 사람이 고른 열화다 — `dawnline_plan_degraded_total` 에 들어가지 않고 `route_plans.mode_reason` 이 `REQUESTED` 다(ADR-034).
- 감사 행이 남는다(`RUN_PLAN`). 응답이 `UNKNOWN`(504 · 502 `core-error`)이면 [RB-07](RB-07-audit-unknown.md) 이다 — dispatch 의 읽기 타임아웃은
  60초라 정상적인 계획이 여기 걸리지는 않는다(§5.5).

## 2. 느리다 — `DawnlinePlanDurationP95`

**먼저 본다 — 메트릭** 잘린 계획의 몫 — `prom 'sum by (mode, termination) (increase(dawnline_plan_duration_seconds_count[1h]))'`.

| 모양 | 뜻 | 대응 |
|---|---|---|
| `termination="deadline"` 이 대부분 | 예산(30초)이 문다 — 계획이 하지 못한 일을 남기고 끝났고, 결과는 그날의 기계 속도에 달렸다(ADR-036) | 규모를 본다: `prom 'max by (camp) (dawnline_wave_orders)'`. 열화 사다리가 이미 개선 예산을 줄이고 있는지(`dawnline_plan_degraded_total{reason="BUDGET"}`, 로그 「열화합니다」). 피크면 [RB-06](RB-06-peak-readiness.md) |
| `converged` 인데 길다 | 알고리즘이 끝까지 돌았는데 느리다 — 기계 쪽이다 | CPU 한도 · GC(콜드 스타트 — RB-06 §3). 같은 데이터셋의 벤치마크 수치(`docs/benchmarks/phase4-strategies.md`)와 견준다 |
| `mode="FAST"` 가 많다 | 랙이 열화를 부르고 있다 | 원인은 계획이 아니라 소비 랙이다 — [RB-01](RB-01-kafka-recovery.md) §2 |

**계획 시간은 알고리즘 시간만이다** — 저장은 `dawnline_plan_persist_seconds{camp}` 가 따로 잰다(ADR-029). 알림은 앞의 것에만 걸려 있다.

## 3. 걸린 행 — `*_stuck` 이 0 이 아니다

| 게이지 | 뜻 | 볼 곳 |
|---|---|---|
| `dawnline_fulfillment_orders_stuck` | 30일이 넘었는데 웨이브의 계획이 끝나지 않은 주문 | 어느 웨이브인가 — 게이지와 같은 조건(`JpaFulfillmentOrderRepository.COUNT_UNSETTLED_SQL`): `sql fulfillment "SELECT fo.wave_id, w.status, w.cutoff_at, count(*) FROM fulfillment_orders fo LEFT JOIN waves w ON w.id = fo.wave_id WHERE fo.updated_at < now() - interval '30 days' AND fo.status NOT IN ('CANCELLED', 'UNSERVICEABLE') AND (w.id IS NULL OR w.status NOT IN ('PLANNED', 'PLAN_FAILED')) GROUP BY 1, 2, 3"` → 웨이브가 `CLOSED` 면 그 계획(위 1), `OPEN` 이면 마감 스케줄러(로그 「웨이브 마감 실패」) |
| `dawnline_route_plans_stuck` | 30일이 넘었는데 종결이 아닌 계획 | 계획 상태가 끝나지 않았으면 위 1. **stop 이 끝나지 않았으면** 배송 결과가 dispatch 에 닿지 않은 것이다 — `delivery.status` 의 DLQ 에서 원래 그룹이 `dispatch-service` 인 레코드([RB-05](RB-05-dlq-and-outbox-quarantine.md) §2), 그리고 [README](README.md) 2.2 |

## 참조

- `docs/DESIGN.md` §5.3 · §6.7 · §8.4 · §9.1(`*_stuck` 행)
- [ADR-024](../adr/ADR-024-plan-completed-event.md) · [ADR-034](../adr/ADR-034-degrade-mode.md) · [ADR-036](../adr/ADR-036-deadline-belongs-to-the-plan.md) · [ADR-059](../adr/ADR-059-dispatch-retention-is-per-plan.md)
