# ADR-071 — 배송의 사실은 주문의 행에 적는다 · stop 의 상태는 그 주문들에서 다시 센다

| 항목 | 내용 |
|---|---|
| 상태 | Accepted (2026-09-28) |
| 결정일 | 2026-09-28 |
| 관련 문서 | `docs/DESIGN.md` §4 「dispatch 가 `delivery.status` 를 소비한다」 · §5.3 DDL · §13 불변규칙 6 · `docs/benchmarks/phase7-window-scenarios.md` §4 항목 6 |
| 관련 ADR | [ADR-047](ADR-047-delivery-status-is-a-fact-not-a-revision.md) (사실은 `orderId` 로 식별한다 — 이 ADR 은 그 원칙을 **저장**에 적용한다 · 재검토 지점 ④ 를 닫는다) · [ADR-061](ADR-061-unfinished-work-has-no-window.md) (쓰기 때 다시 센다 — 같은 모양) · [ADR-068](ADR-068-replan-write-locks-and-moves-rows.md) 후속 C (「가르는 동안 도착한 배송」 — V8 ✗ 가 남아 있던 자리) · [ADR-026](ADR-026-dispatch-cancellation-window.md) 결정 2 (닿은 stop 은 옮기지 않는다) |

---

## 맥락

ADR-047 은 「계획은 `(route, revision, seq)` 로, 사실은 `orderId` 로 식별한다」를 정했다. dispatch 는 `delivery.status` 의 주문으로 stop 을 **찾았지만**,
찾은 뒤에는 **stop 하나의 칸**(`route_stops.status`)에 적었다 — 그 stop 의 어느 주문의 사건이 와도 stop 전체가 그 상태가 된다. 사실은 주문의 것인데
저장은 stop 의 것이라, 한 stop 에 주문이 여럿이면 하나가 나머지의 사실을 대신 말한다. 그 칸이 같은 사실의 **둘째 출처**였다.

한 stop 에 주문이 여럿인 경우는 드물지 않다 — 계획이 같은 지점 · 같은 약속창의 주문을 합치고(§6.5 1단계), 재배정이 같은 지점의 `PLANNED` stop 에
붙인다(§5.3). **근거: 관측(재현됨)** — `DeliveryFactPerOrderIT` 를 먼저 쓰고 정정 전 코드에서 돌렸다. 셋 모두 빨강이었다:

1. **한 주문만 끝나도 stop 이 끝난다.** 같은 지점의 두 주문 중 하나에 `COMPLETED` 가 오자 stop 이 `COMPLETED` — 다른 주문은 배송되지 않았다.
2. **옮겨 온 주문이 배송된 것으로 보인다.** 재배정이 같은 건물의 stop 에 붙인 주문이 있는데, 그 라우트의 기사가 옛 개정으로 원래 주문만 찍자 stop 이
   `COMPLETED` — 옮겨 온 주문은 계획에서 끝난 것이 된다(재계획 · 보존 · 차량 비활성화가 모두 그 stop 을 끝난 것으로 본다). ADR-047 재검토 지점 ④ 가
   「줄였지만 막지 않았다(근거: 추정)」로 남긴 자리다.
3. **가르는 동안 함께 배송된 주문이 옛 자리에만 적힌다.** 합쳐진 stop 에서 하나를 다른 라우트로 뗀 뒤 두 주문의 `COMPLETED` 가 한 사건으로 오면,
   ADR-047 결정 2 의 마지막 문단대로 「이벤트의 라우트에 있는 쪽」 하나만 적힌다 — 떼어 낸 주문의 새 stop 은 `PLANNED` 이고 tracking 에서는
   `COMPLETED` 다(V8 ✗, ADR-068 후속 C 가 결정 항목으로 넘긴 것).

## 결정

1. **사실은 주문의 행에 적는다 — `route_stop_orders.status` · `actual_at`(V12).** `delivery.status` 의 주문마다 그 주문이 **지금** 있는 행(ADR-047
   결정 2)에 상태와 처음 닿은 시각을 적는다. 전이 판정(`RouteStopTransition`)도 주문마다 한다 — 그 주문의 상태(후보가 취소됐으면 `CANCELLED`)와
   보고된 상태로. 한 사건의 주문들이 서로 다른 stop 에 있으면 **각자의 자리에** 적힌다 — ADR-047 결정 2 의 「하나를 택한다」는 stop 하나의 칸에
   적어야 했기 때문에 있던 타협이고, 주문의 행이 생기면 고를 것이 없다. ADR-047 의 원칙(사실은 주문에 귀속된다)은 그대로이고, 이 결정은 그것을
   저장에 적용한 것이다.
   - 주문의 값은 `PLANNED | ARRIVED | COMPLETED | FAILED` 다. **취소는 여기 적지 않는다** — 취소의 출처는 `dispatch_candidates.status` 하나다(§6.10).
   - `actual_at` 은 그 주문에 **처음** 닿은 시각이다(`COALESCE`, ADR-048 결정 1 의 규칙을 주문으로).
2. **stop 의 상태는 그 주문들에서 다시 센다 — 쓰기 때(ADR-061 과 같은 모양).** 주문의 행을 고친 트랜잭션이 그 stop 의 상태와 `actual_at` 을 다시 계산해
   적는다. 읽는 쪽(끝나지 않은 stop 의 조각 `UNFINISHED_STOP` 넷 · 재계획 · 재배정 · 보존 · 발행 · 조회)은 그대로다. 규칙 — 살아 있는 주문(후보가
   취소되지 않은 주문)만 센다:

   | 살아 있는 주문들 | stop |
   |---|---|
   | 없다 | `CANCELLED` |
   | 전부 `PLANNED` — **아무도 닿지 않았다** | `PLANNED` |
   | 전부 끝났다(`COMPLETED`·`FAILED`), 실패 없음 | `COMPLETED` |
   | 전부 끝났다, 실패가 하나라도 | `FAILED` |
   | 그 밖 — 일부만 닿았거나 끝났다 | `ARRIVED` |

   `actual_at` 은 그 주문들의 처음 닿은 시각 중 가장 이른 값 — 그 stop 에 기사가 처음 닿은 시각이다(ADR-048 의 앵커 그대로).
3. **stop 단위의 검사는 stop 단위로 남긴다 — 보수적으로.** 재계획의 「옮길 stop 은 `PLANNED`」, 재배정의 `stop-not-planned`, 취소의 「닿은 stop 은
   거부」(ADR-026 결정 2)는 stop 의 상태를 본다. 결정 2 의 표에서 `PLANNED` 는 「살아 있는 주문 누구도 닿지 않았다」이므로, 끝난 주문과 옮겨 온
   `PLANNED` 주문이 섞인 stop 은 `ARRIVED` — 옮기지도, 그 안의 주문을 떼지도 않는다. 기사가 그 지점에 닿은 뒤의 이동은 ADR-026 결정 2 가 막는
   부류다.
4. **카운터는 사건 단위 그대로다.** `dawnline_event_stale_total{consumer="dispatch", eventType="delivery.status"}` 는 사건의 주문 **어느 것도**
   적용되지 않았고 취소 뒤의 도착도 아닐 때 하나, `dawnline_scan_after_cancel_total` 은 취소된 주문이 **하나라도** 있으면 하나,
   `dawnline_status_after_relocate_total` 은 적용한 주문 중 **하나라도** 다른 라우트에 있으면 하나다. 주문 단위로 바꾸면 같은 이름의 값이 뜻을
   바꾼다(§9.1).

## 근거

- **둘째 출처를 없애는 것이 이 Phase 의 부류다.** stop 의 칸은 주문들의 사실을 요약할 뿐이다 — 요약을 사실처럼 따로 쓰면 둘은 갈라진다. 결정 2 는
  요약을 사실에서만 만든다.
- **쓰기 때 다시 센다(읽을 때가 아니라).** `UNFINISHED_STOP` 은 보존 배치 · 비활성화 409 · 재계획 대상 · 받는 쪽 잠금 네 자리가 쓰는 조각이고, 보존은
  계획 단위로 수만 행을 본다. 읽을 때마다 주문을 모으면 네 자리가 모두 집계가 된다. ADR-061 이 `rm_routes.completed_at` 에 대해 같은 판단을 했다.
- **가르는 경우가 저절로 맞는다.** 결정 1 에서 떼어 낸 주문의 행은 자기 상태를 들고 옮겨 간다(`moveOrder` 는 행의 `stop_id` 만 바꾼다). 사건이 오면
  그 행에 적히고, 그 stop 이 다시 세어진다.

## 고려한 대안과 기각 이유

| 대안 | 기각 이유 |
|---|---|
| 사건의 `orderIds` 가 stop 의 살아 있는 주문을 **전부** 덮을 때만 stop 을 옮긴다(ADR-047 ④ 의 처음 안) | 여전히 stop 하나의 칸이다 — 일부만 끝난 사건은 적을 곳이 없어 사실을 버린다(부재의 고객이 있는 건물) |
| stop 상태를 읽을 때 주문들에서 모은다(뷰) | 네 자리의 조각이 모두 집계가 된다 — 보존 배치가 계획 단위로 수만 행을 본다(ADR-061 과 같은 판단) |
| 갈라진 사건을 두 stop 에 **각각 stop 단위로** 적는다 | 각 stop 에 다른 주문이 섞여 있으면 1 · 2 의 결함이 그대로다 |
| 주문의 행에 `CANCELLED` 도 적는다 | 취소의 출처가 둘이 된다 — `dispatch_candidates` 하나로 둔다 |
| 재배정의 검사를 주문 단위로(그 주문이 `PLANNED` 면 뗀다) | 기사가 닿은 지점에서 주문을 떼는 일이다 — ADR-026 결정 2 가 취소에 대해 막은 부류. 필요해지면 연다 |

## 결과

- **장점**: 한 stop 의 주문들이 각자의 사실을 갖는다 — 위 셋이 모두 맞는다. V8(서비스 둘의 사실 대조)이 주문 단위로 맞을 수 있게 된다.
- **비용**: 사건 하나가 주문 수만큼의 행 갱신과 stop 다시 세기 하나가 된다(이전: stop 한 행). 주문은 stop 당 평균 1–2 라 작다(근거: 추정 —
  turbulent 실행의 dispatch 소비 랙이 본다). V12 의 백필은 닿은 stop 의 값을 그 주문들에 복사한다 — 주문별 사실은 이전에 저장된 적이 없어서
  **유일한 출처**다.
- **되돌리는 방법**: 서비스가 주문의 행을 쓰지 않고 `route_stops.status` 를 직접 쓰게 되돌린다. V12 의 두 칸은 남아도 읽는 쪽이 없다.

## 검증

| 표본 | 기대 | 결과 |
|---|---|---|
| `DeliveryFactPerOrderIT` — 한 stop 의 주문 하나만 끝난다 | stop 은 `ARRIVED`, 그 주문만 `COMPLETED` | 정정 전: stop `COMPLETED` — (구현 커밋에서 채운다) |
| 같은 IT — 옮겨 온 주문이 있는 stop 에서 원래 주문만 끝난다 | stop `ARRIVED`, 옮겨 온 주문은 `PLANNED` | 정정 전: stop `COMPLETED` — (구현 커밋에서 채운다) |
| 같은 IT — 가르는 동안 두 주문이 함께 배송된다 | 두 자리 모두 `COMPLETED` | 정정 전: 옮겨 간 자리 `PLANNED` — (구현 커밋에서 채운다) |
