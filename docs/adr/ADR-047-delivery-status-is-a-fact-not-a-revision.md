# ADR-047 — 배송 상태는 사실이고 개정은 계획이다

| 항목 | 내용 |
|---|---|
| 상태 | Accepted |
| 결정일 | 2026-09-22 |
| 관련 문서 | `docs/DESIGN.md` §4.1(소비자 목록) · §5.1(축 규칙) · §5.3(dispatch 스키마) · §6.8(부분 재계획) · §6.10(취소 창) · §7.2(Redis 키) · §9.1 · §16 |
| 관련 ADR | [ADR-017](ADR-017-order-state-machine-absorbs-out-of-order-events.md) (순서 뒤바뀜은 상태 머신이 흡수한다 — 이 ADR 의 원형), [ADR-026](ADR-026-dispatch-cancellation-window.md) (취소 창의 dispatch 쪽 끝), [ADR-045](ADR-045-revision-comparison-is-per-route.md) (개정 번호는 라우트의 것이다) |
| 구현 | `services/dispatch-service/.../adapter/in/messaging/DeliveryStatusListener.java` · `application/RecordDeliveryStatusService.java` |

---

## 맥락

축 규칙([ADR-017](ADR-017-order-state-machine-absorbs-out-of-order-events.md))은 이 저장소에
이미 세 자리를 갖고 있다.

| 자리 | 축 | `CANCELLED` 의 위치 | 특징 |
|---|---|---|---|
| order `orders.status` | `PLACED→PLANNED→DISPATCHED→DELIVERED/FAILED` | **축 밖** | 축 밖에 두는 이유가 「경합 창의 크기를 재기 위해」다(§5.1) |
| fulfillment | 웨이브 편입 축 | 축 안 | 취소가 편입을 되돌리는 정식 전이다 |
| tracking `shipments.status` | `SCHEDULED→OUT_FOR_DELIVERY→COMPLETED/FAILED` | 축 밖, **먼저 묻는다** | `CANCELLED` 면 스캔을 보기 전에 무시하고 센다(`dawnline_scan_after_cancel_total`) |

**네 번째 자리가 dispatch 의 `route_stops.status` 다.** Phase 5-5 가 `delivery.status` 를 소비해
`PLANNED → ARRIVED → COMPLETED/FAILED` 로 옮기면서 생긴다. 같은 규칙을 네 번째로 적용하는
일이므로 ADR 이 새 규칙을 만들지 않는다 — **이 자리에서만 새로 갈리는 두 분기**를 정한다.

그리고 이 자리에는 다른 셋에 없는 조건이 하나 있다. `route_stops` 는 상태 기록이 아니라
**계획의 일부**다. §6.8 의 부분 재계획이 「미완료 stop 만」 다시 푸는 근거가 이 컬럼이고,
§6.10 의 넷째 분기가 취소를 거부하는 근거도 이 컬럼이다. 여기에 잘못 적힌 값은 지표를 틀리게
하는 데서 끝나지 않고 **다음 계획을 틀리게 한다.**

## 결정

### 1. stop 은 `seq` 가 아니라 **주문**으로 찾는다

`delivery.status.v1` 은 `routeId` · `stopSeq` · `orderIds` 를 required 로 들고 온다. 셋 중
**`orderIds`** 로 찾는다(`RouteMutations.findAssignedStop`).

`seq` 는 라우트 <em>개정본</em>의 좌표다. §6.8 의 `relocate` 는 stop 을 다른 라우트로 옮기고,
§6.10 의 취소는 stop 을 죽인 채 번호를 남긴다. 그래서 같은 `seq` 가 개정에 따라 다른 지점을
가리킬 수 있다. 주문 id 는 개정을 가로질러 같은 것을 가리킨다 — tracking 이 `shipments.order_id`
를 PK 로 두고 배송 단위로 판단하는 것, sim-runner 의 기사가 「끝낸 stop」을 `seq` 가 아니라
주문으로 기억하는 것과 **같은 축**이다.

찾지 못하면(이 라우트에 그 주문이 없으면) 적용하지 않고 `dawnline_event_stale_total`
`{consumer="dispatch", eventType="delivery.status"}` 를 올린다.

### 2. 개정 번호로 거르지 **않는다**

[ADR-045](ADR-045-revision-comparison-is-per-route.md) 는 `route.assigned` 를 「저장된 revision
보다 크지 않으면 버린다」로 정했다. **`delivery.status` 에는 그 규칙을 옮기지 않는다.**

두 이벤트가 나르는 것이 다르기 때문이다.

| | 나르는 것 | 옛 것이 도착하면 |
|---|---|---|
| `route.assigned` | **계획** — 「이 라우트는 이렇게 돌아야 한다」 | 이미 갈아탄 계획을 되돌린다. 버려야 한다 |
| `delivery.status` | **사실** — 「기사가 이 주문에 갔다」 | 사실은 여전히 참이다. 버리면 일어난 일이 사라진다 |

ADR-017 의 문장이 그대로 적용된다: **「사실은 이미 일어났고, 순서가 다른 것은 우리가 알게 된
순서일 뿐이다.」**

구체적인 실패 양식은 이렇다.

> 라우트 A(revision 1)의 기사가 stop 1·2·3 을 완료한다. 그 사이 at-risk 가 나 A 가 재계획되고
> revision 이 2 가 된다. stop 1–3 의 `delivery.status` 가 (DLQ replay 로, 또는 컨슈머 랙으로)
> 개정 뒤에 도착한다. 「revision 1 < 2 이므로 버린다」면 **dispatch 는 완료된 stop 셋을 모른
> 채로 남는다** — 그리고 그것이 §6.8 의 「미완료 stop 만」이 읽는 바로 그 값이다. 다음
> 재계획이 이미 배송된 세 지점을 다시 배정한다.

즉 개정 비교를 넣으면 이 ADR 이 채우려던 결손을 **같은 크기로 다시 만든다.** 결정 1 의
주문 기반 조회가 개정을 견디는 방식이고, 개정 번호는 필요하지 않다.

> **근거: 추정.** 위 양식을 재현해 보지 않았다. 재현하려면 재계획(Phase 5-3)이 있어야 하고
> 그것은 아직 없다. 「revision 을 실어 비교한다」를 <em>구현한 뒤</em> 깨지는 것을 본 것이
> 아니라, 두 이벤트가 나르는 것의 차이에서 연역했다. 5-3 이 들어온 뒤 이 양식을 IT 로
> 만드는 것이 재검토 지점이다.

부수적으로 이 결정은 **계약을 바꾸지 않는다.** `delivery.status.v1` 에 `revision` 을 더하려면
`contracts/events/README.md` §5 의 required 예외 조건 셋을 다시 만족시켜야 하는데, 그 값이
쓰이지 않으므로 조건이 성립하지 않는다.

### 3. `CANCELLED` 인 stop 에 도착한 상태는 무시하고 센다

tracking 의 자리와 같은 형태다 — **취소를 먼저 묻는다.** 다른 점은 <em>왜</em> 취소가 먼저냐다.
tracking 에서 `CANCELLED` 는 배송의 종결이고, dispatch 에서 `CANCELLED` 는 **계획에서 뺐다**는
뜻이다. 그 stop 은 §6.10 이 이미 시각을 재전파하고 새 개정을 발행한 자리이고, 여기에 뒤늦게
`COMPLETED` 를 적으면 이미 나간 개정과 저장된 계획이 어긋난다.

세는 이름은 tracking 과 같은 `dawnline_scan_after_cancel_total` 이고 **자리(`job`)로 갈린다.**
두 값이 한 쌍인 이유는 둘이 갈릴 수 있기 때문이다 — tracking 의 shipment 가 아직 `CANCELLED`
가 아닌데 dispatch 의 stop 은 취소된 순간이 있고(개정이 tracking 에 닿기 전), 그 창에서만
dispatch 쪽이 오른다. **한쪽만 오르는 것이 정보다.**

### 4. 축의 나머지는 ADR-017 그대로

| 도착한 상태 | 현재 stop 상태 | 처리 |
|---|---|---|
| `ARRIVED` | `PLANNED` | 전이 |
| `COMPLETED`·`FAILED` | `PLANNED` | **전이** — 건너뜀은 정식이다(§5.1) |
| `COMPLETED`·`FAILED` | `ARRIVED` | 전이 |
| `ARRIVED` | `ARRIVED`·`COMPLETED`·`FAILED` | stale |
| `COMPLETED`·`FAILED` | `COMPLETED`·`FAILED` | stale |
| 무엇이든 | `CANCELLED` | 결정 3 |
| 그 주문의 stop 이 이 라우트에 없다 | — | 결정 1 |

`FAILED` 도 **종결**이다. 기사가 갔고 배송이 끝났으므로 §6.8 이 다시 배정하지 않는다.

### 5. 이 ADR 이 §6.10 넷째 분기를 연다 — 코드를 더하지 않고

§6.10 의 넷째 행(「stop 이 `ARRIVED`/`COMPLETED` 이후에 도착한 취소는 거부」)과
`dawnline_cancel_too_late_total` 은 **이미 구현돼 있다**(`CancelOrderService`,
`RouteMutations.AssignedStop.visited()`). 발화하지 않은 이유는 조건이 틀려서가 아니라
`route_stops.status` 가 `ARRIVED` 에 닿은 적이 없어서다. 이 ADR 의 전이가 그 조건을 처음으로
참이 될 수 있게 한다.

그래서 이 ADR 은 §6.10 의 「구조적으로 0」 문단을 **닫는다.** 0 이 계속되면 이제 그것은
미구현이 아니라 관측이다.

## 기각한 대안

### (1) `stopSeq` 로 찾는다

계약이 그 값을 들고 오고 조회가 한 번이라 가장 짧은 코드다. `(route_id, seq)` 에 UNIQUE 도
있다. 결정 1 의 이유로 기각한다 — **개정이 `seq` 의 뜻을 바꾼다.** 다만 `seq` 를 버리지는
않는다: 주문으로 찾은 stop 의 `seq` 가 페이로드와 다르면 `debug` 로 남긴다. 없어도 되는
정보지만, 있으면 「개정이 언제 끼어들었나」를 로그만으로 잴 수 있다.

### (2) `delivery.status.v1` 에 `revision` 을 더하고 ADR-045 를 그대로 옮긴다

가장 대칭적으로 보이는 안이고, **그래서 이 항목이 있다.** 결정 2 의 실패 양식으로 기각한다.
대칭은 이름의 대칭이지 의미의 대칭이 아니었다 — 두 이벤트가 나르는 것이 계획과 사실로 다르다.

### (3) `CANCELLED` 인 stop 에 `COMPLETED` 가 오면 `COMPLETED` 로 옮긴다

「물건은 실제로 배송됐다」는 반론이 있고 그 말은 맞다. 그러나 그 사실을 들고 있어야 하는 곳은
`orders.status`(order-service 도 같은 이벤트를 소비한다)와 tracking 의 `shipments` 이지,
**계획 테이블이 아니다.** `route_stops` 를 배송 원장으로 쓰기 시작하면 §6.8 이 읽는 값과
§6.10 이 읽는 값이 서로 다른 질문의 답이 된다.

### (4) 전이를 stop 애그리거트 메서드로 만든다 (불변규칙 6)

`route_stops` 에는 애그리거트가 없다 — 라우트는 120 stop 까지 가고 `RouteMutations` 는
「애그리거트를 되살리지 않는다」를 명시한 포트다(§5.3 운영자 재배정). 전이 규칙은
**`RouteStopTransition`(도메인의 순수 함수)** 에 두고 어댑터가 그 판정만 받아 한 행을 쓴다.
규칙이 코드 한 곳에 있다는 불변규칙 6 의 목적은 지켜지고, 120행을 메모리로 올리는 대가는
치르지 않는다. **그 대신 이 결정은 ArchUnit 이 닿지 않는 자리다** — §13 매핑표에 적는다.

## 결과와 재검토 지점

- **`route_stops.status` 에 `FAILED` 가 는다.** V1 의 컬럼 주석은 네 값만 적고 있었다
  (`PLANNED|CANCELLED|ARRIVED|COMPLETED`). 머지된 V 스크립트는 주석 한 글자도 고치지 않으므로
  (불변규칙 13) 새 `V8` 이 `COMMENT ON COLUMN` 으로 다시 적는다. `CHECK` 제약은 없었고 더하지도
  않는다 — 같은 major 안에서 `delivery.status.v1` 의 enum 은 값이 늘 수 있고(§4.7), 제약을 걸면
  값이 하나 늘 때마다 마이그레이션이 필요해진다. 대신 **모르는 값은 무시하고 센다.**
- **`route:{id}:progress` 는 불변규칙 7 의 대상이다.** Redis 가 없어도 `route_stops` 에서
  재구성된다(§7.2 의 「DB 조회」). 첫 소비자는 §6.8 의 부분 재계획(Phase 5-3)이고, 5-5 는
  채우는 쪽과 폴백만 만든다.
- **재검토 지점 ①**: 5-3 이 들어오면 결정 2 의 실패 양식을 IT 로 만든다 — 지금 그것은 추정이다.
- **재검토 지점 ②**: 한 주문이 여러 라우트에 실리는 설계(분할 배송)가 들어오면 결정 1 의
  조회 단위를 다시 본다. ADR-045 의 같은 재검토 지점과 짝이다.
- **재검토 지점 ③**: `dawnline_scan_after_cancel_total` 의 두 자리가 <em>같은 비율로</em> 오르면
  결정 3 의 창(개정이 tracking 에 닿기 전)이 사라졌다는 뜻이 아니라 둘 다 같은 원인을 보고
  있다는 뜻이다. 그때는 라벨이 아니라 이름을 갈라야 한다.
