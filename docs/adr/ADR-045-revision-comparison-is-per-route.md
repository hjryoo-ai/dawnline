# ADR-045 — 개정 번호는 라우트의 것이다

| 항목 | 내용 |
|---|---|
| 상태 | Accepted |
| 결정일 | 2026-09-19 |
| 관련 문서 | `docs/DESIGN.md` §5.4(tracking 스키마) · §6.8(부분 재계획) · §8.5(멱등성 지점) · §16 |
| 관련 ADR | [ADR-017](ADR-017-order-state-machine-absorbs-out-of-order-events.md) (순서 뒤바뀜은 상태 머신이 흡수한다), [ADR-026](ADR-026-dispatch-cancellation-window.md) (취소된 stop 은 페이로드에 남는다), [ADR-024](ADR-024-plan-completed-event.md) (재계획은 `plan.completed` 를 다시 내지 않는다), [ADR-015](ADR-015-outbox-publish-side-quarantine.md) (격리 행의 수동 재큐 — replay 경로) |
| 구현 | `services/tracking-service/src/main/resources/db/migration/V1__tracking.sql` (`route_revisions`) |

---

## 맥락

§6.8 은 부분 재계획의 결과를 「`route.assigned.v1` 에 `revision` 증가로 발행. tracking·ops는
revision이 낮은 이벤트를 무시(멱등)」로 적었고, §8.5 는 그 멱등 키를 **「routeId + revision」,
저장소 「DB (revision 비교)」** 로 적었다. 그런데 §5.4 의 DDL 에는 그 값을 둘 자리가 없었다 —
`shipments`(order_id PK)와 `shipment_events` 뿐이다.

Phase 5-1a 에서 그 자리를 만들면서 세 가지 형태를 놓고 골랐다. 셋 다 「낮은 revision 을 무시한다」는
같은 문장을 구현하지만, **무엇을 기준으로 낮은가**가 다르다.

`revision` 의 성질 둘이 판단의 전부다.

1. **라우트마다 독립이다.** 최초 확정이 1 이고 그 라우트가 재계획될 때마다 오른다
   (`routes.revision`, §6.8 4단계). 라우트 A 가 5 일 때 라우트 B 는 1 일 수 있고, 그 둘 사이에는
   순서 관계가 없다.
2. **같은 라우트 안에서는 Kafka 가 순서를 지킨다.** `route.assigned` 의 파티션 키는 `routeId`
   이므로(§4.5) 같은 라우트의 개정들은 한 파티션에 순서대로 들어간다. 순서가 뒤집히는 경로는
   **DLQ replay 와 재처리**뿐이다(§4.6, RB-05) — 그리고 그 경로는 이 시스템에 실재한다.

## 결정

**`route_revisions(route_id PK, revision, applied_at)` 에 라우트당 한 줄을 두고, 그 줄과 비교한다.**
들어온 `revision` 이 저장된 값보다 **크지 않으면** 이벤트를 적용하지 않는다.

```sql
CREATE TABLE route_revisions (route_id UUID PK, revision INTEGER NOT NULL CHECK (revision >= 1),
  applied_at TIMESTAMPTZ NOT NULL);
```

`processed_events`(불변규칙 2)와 **겹치는 것이 아니라 다른 것을 막는다** — 저쪽은 *같은 이벤트*의
재배달을, 이쪽은 *다른 이벤트*(옛 개정)의 뒤늦은 도착을 막는다. eventId 가 다르면
`processed_events` 는 통과시킨다.

## 기각한 대안

### (1) `shipments` 에 `route_revision` 컬럼을 두고 `MAX(...) WHERE route_id = ?` 로 유도

표를 하나 덜 만드는 가장 눈에 띄는 안이고, **그래서 이 ADR 이 있다** — 다음 사람이 가장 먼저
되돌리려 할 형태다.

실패 양식은 §6.8 의 `relocate` 와 DLQ replay 가 만난다.

> 라우트 A 가 at-risk 로 재계획되고, A 의 미완료 stop 이 **전부** 다른 라우트로 옮겨 간다.
> A 의 새 개정에는 stop 이 없고, A 를 가리키던 `shipments` 행은 전부 다른 `route_id` 를 갖게 된다.
> 이제 `MAX(route_revision) WHERE route_id = A` 는 **NULL** 이다.
> 며칠 뒤 운영자가 A 의 옛 `route.assigned`(revision 1)를 DLQ 에서 replay 한다. 비교할 값이 없으니
> 통과하고, **이미 옮겨간 주문들이 A 로 되돌아온다.** 기사는 B 를 들고 다니는데 tracking 은 A 다.

라우트가 비는 것이 드문 일이라는 것은 반론이 아니다 — 재계획의 트리거가 at-risk 이고(§6.8),
at-risk 는 **첫 배송 전에도** 난다. 완료 stop 이 하나도 없는 라우트가 통째로 흩어지는 것은
이 설계가 정상 동작으로 허용하는 경우다.

부수적으로, 유도는 O(1) 이 아니다. `ix_ship_route (route_id, stop_seq)` 를 타지만 라우트의 모든
행을 훑어 `MAX` 를 낸다 — 개정마다, stop 마다.

### (2) shipment 행마다 비교한다 (「이 주문에 마지막으로 적용된 revision 보다 큰가」)

표를 아예 만들지 않는 안이다. 성질 1 에 걸린다.

> 라우트 A(revision 5)의 주문 X 가 재계획으로 라우트 B(revision 2)로 옮겨 간다. B 의 이벤트가
> 싣고 오는 번호는 **2** 이고, X 의 행에 적힌 번호는 **5** 다. 행 단위 비교는 이 정당한 이동을
> **역행으로 읽고 버린다.** 주문은 영영 A 에 남는다.

번호를 라우트 밖에서 견주는 순간 그 번호는 순서를 뜻하지 않는다. 「크면 새것」이 성립하는 범위가
곧 비교의 범위여야 한다.

### (3) 비교하지 않고 언제나 마지막에 도착한 것을 적용한다

§6.8 4단계가 명시적으로 금지한다(「tracking·ops는 revision이 낮은 이벤트를 무시」). 그리고
replay 가 있는 시스템에서 「마지막에 도착한 것」은 「마지막에 일어난 것」이 아니다 —
[ADR-017](ADR-017-order-state-machine-absorbs-out-of-order-events.md) 이 order-service 에서 같은
구분을 했다.

### (4) `routes` 전체를 tracking 에 프로젝션한다

`route_revisions` 는 그 프로젝션의 **한 칸짜리 축소판**이다. 나머지 칸(차량·기사·요약)은 지금
tracking 이 쓰지 않고, 쓰게 되는 날 이 표를 넓히면 된다. 안 쓰는 칸을 미리 만들면 그 칸이 낡았는지
아무도 모른다.

## 결과와 재검토 지점

- **보존 정책이 없다.** `route_revisions` 는 `shipments` 와 같이 늘어나고, §7.1 의 보존 표에
  두 표 다 없다. 같은 성질이라 같은 자리에서 정하는 것이 맞고, 그 자리는 배송 이력의 보존을
  정하는 Phase 6(ops 읽기 모델)이다. **지금 적어 두는 이유는 「몰라서 없는 것」과 구별하기 위해서다.**
- **재검토 지점**: 한 주문이 여러 라우트에 동시에 실리는 설계(분할 배송)가 들어오면 이 비교의
  단위를 다시 본다. 지금은 `shipments.order_id` 가 PK 라 그런 일이 없다.
- 이 표를 읽지 않는 소비자는 영향이 없다. ops-api 는 자기 읽기 모델에서 같은 비교를 하되
  **자기 자리에서** 한다(불변규칙 3) — 이 표를 가로질러 읽지 않는다.
