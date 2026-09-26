# ADR-024 — 웨이브의 계획 완료는 `dawnline.plan.completed.v1` 이 알린다 (`route.assigned` 가 아니라)

| 항목 | 내용 |
|---|---|
| 상태 | Accepted (**후속 정정 있음** — 아래 「[후속 정정 — 2026-09-26] 계획 하나는 트랜잭션 하나다」) |
| 결정일 | 2026-09-05 |
| 정정일 | 2026-09-26 (7-3②) — **크래시의 회수는 롤백과 재전달이다.** `PLANNING` 은 커밋되지 않으므로 정체 회수를 지운다(근거: 관측(재현됨)) |
| 관련 문서 | `docs/DESIGN.md` §4.1 · §4.3 · §4.5 · §5.2 · §5.3 · §6.8 · `contracts/events/` · `CLAUDE.md` 불변 규칙 6·8 |
| 관련 ADR | [ADR-017](ADR-017-order-state-machine-absorbs-out-of-order-events.md) (축 규칙), [ADR-022](ADR-022-fulfillment-order-aggregate.md) (주문 애그리거트), [ADR-023](ADR-023-fulfillment-retention.md) (정리 배치의 전제) |

---

## 맥락

§5.2 의 웨이브 수명주기는 이렇게 적혀 있었다.

```
CLOSED ──(route.assigned 수신)──▶ PLANNED
       └──(plan.failed)────────▶ PLAN_FAILED
```

Phase 2-3 에서 `WaveStatus` 를 만들며 이 두 전이의 트리거를 찾다가 **어긋남 두 개**를 발견했다.

1. **§4.1 의 소비자 목록에 fulfillment 가 없다.** `route.assigned` → tracking·ops, `plan.failed` → ops.
   수명주기가 요구하는 소비자와 토픽 표의 소비자가 서로 다르다.
2. **`route.assigned` 는 웨이브의 완료 신호가 될 수 없다.** 라우트 단위 이벤트이고 웨이브 하나에
   라우트가 여럿이다(§6.7 은 캠프당 수십 대를 전제한다). "언제 웨이브가 `PLANNED` 가 되는가" 가
   정의되지 않는다 — 첫 라우트인가, 전부인가.

**이것이 남기던 것**: 그 전이가 발화하지 않으면 [ADR-023](ADR-023-fulfillment-retention.md) 의
정리 배치가 `PLANNED` 주문 행을 **영원히** 지우지 못한다. 삭제 조건이 "소속 웨이브가
`PLANNED`/`PLAN_FAILED`" 이기 때문이다. 보존 정책이 조용히 무한 보존이 된다.

### 처음에 떠올린 답이 왜 틀렸나

**"§4.1 에 fulfillment 를 추가하고 `route.assigned` 를 소비하면 된다."** 표를 고치는 쪽이 작아
보이지만, 그러면 2번이 그대로 남는다.

- **첫 라우트에서 전이하면 의미가 틀리다.** 라우트 1번이 확정된 시점에 계획은 아직 진행 중이다.
  그때 `PLANNED` 로 옮기면 "계획이 끝났다" 가 거짓이 되고, ADR-023 의 정리 배치는 아직 계획 중인
  웨이브의 주문 행을 종결로 본다.
- **전부를 세려면 개수를 알아야 한다.** 그런데 그 웨이브에 라우트가 몇 개인지는 **발행자만 안다.**
  소비자가 셀 방법이 없다. 개수를 `route.assigned` 에 실으면 웨이브 단위 사실이 라우트 수만큼
  반복되고, 그중 마지막 하나가 늦게 오거나 재정렬되면(§4.5 — 키가 routeId 라 라우트 간 순서는
  보장되지 않는다) 웨이브가 영영 `PLANNED` 가 되지 않는다.

빠져 있던 것은 소비자 목록이 아니라 **이벤트 자체**다. `plan.failed.v1` 은 이미 있는데 그 대칭이
없었다 — 계획은 실패했을 때만 알리고 성공했을 때는 알리지 않고 있었다.

---

## 결정

### 1. `dawnline.plan.completed.v1` 을 추가한다

| 항목 | 값 |
|---|---|
| 키 | `waveId` — `plan.failed` 와 같은 키다. 같은 토픽 안에서 웨이브당 순서가 보장된다 |
| 발행자 | dispatch-service |
| 소비자 | fulfillment(`CLOSED`/`PLAN_FAILED` → `PLANNED`), ops(`rm_waves`) |
| 발행 시점 | Plan 이 `PUBLISHED` 에 도달할 때, **라우트 발행과 같은 outbox 트랜잭션**(불변규칙 1) |

페이로드:

```json
{
  "planId": "…", "waveId": "…", "campId": "…",
  "strategy": "sweep-greedy-nn+ls", "mode": "FULL",
  "routeCount": 12, "assignedCount": 4780, "unassignedCount": 40,
  "totalCostKrw": 1638000, "planDurationMs": 18420
}
```

`routeCount` 는 이 이벤트를 받는 쪽이 "몇 개의 `route.assigned` 를 기다려야 하는가" 를 알 수 있게
한다 — ops 의 화면이 그것을 쓴다. fulfillment 는 `waveId` 만 있으면 되지만, 계획 결과를 요약하는
값들이 웨이브 단위로 나오는 유일한 자리이므로 여기 함께 싣는다.

### 2. 이것은 "최초 전체 계획의 완료" 다 — 부분 재계획은 다시 내지 않는다

§6.8 의 부분 재계획은 결과를 `route.assigned` 의 `revision` 증가로만 발행한다. `plan.completed`
를 다시 내지 않는다. 다시 내면 이 이벤트의 의미가 "계획이 끝났다" 에서 "계획이 또 바뀌었다" 로
흔들리고, 그 둘을 같은 값으로 세는 쪽(웨이브 상태·ops 화면)이 무엇을 세는지 모르게 된다.
이 문장은 **스키마 설명에 고정**한다.

### 3. `PLAN_FAILED → PLANNED` 를 허용한다 (운영자 재실행 경로)

§5.3 은 이미 "`FAILED` (plan.failed 발행, 운영자 재실행 가능)" 이라고 적고 있었는데, 웨이브 쪽에는
그 재실행이 성공했을 때 돌아올 자리가 없었다. `PLAN_FAILED` 는 **종결 상태가 아니다.**

ADR-023 과의 정합성: 정리 배치는 여전히 `PLANNED`/`PLAN_FAILED` 를 대상으로 한다. 재실행 창은
운영자가 실패를 보고 다시 돌리는 분~시간 단위이고 보존은 30·90일이라, 삭제가 재실행을 앞지르는
경우가 없다. (`isPlanningSettled()` 는 그대로 두고 `isTerminal()` 만 좁힌다.)

### 4. 마지막 두 전이는 축 규칙으로 흡수한다 — 웨이브도 순서 뒤바뀜을 겪는다

Phase 2-3 의 `WaveStatus` javadoc 은 "웨이브 전이는 전부 자기 자신이거나 인과적으로 앞선 사건이라
순서가 뒤바뀔 수 없다" 고 적었다. **결정 3 이 그 문장을 반쪽만 참으로 만든다.**

`OPEN → CLOSING → CLOSED` 는 여전히 자기 스케줄러가 하므로 뒤바뀔 수 없다. 그러나 재실행이
생기면 마지막 두 전이는 **서로 다른 두 토픽**에서 온다. `plan.failed`(1회차)와
`plan.completed`(2회차)는 키가 같아도 토픽이 달라 순서가 보장되지 않는다(§4.5). 즉 이 순서가
가능하다.

```
2회차 plan.completed 도착 → CLOSED→PLANNED
1회차 plan.failed  도착 → ??? (PLANNED → PLAN_FAILED)
```

그대로 두면 **라우트가 이미 나간 웨이브가 실패로 표시된다.** 그래서 [ADR-017](ADR-017-order-state-machine-absorbs-out-of-order-events.md)
의 축 규칙을 마지막 두 상태에 적용한다.

```
OPEN(0) → CLOSING(1) → CLOSED(2) → PLAN_FAILED(3) → PLANNED(4)
```

`PLANNED` 가 축의 끝이고 **흡수 상태**다. 늦게 온 `plan.failed` 는 `PLAN_FAILED`(3) ≤ 현재(4) 라
무시하고 커밋한다(`dawnline_event_rejected_total{reason="wave_already_planned"}`, §4.6 — DLQ 아님).
이것은 순서 뒤바뀜을 봐주는 편법이 아니라 **의미가 맞다**: 계획된 웨이브를 운영자가 다시 돌려
실패해도, 1회차의 라우트는 여전히 유효하고 그 웨이브는 계획된 웨이브다.

`canTransitionTo` 는 좁힌 채로 둔다. `OPEN → CLOSED` 같은 건너뜀은 여전히 예외다 — 앞의 세
상태는 이 서비스가 스스로 옮기므로 건너뜀은 버그이지 순서 뒤바뀜이 아니다. **축 규칙은 리스너가
"철 지난 이벤트인가" 를 판단할 때만 쓴다.**

### 5. 스키마는 소비자가 먼저 정의한다 — fulfillment 쪽, Phase 2

`plan.completed` 와 `plan.failed` 의 발행자는 Phase 3 의 dispatch-service 다. Phase 1 에서
`order.dispatched`·`delivery.status` 를 order-service 가 먼저 정의한 것과 같은 이유로(계약
README §1 「예외: 소비자가 먼저 정의하는 계약」), 두 스키마를 **소비자인 fulfillment 가 Phase 2 에
정의한다.** 그러지 않으면 `CLOSED → PLANNED/PLAN_FAILED` 두 전이가 Phase 3 까지 한 번도 검증되지
않고, ADR-023 의 정리 배치도 Phase 3 이 되어서야 처음 돌아 본다.

`plan.failed` 를 여기서 함께 정의하는 이유는 대칭이기 때문이다. 두 전이는 같은 리스너 쌍이고,
한쪽만 계약이 있으면 다른 쪽 전이는 통합 테스트에서 예시 이벤트를 만들 수 없다.

### 6. "부분 실패" 는 `plan.completed` 가 나른다

§4.1 의 `plan.failed` 의미 칸은 "계획 실패/부분 실패" 였다. 이제 두 이벤트가 있으므로 그 경계를
정한다 — **`plan.failed` 는 §5.3 Plan 상태 머신의 `FAILED`(예외·시간초과) 뿐**이고, 계획은
성공했는데 일부 주문이 배정되지 않은 것(§6.7 의 미배정률)은 `plan.completed` 의
`unassignedCount` 가 나른다. 그 둘이 한 이벤트에 섞이면 웨이브 상태가 무엇이어야 하는지 답이
갈린다 — 라우트가 나갔으면 `PLANNED` 다.

---

## 고려한 대안과 기각 이유

| 대안 | 기각 이유 |
|---|---|
| fulfillment 를 `route.assigned` 소비자에 추가, 첫 라우트에서 `PLANNED` | 계획이 아직 진행 중인데 완료로 표시된다. ADR-023 이 진행 중 웨이브의 주문 행을 종결로 본다 |
| `route.assigned` 에 `routeCount`/`isLast` 를 실어 전부를 센다 | 웨이브 단위 사실이 라우트 수만큼 반복된다. 라우트 간 순서는 보장되지 않으므로(§4.5, 키=routeId) 마지막 하나가 재정렬되면 웨이브가 영영 `PLANNED` 가 되지 않는다 |
| fulfillment 가 dispatch 에 계획 상태를 동기 조회 | 불변규칙 4 위반(코어 서비스 간 동기 호출 금지). 게다가 폴링 주기가 곧 지연이 된다 |
| `wave.closed` 처럼 캠프 키로 발행 | 웨이브 단위 사실이라 캠프 직렬화가 필요 없고, `plan.failed` 와 키가 달라지면 두 이벤트의 순서 관계를 논할 근거마저 사라진다 |
| §5.2 수명주기에서 마지막 두 전이를 삭제(웨이브는 `CLOSED` 가 종점) | 계획 결과를 웨이브에서 못 본다. ADR-023 의 정리 조건이 성립하지 않아 `PLANNED` 주문 행이 영구 보존된다 |

---

## 결과

**좋아지는 것**

- 웨이브의 완료 신호가 **웨이브 단위 이벤트 하나**가 된다. "언제 `PLANNED` 가 되는가" 에 답이 하나다.
- ADR-023 의 정리 배치가 전제하던 전이가 실제로 발화한다. 보존 정책이 살아난다.
- `plan.failed` 만 있고 성공 신호가 없던 비대칭이 사라진다. ops 는 계획 결과 요약(비용·미배정·소요)을
  라우트를 다 모으지 않고도 웨이브 단위로 받는다.

**대가**

- 토픽이 하나 늘어난다(10 → 11). 파티션·컨슈머 그룹·DLQ 가 함께 는다.
- dispatch 는 라우트 발행과 `plan.completed` 를 **같은 트랜잭션**에 넣어야 한다. 나눠 넣으면
  "완료라는데 라우트가 없다" 가 생긴다.
- ops 는 `plan.completed` 를 일부 `route.assigned` 보다 **먼저** 받을 수 있다. 같은 트랜잭션에서
  나가도 토픽이 다르고 파티션이 다르기 때문이다(§4.5). `rm_waves` 는 `routeCount` 를 저장해 두고
  뒤늦게 오는 라우트를 세는 식이어야 하며, 아직 다 안 왔다고 실패로 표시하지 않는다.
  **Phase 6 메모**로 남긴다.

**의존 경고**

- 결정 3(`PLAN_FAILED → PLANNED`)을 되돌리면 결정 4 의 축도 함께 무너진다. `PLAN_FAILED` 가 다시
  종결 상태가 되면 두 토픽 간 순서 뒤바뀜을 흡수할 자리가 없어진다.
- 결정 2 를 바꿔 부분 재계획이 `plan.completed` 를 다시 내게 하면, 결정 4 의 흡수 규칙이 두 번째
  완료를 stale 로 버린다. 재계획 결과가 조용히 사라지는 형태로 나타나므로, 그때는 축이 아니라
  `revision` 비교로 바꿔야 한다.

---

## [후속 정정 — 2026-09-26] 계획 하나는 트랜잭션 하나다 — 크래시의 회수는 롤백과 재전달이고 `PLANNING` 은 커밋되지 않는다

7-3②. 「대가」의 둘째 줄(라우트 발행과 `plan.completed` 는 **같은 트랜잭션**)은 실제 코드에서 더 넓게 성립하고 있었다. `WaveClosedListener`
가 `runPlan.run()` 을 멱등 소비자의 트랜잭션(`IdempotentConsumer.runOnce`) 안에서 부르고, `run()` 의 `@Transactional` 은 그 트랜잭션에
합류한다. 그래서 **계획 열기(`REQUESTED` 삽입) · `PLANNING` 전이 · 결과 쓰기 · 세 이벤트 · `processed_events` 가 전부 한 트랜잭션**이고,
그 트랜잭션은 `PUBLISHED` 나 `FAILED` 로만 커밋된다. 운영자 재실행(`PlanController`)도 같은 `run()` 이다.

그런데 §5.3 은 「계획 중 인스턴스가 죽으면 `PLANNING` 상태로 남는다」고 적었고, 그 상태의 출구로 정체 회수(`RecoverStalePlansService` —
1분마다, `started_at` 10분 경과)를 두었다. §8.4 의 「dispatch 계획 중 크래시 → 10분 후 자동 재실행」, §8.6 의 「진행 중 계획은 `PLANNING`
유지(재실행 경로가 회수)」, RB-04 의 「`PLANNING`, 10분 넘음 — 저절로 회수된다」가 같은 전제 위에 있었다.

**근거: 관측(재현됨)** (2026-09-26, 로컬 스택): dispatch DB 의 `routes` 에 배타 락을 걸어 계획을 결과 쓰기 직전에 세웠다(157 주문
웨이브 하나를 조기 마감). dispatch 의 백엔드가 `INSERT INTO routes` 에서 락을 기다리는 것 — 트랜잭션을 연 지 5초 — 을 `pg_stat_activity`
로 확인한 뒤 컨테이너에 `SIGKILL`. 죽인 뒤 그 웨이브의 `route_plans` 행은 **없었고** 전체 `PLANNING` 은 0. 재기동 26초 뒤 `wave.closed`
가 다시 전달돼 `PUBLISHED`(리스너 스레드의 「웨이브 계획 … 결과=PUBLISHED」). 「정체된 계획」 회수 로그는 없었다.

### 결정

1. **계획 하나는 트랜잭션 하나다 — 정본은 이쪽이다.** 크래시(또는 연결 끊김 · 예외)는 트랜잭션을 롤백하고, 커밋되지 않은 오프셋이
   `wave.closed` 를 **다시 전달한다**. 그것이 크래시의 회수 경로다. 회수는 재기동 즉시 일어난다 — 10분을 기다리지 않는다.
   부분 결과가 발행되지 않는다는 §5.3 의 성질은 그대로이고, 이제 그 이유가 하나다(트랜잭션).
2. **정체 회수를 지운다** — `RecoverStalePlansService`, 저장소의 `findStalePlanning`, `RunPlanCommand.rerun`, 설정
   `dawnline.dispatch.plan.stale-after` · `recover-batch` · `recover-interval-ms` · `recover-initial-delay-ms`. 입력(커밋된 `PLANNING`)이
   생기는 경로가 없으니 출구가 아니라 **출구가 있다는 인상**이었다. 남겨 두면 누가 트랜잭션을 나눴을 때 그 사실을 조용히 받아 준다 —
   그때 필요한 것은 되살린 스케줄러가 아니라 그 변경의 ADR 이다.
3. **그 성질을 테스트가 고정한다** — `PlanCrashIT`: 계획이 결과 쓰기에서 멈춘 채 그 연결이 끊기면 `route_plans` 에 그 웨이브의 행이
   남지 않고, 같은 `wave.closed` 가 다시 처리되어 `PUBLISHED` 하나 · 라우트 중복 없음으로 끝난다. 카오스(`make chaos-kill`)는 같은 것을
   프로세스 수준에서 본다.
4. `PLANNING` 은 도메인 상태로 남는다 — 트랜잭션 **안**의 전이이고(`RoutePlan.begin`), 다른 세션에는 보이지 않는다. 계획 중인 웨이브는
   밖에서 보면 **`route_plans` 에 행이 없다.** 운영자가 볼 것은 웨이브가 `CLOSED` 인데 행이 없는 시간이다(RB-04 §1).

### 고려한 대안

- **설계서를 정본으로 — `PLANNING` 을 먼저 커밋한다.** 계획 중인 상태가 밖에서 보이고 §5.3 의 문장이 참이 된다. 기각: 계획 한 번에
  트랜잭션이 둘이 되고, 멱등 기록(`processed_events`)을 첫 트랜잭션에 넣으면 재전달이 막혀 크래시 뒤에는 회수가 **유일한** 출구가 된다 —
  웨이브가 최소 정체 판정 시간만큼(90초 ~ 10분) 멈춘다. 지금은 재기동 즉시다. 보이게 하는 값은 로그와 `pg_stat_activity` 가 이미 준다.
- **회수를 방어로 남긴다.** 기각: 결정 2.

### 결과

- 좋아지는 것: 크래시 회수가 10분 → 재기동 즉시. 설정 넷과 스케줄 작업 하나가 사라진다(dispatch 의 `@Scheduled` 여섯 → 다섯).
- 대가: 계획이 도는 동안 DB 트랜잭션 하나가 예산(30초) + 영속화 시간만큼 열려 있다 — 원래도 그랬다(이 정정은 코드를 바꾸지 않고 문서를
  코드에 맞춘다). 커넥션 하나를 그동안 쥔다.
- **재검토 지점**: 계획 시간이 `max.poll.interval.ms`(기본 300초)에 다가가면 — 트랜잭션 안의 계획이 폴 간격을 넘기면 컨슈머가 그룹에서
  쫓겨나고 같은 `wave.closed` 가 다른 소비자에게 가서 **두 계획이 한 행을 두고 경합한다**(`wave_id` UNIQUE 가 하나를 막는다). 예산 30초는
  그 10분의 1 이다(ADR-015 후속 정정의 「백오프 상한 < 폴 간격」과 같은 관계).
