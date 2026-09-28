# ADR-074 — 후보보다 먼저 온 취소는 행을 남긴다 · 뒤에 온 `fulfillment.planned` 는 그 행을 되살리지 않는다

| 항목 | 내용 |
|---|---|
| 상태 | Accepted (2026-09-28) — 결함 주장의 근거는 **관측(재현됨)** (아래 「근거 표기」) |
| 결정일 | 2026-09-28 |
| 관련 문서 | `docs/DESIGN.md` §5.3 DDL · §6.10 · §7.1 보존 표 · §9.1 · §13 축 18 · `docs/benchmarks/phase7-window-scenarios.md` §3.5 · §3.7 항목 7 · `docs/IMPLEMENTATION_PLAN.md` 7-0 A37 · `tools/chaos/verify.sh` V9 |
| 관련 ADR | [ADR-022](ADR-022-fulfillment-order-aggregate.md) (fulfillment 의 같은 모양 — 취소 선착 행) · [ADR-026](ADR-026-dispatch-cancellation-window.md) (§6.10 의 네 분기 — 이 ADR 이 그 앞에 한 행을 더한다) · [ADR-051](ADR-051-first-fact-creates-the-row-absence-is-not-a-value.md) (먼저 온 사실이 행을 만든다 · 부재는 값이 아니다 — 축 규칙의 다섯 번째 자리) · [ADR-059](ADR-059-dispatch-retention-is-per-plan.md) (보존 — 표식을 지우는 줄) |

---

## 맥락

turbulent 실행에서 **계획 전 취소 234건 중 12건이 배송됐다**(리포트 §3.5, 근거: 관측). order-service · fulfillment 는 234건 모두
`CANCELLED` 였고, dispatch 는 그 12건을 계획해 발행했으며 tracking 은 `COMPLETED` 로 끝냈다.

한 주문의 순서(유효 시각): 접수 13:59:55.454 → 취소 .459(5 ms 뒤) → dispatch 후보 생성 .578. dispatch 가 받는 두 사실은
**다른 토픽**에서 온다 — 후보는 `fulfillment.planned` 가, 취소는 `order.cancelled` 가 만든다. §4.5 가 보장하는 순서는 같은 토픽 ·
같은 키 안의 것뿐이다. 그래서 fulfillment 가 `order.placed` 를 먼저 처리해 `fulfillment.planned` 를 낸 뒤에 취소를 받아도, 두 이벤트가
dispatch 에 닿는 순서는 정해져 있지 않다.

`CancelOrderService` 는 후보가 없으면 `NOT_A_CANDIDATE` 로 넘겼다. 그 자리의 주석은 이 순서를 알고 있었다:

> 후자라면 그 이벤트가 왔을 때 이미 취소된 주문을 적재하게 되는데, 그것은 §6.10 이 아니라 순서 역전의 문제라 여기서 만들어 두지 않는다.

**결함을 알고 적은 주석이 그 결함의 유일한 기록이었다.** 테스트도 원장 행도 가리키지 않았다 — 그래서 결함은 어디에도 열린 항목으로
없었고, 이 실행이 처음 셌다. 이 모양을 §13 의 열여덟째 축으로 적는다(결정 6).

그리고 V 표는 이것을 보지 못했다. V1 은 주문의 끝을 「후보 · 취소 · 배차 불가」 중 하나로 세는데, 12건은 order-service 에서
`CANCELLED` 라 「취소로 끝난 주문」으로 통과했다. V8 은 dispatch `PLANNED` ∧ tracking `COMPLETED` 를 보지만 취소된 주문을 뺀다.
**두 서비스의 사실이 갈린 방향 하나를 V 표가 보지 않았다.**

---

## 결정

### 1. 먼저 온 취소가 후보 행을 만든다 — `CANCELLED`, 웨이브 없이

`order.cancelled` 가 왔는데 그 주문의 후보 행이 없으면 **행을 만든다.** 상태는 `CANCELLED` 이고, 스냅샷 칸(웨이브 · 캠프 · 좌표 ·
화물 · 약속창 · 서비스 시간 · 우선도와 그 근거)은 비어 있다. 이 문서는 그 행을 **취소 선착 표식**이라 부른다.

이것은 [ADR-051](ADR-051-first-fact-creates-the-row-absence-is-not-a-value.md) 의 모양이다 — **먼저 온 사실이 행을 만든다.**
어느 토픽이 이 주문의 행을 처음 만들지는 설계의 성질이 아니라 그날의 컨슈머 랙의 성질이라, 「후보는 `fulfillment.planned` 가
만든다」는 특권을 둘 수 없다. **축 규칙의 여섯 번째 자리다** — order(ADR-017) · fulfillment 의 웨이브(ADR-024) · tracking ·
dispatch 의 stop(ADR-047) · ops 의 읽기 모델(ADR-051) 다음이다. fulfillment 는 같은 문제를 Phase 2 에 같은 방식으로 풀었다
([ADR-022](ADR-022-fulfillment-order-aggregate.md) — `CANCELLED` + `placed_event_id NULL`).

### 2. 뒤에 온 `fulfillment.planned` 는 그 행을 되살리지 않는다

적재는 이미 `INSERT … ON CONFLICT (order_id) DO NOTHING` 이다 — 그 문장은 바꾸지 않는다. 바뀌는 것은 **넣지 못했을 때의 결론**이다.
지금까지는 「이미 적재된 후보 — 재전달」 하나였고, 이제 둘로 가른다.

| 이미 있는 행 | 결과 | 셈 |
|---|---|---|
| 스냅샷이 있는 후보 | `DUPLICATE` — 재전달이다. 덮어쓰지 않는다(ADR-020) | 없음 |
| 취소 선착 표식 | `CANCELLED_FIRST` — **거부한다.** 행은 그대로 `CANCELLED` 이고 스냅샷을 채우지 않는다 | `dawnline_event_rejected_total{consumer="dispatch-service", eventType="fulfillment.planned", reason="cancelled_before_candidate"}` |

표식을 만든 쪽(`order.cancelled`)은 `dawnline_event_stale_total{consumer="dispatch-service", eventType="order.cancelled"}` 로
센다 — 순서 역전을 흡수한 설계된 동작이다. **라벨 배치는 fulfillment 의 짝과 같다**(ADR-022: 취소 선착 → stale, 뒤에 온
`order.placed` → `rejected{reason="cancelled_before_placed"}`). 같은 경합의 두 서비스 쪽이 같은 이름으로 보여야 한 질의로 견줄 수 있다.

뒤에 온 이벤트를 `stale` 이 아니라 `rejected` 로 세는 것은 ADR-051 결정 5(「아직 안 왔다」는 실패가 아니다)와 부딪히지 않는다 —
그 결정은 **빠진 사실이 `NULL` 칸 하나만 만드는** 경우의 것이다. 여기서 뒤에 온 이벤트는 흡수되는 것이 아니라 **내용 전체가
버려진다**: 스냅샷이 적히지 않고, 그 주문은 계획에 들어가지 않는다. 이 카운터의 값은 이 ADR 이 없었다면 **배송됐을 취소 주문의 수**이고,
사람이 볼 가치가 있다(ADR-022 가 같은 판단을 했다).

거부는 `EventRejectedException` 으로 한다 — 계약대로 **어떤 상태도 바꾸기 전에** 던진다. `ON CONFLICT DO NOTHING` 은 아무것도 쓰지
않았고, 표식인지 확인하는 것은 읽기다.

### 3. 비어 있는 칸은 `NULL` 이다 — `false` · `0` 을 쓰지 않는다 (V15)

표식의 스냅샷 칸은 **모른다.** ADR-051 결정 2 대로 부재는 값이 아니다 — `requires_cold = false` 는 「냉장이 아니다」라는 주장이고,
표식은 그 주장을 할 근거가 없다. 그래서 V15 는:

- 스냅샷 칸 열넷(`wave_id` · `camp_id` · `lat` · `lng` · `geohash7` · `weight_g` · `volume_cm3` · `requires_cold` · `hazmat` ·
  `promised_start` · `promised_end` · `service_seconds` · `promise_revised` · `priority`)의 `NOT NULL` 을 푼다.
- 기본값이 있던 넷(`requires_cold` · `hazmat` · `promise_revised` · `priority`)의 **기본값을 지운다.** 기본값이 남으면 칸을 빠뜨린
  `INSERT` 가 `false` · `0` 을 조용히 쓴다 — 표식에서도, 스냅샷 행에서도.
- **CHECK 하나**로 행의 모양을 둘로 닫는다: 스냅샷 칸이 **전부** 있거나, `status = 'CANCELLED'` 이고 **전부** 없다. 반쯤 찬 행은
  들어갈 수 없다. 앞의 기본값 삭제가 이 CHECK 와 한 쌍이다 — 스냅샷 행이 칸 하나를 빠뜨리면 기본값이 메우지 않고 CHECK 가 거절한다.

`zone_id` 는 원래 `NULL` 을 허용한다(지오코딩 실패). CHECK 의 두 모양 어디에도 들지 않는다.

**엔티티는 스냅샷 행만 든다.** `DispatchCandidateEntity` 의 원시 타입 칸은 `NULL` 을 담을 수 없고, 담게 만들면 도메인의
`DispatchCandidate` 가 모든 칸을 `@Nullable` 로 들어야 한다 — 계획 · 재배정 · 취소가 전부 그 칸을 읽는다. 그래서 표식은 네이티브
문장으로만 쓰고 읽는다: `insertCancelledFirst` 가 넣고, `findById` 는 **스냅샷 행만** 돌려준다(`wave_id IS NOT NULL`).
「행이 있는데 `findById` 가 비었다」는 곧 「표식이다」이고, 그 해석은 저장소 포트의 Javadoc 한 곳에 있다.

### 4. 두 리스너가 동시에 오면 PK 가 순서를 정한다

두 토픽은 서로 다른 리스너 컨테이너가 받으므로 같은 주문의 두 이벤트가 **동시에** 처리될 수 있다. 둘 다 `ON CONFLICT (order_id)
DO NOTHING` 으로 넣으므로 먼저 넣은 쪽이 이기고, 진 쪽의 `INSERT` 는 이긴 쪽이 커밋할 때까지 기다린 뒤 아무것도 하지 않는다.

- 표식이 이기면: 적재가 표식을 보고 거부한다(결정 2).
- 후보가 이기면: 취소가 표식을 넣지 못한다. 그때 **다시 읽는다** — READ COMMITTED 에서 새 문장은 커밋된 후보를 본다 — 그리고
  §6.10 의 첫 행(후보 취소)으로 간다.

잠금을 따로 두지 않는다. 경합의 심판은 PK 하나이고, 그것은 이미 있다.

### 5. 표식은 계획의 입력이 아니다 · 보존은 상한 줄이 지운다

- 계획은 `wave_id = ? AND status = 'PENDING'` 으로 후보를 집는다 — 표식은 웨이브가 없고 상태도 `CANCELLED` 라 **두 겹으로** 빠진다.
  결과 반영(`recordPlanResult`)은 `status = 'PENDING'` 만 바꾸므로 표식을 뒤집지 않는다.
- 보존: 웨이브 단위의 삭제(`wave_id = ?`)는 표식을 고르지 않는다. 표식은 **상한 줄**(§7.1 보존 표 「`dispatch_candidates` — 상한」, 365일)이
  지운다 — `NOT EXISTS (SELECT 1 FROM route_plans p WHERE p.wave_id = c.wave_id)` 는 `c.wave_id` 가 `NULL` 이면 언제나 참이다.
  이 해석은 SQL 의 `NULL` 비교에 기대므로 **테스트가 가리킨다**(`DispatchRetentionIT`) — 문장을 고쳐 표식이 빠지면 그 IT 가 빨갛다.
  365일은 늦은 `fulfillment.planned` 의 상한(DLQ 보존 30일, §7.3 — 재처리가 가장 늦게 되살리는 이벤트)보다 길다.

### 6. V9 — order 의 `CANCELLED` ∧ tracking 의 `COMPLETED`, 취소 시각으로 가른다

`tools/chaos/verify.sh` 에 V9 를 더한다. T0 이후 주문 중 order-service 에서 `CANCELLED` 이고 tracking 에서 `COMPLETED` 인 주문을,
**취소 시각**(`orders.updated_at` — `CANCELLED` 는 종결이라 그 뒤로 바뀌지 않는다)과 **그 주문의 계획**(`route_plans`)의 시각으로 가른다.

| 칸 | 기대 | 이유 |
|---|---|---|
| 계획 시작 전 취소 | **0** | 이 ADR 이 닫는 자리다. turbulent 의 12건이 이 행의 첫 ✗ 다 |
| 계획 시작 뒤 취소 | 관찰 — 센다 | §6.10 넷째 분기(`TOO_LATE`)와 `scan_after_cancel` 의 경합 창이다. 그 크기를 재는 값이지 결함이 아니다 |

V1 의 「후보」 집합은 **스냅샷이 있는 행**(`wave_id IS NOT NULL`)으로 좁힌다 — 표식은 「취소로 끝난 주문」 쪽에 있어야 V1 의
세 끝이 이전과 같은 뜻이다.

### 7. 주석이 결함을 넘긴 모양에 이름을 붙인다 — §13 축 18

「아는 결함을 주석으로 넘기면 결함이 남는다 — **주석은 테스트나 원장 행을 가리켜야 한다.**」 `CancelOrderService` 의 새 주석은
이 ADR 과 재현 IT 를 가리킨다.

---

## 고려한 대안과 기각 이유

**(b) fulfillment 가 취소된 주문의 `fulfillment.planned` 를 막는다.** 이 경합을 막지 못한다. 12건에서 fulfillment 는 `order.placed` 를
**먼저** 처리했다 — `fulfillment.planned` 는 취소가 fulfillment 에 닿기 전에 이미 outbox 에 있었다. 막을 이벤트가 이미 떠난 뒤다.
그리고 막더라도 dispatch 에서 두 토픽의 순서는 여전히 보장되지 않는다. 경합은 **받는 쪽**의 문제이고, 받는 쪽이 풀어야 한다.

**표식을 별도 표에 둔다(`cancelled_before_candidate` 따위).** 설계서에 없는 표를 늘리고, 「이 주문은 취소됐다」가 두 곳에 산다 —
적재가 두 표를 봐야 하고, 둘을 함께 지우는 보존 줄이 하나 더 는다. 한 행 · 한 PK 로 경합을 심판하는 결정 4 도 성립하지 않는다.

**`fulfillment.planned` 가 오면 order-service 에 되묻는다.** 불변규칙 4(코어 서비스 간 동기 호출 금지).

**표식을 만들지 않고 계획 때 취소 여부를 다시 확인한다.** dispatch 가 취소를 기억하지 않으면 계획 때 확인할 사실이 없다 — 그 사실이
바로 표식이다.

**도메인 `DispatchCandidate` 가 표식을 든다(칸을 전부 `@Nullable` 로).** 계획 · 재배정 · 취소 · 보존이 모두 스냅샷 칸을 읽는다.
그 칸이 `@Nullable` 이 되면 모든 읽는 자리가 「표식이면?」을 물어야 하고, 묻지 않은 자리는 컴파일이 통과한다. 표식은 저장소의 경계에서
멈춘다(결정 3).

**스냅샷 칸에 기본값(`false` · `0` · 빈 좌표)을 넣어 `NOT NULL` 을 지킨다.** ADR-051 결정 2 — 모르는 칸에 값을 쓰면 그것이
주장이 된다. 좌표 `(0, 0)` 은 기니만의 지점이다.

---

## 결과

**장점**
- 취소된 주문이 계획에 들어가지 않는다 — 두 토픽의 순서와 무관하게. 재현 IT 가 두 순서를 모두 본다(`CandidateLoadingIT`).
- 흡수 · 거부가 fulfillment 와 같은 이름으로 보인다 — 한 질의로 두 서비스의 같은 경합을 견준다.
- V 표가 두 서비스의 사실이 갈린 방향 하나(취소된 주문의 배송)를 처음으로 본다.

**비용**
- `dispatch_candidates` 의 칸 열넷이 `NULL` 을 허용한다. 모양은 CHECK 가 둘로 닫지만, 칸을 더할 때 CHECK 도 함께 고쳐야 한다 —
  빠뜨리면 새 칸은 CHECK 밖에 있다. (재검토 지점 1)
- 엔티티와 도메인이 표식을 모른다 — 표식은 네이티브 문장 둘(넣기 · 스냅샷 행만 찾기)로만 지난다.
- 계획 전에 취소된 주문은 `fulfillment.planned` 가 끝내 오지 않아도(fulfillment 도 취소 선착) 표식을 남긴다. 상한 줄이 365일 뒤 지운다.
  규모는 접수 직후 취소의 일부다(turbulent 는 계획 전 취소 234건).

**되돌리는 방법**
- 코드: `CancelOrderService` 의 표식 분기와 `LoadCandidateService` 의 `CANCELLED_FIRST` 를 빼면 이전 동작이다(결함 포함).
- 스키마: 새 `V` 로 표식을 지우고 `NOT NULL` · 기본값을 되돌린다. V15 는 고치지 않는다(불변규칙 13).

---

## 재검토 지점

1. **스냅샷 칸이 늘 때** — CHECK 의 두 모양에 새 칸을 넣는다. `DispatchPersistenceIT` 가 반쯤 찬 행이 거절되는지를 본다 —
   새 칸이 CHECK 밖에 있으면 그 칸만 비운 행이 들어간다.
2. **`fulfillment.planned` 의 지연이 365일에 다가갈 때** — 없는 일이지만 상한 줄이 표식을 지운 뒤에 오는 `fulfillment.planned` 는
   다시 후보가 된다. 그 경계는 DLQ 보존(30일)이 먼저 막는다.
3. **V9 의 「계획 시작 뒤」 칸이 커질 때** — ADR-026 의 경합 창이 가정보다 넓다는 뜻이다. `dawnline_cancel_too_late_total` 과 함께 본다.

---

## 근거 표기

- 12건이 배송됐다: **근거: 관측** — turbulent 실행의 세 서비스 DB(리포트 §3.5).
- 순서 역전이 원인이다: **근거: 관측(재현됨)** — `CandidateLoadingIT.취소가_먼저_오면_뒤에_온_fulfillment_planned_는_후보를_되살리지_않는다` 가
  정정 전 코드에서 빨갛다(후보가 `PENDING`).
- (b) 가 경합을 막지 못한다: **근거: 관측** — 12건 모두 fulfillment 의 행이 `placed_event_id` 를 가진 채 `CANCELLED` 다(`order.placed` 를 먼저 처리했다).
