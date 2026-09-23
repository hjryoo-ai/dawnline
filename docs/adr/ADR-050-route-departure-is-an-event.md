# ADR-050 — 라우트 출발은 이벤트다 — 출발이 첫 편차의 출처이기 때문이다

| 항목 | 내용 |
|---|---|
| 상태 | Accepted (계약은 묶음 B 에서 소비자가 정의한다) |
| 결정일 | 2026-09-23 |
| 관련 문서 | `docs/DESIGN.md` §4.1 · §5.4 · §5.5 · §8.1 · `contracts/events/` · `CLAUDE.md` 불변 규칙 4·8 |
| 관련 ADR | [ADR-024](ADR-024-plan-completed-event.md) (라우트 단위 사실로 웨이브 단위 사실을 말할 수 없다) · [ADR-046](ADR-046-at-risk-is-an-event.md) (같은 축 — 판정을 이벤트로 낸다) · [ADR-047](ADR-047-delivery-status-is-a-fact-not-a-revision.md) (`DEPARTED_CAMP` 만 라우트의 사건이라는 예외) · [ADR-048](ADR-048-replan-reads-its-own-db.md) (재검토 지점 1 이 이 결정을 가리킨다) |

---

## 맥락

Phase 5-1b 가 `DEPARTED_CAMP` 를 **라우트의 사건**으로 만들었다(§5.4). 기사는 캠프를 한 번
떠나고 그 순간 그 라우트의 배송 전부가 길 위에 있으므로, 스캔 하나가 stop 하나가 아니라 라우트
전체를 `OUT_FOR_DELIVERY` 로 옮긴다. 같은 자리에서 그 갈래는 **첫 편차의 출처**가 되었다 —
기준값은 `route_revisions.planned_departure`(= `route.assigned.v1` 의 `summary.plannedDeparture`,
required)이고, 늦은 출발은 가장 흔한 지연 원인이면서 **첫 `ARRIVED` 스캔 전에 이미 알 수 있다.**

그런데 그 사실은 tracking 밖으로 나가지 않는다. `ScanType.isPublished()` 가
`this != DEPARTED_CAMP` 이므로 `delivery.status.v1` 로 발행되지 않고, 다른 토픽도 없다
(§4.1). 이유는 옳았다 — 한 사실을 stop 수만큼 반복해 말하는 꼴이고, order-service 의 상태
머신은 `DISPATCHED` 로 그 구간을 이미 덮는다.

5-1b 는 그래서 판정을 미뤘다: **「운영자가 출발 사실을 화면에서 원하면 라우트 단위 이벤트
하나를 첫 소비자가 나타나는 Phase 6 에서 소비자 주도로 정한다.」** 그 소비자가 ops-api 이고,
여기가 그 판정이다.

[ADR-048](ADR-048-replan-reads-its-own-db.md) 재검토 지점 1 이 같은 자리를 다른 쪽에서
가리키고 있었다 — 출발 지연만으로 난 at-risk 는 dispatch 에 닿은 stop 이 없어 `no-anchor` 로
세어지고, **「그 구멍이 정상 경로면 dispatch 가 출발 사실을 아는 길이 값을 한다」**가 그
항목의 조건이었다.

## 결정

### 1. `dawnline.delivery.route-departed.v1` 을 정의한다 — 키는 `routeId`, 소비자는 ops

근거는 **ops 화면이 아니라 사실의 가시성**이다.

지금 출발을 아는 것은 tracking 뿐이다(위 문단 — `isPublished()` 로 소스에서 확인했다). 그러면
ops 는 첫 `ARRIVED` 가 올 때까지 **「출발 안 함」과 「출발했는데 아직 도착 없음」을 구별하지
못한다.** 두 상태는 운영자에게 정반대의 행동을 요구한다 — 아직 안 나간 차는 **다시 짤 수 있고**,
이미 나간 차는 재계획의 대상이 `relocate` 로 좁혀진다(§6.8). 그 구간이 정확히 **운영자가
개입할 수 있는 마지막 창**이고, 지금은 그 창이 화면에 없다.

> **근거: 추정.** 「ops 가 구별하지 못한다」의 구조적 부분 — tracking 이 그 사실을 내보내지
> 않는다 — 은 소스에서 확인했다(`ScanType.isPublished()`, §4.1 토픽 목록). 확인하지 <em>않은</em>
> 것은 그 구별이 실제 운영에서 얼마나 자주 값을 하는가다. ops-api 가 아직 없어 잴 대상이
> 없었고, 그 수치는 ADR-048 재검토 지점 1 의 `dawnline_replan_total{outcome=no-anchor}` 로
> 묶음 B 뒤에 처음 나온다.

두 번째 근거는 KPI 다. `plannedDeparture − departedAt` 은 라스트마일의 고전 지표(출발 정시율)
이고, peak-day 스토리에서 **「출발 지연 → at-risk → 재계획」의 첫 칸**이 된다. §8.1 의 정시율이
*도착*만 말하는 지금, 늦게 끝난 날의 원인이 출발이었는지 경로였는지를 나눌 값이 없다.

### 2. 라우트 하나에 이벤트 하나다 — stop 단위로 내보내지 않는다

5-1b 가 발행하지 않기로 한 이유는 **그대로 유효하다.** `DEPARTED_CAMP` 를 `delivery.status` 로
내보내면 한 사실이 stop 수만큼 반복되고, order-service 는 `DISPATCHED` 로 그 구간을 이미 덮는다.
그래서 새 토픽은 라우트 단위다.

그 이유가 이 이벤트를 *라우트 단위로 만든 것*이지, **이벤트를 만들지 않을 이유였던 적은 없다.**
둘을 섞으면 「반복하지 않는다」가 「말하지 않는다」로 읽힌다 — 5-1b 의 문장이 실제로 그렇게 읽힐
수 있었고, 이 ADR 이 그 자리를 갈라 둔다.

[ADR-024](ADR-024-plan-completed-event.md) 의 거울상이다. 거기서는 *라우트*
단위 사실로 *웨이브* 단위 사실을 말할 수 없어 토픽을 따로 세웠고, 여기서는 *stop* 단위 사실로
*라우트* 단위 사실을 말할 수 없어 토픽을 따로 세운다. 판단의 축이 같다 — **사실의 단위와 토픽의
단위를 맞춘다.**

### 3. 페이로드는 여섯 칸이고 tracking 은 **새 컬럼 없이** 낸다

`{routeId, campId, revision, plannedDeparture, departedAt, stopCount}`.

**(2026-09-24) 다섯 칸이 됐다 — `stopCount` 를 뺐다.** 재검토 지점 3 에서 닫았다. 아래 표의
마지막 행은 그 판정 전의 기록으로 남긴다.

| 칸 | 출처 | 확인 |
|---|---|---|
| `routeId` | 스캔의 라우트 | — |
| `campId` | `route_revisions.camp_id` | `V1__tracking.sql`, `JdbcRouteRevisions` |
| `revision` | `route_revisions.revision` | 같은 행 |
| `plannedDeparture` | `route_revisions.planned_departure` | `V2__route_planned_departure.sql` |
| `departedAt` | 스캔의 `occurredAt` | `RecordScanService` |
| `stopCount` | `shipments` 의 `COUNT(DISTINCT stop_seq)` | `ix_ship_route (route_id, stop_seq)` 가 선두 컬럼으로 받는다 |

**마이그레이션이 필요 없다는 것이 이 형태를 고른 조건**이었다. 새 컬럼을 요구하는 칸이 하나라도
있었으면 그 칸이 정말 필요한지부터 물었을 것이다.

`revision` 을 싣는 이유: 출발은 **어느 개정본의 계획에 대해서** 늦었는지를 말해야 한다.
재배정이 라우트를 바꾸면 `plannedDeparture` 도 바뀌고(§6.8 4단계), 개정 번호가 없으면 ops 는
어느 기준선과 견준 편차인지 알 수 없다 — [ADR-045](ADR-045-revision-comparison-is-per-route.md)
가 라우트마다 개정을 비교하게 한 것과 같은 이유다.

### 4. 스키마·예시·토픽 생성·발행은 **소비자가 먼저 정의한다** (묶음 B)

이 ADR 과 §4.1 의 행이 먼저 들어가고, `contracts/events/delivery.route-departed.v1.schema.json`
과 예시·토픽 목록·outbox 발행은 **ops 의 `rm_routes` 프로젝션 커밋에서** 함께 들어간다.
「부재는 첫 소비자가 나타나는 시점에 채운다」(§11)이고, 이 이벤트의 소비자는 ops 하나다.

그 사이 §4.1 의 행과 `contracts/events/` 는 **갈라져 있다.** 의도한 짧은 간격이고, 표의 칸이
스스로 말한다(「계약은 아직 없다」). 채워지는 순간의 어긋남은 사람이 보지 않아도 드러난다 —
`EventContractsTest` 의 `PARTITION_KEY_FIELD` 는 **예시 파일에서 역으로 돌기** 때문에
`delivery.route-departed` 예시가 들어오면 그 표에 칸이 없다는 이유로 실패한다(빼는 방식,
§13 규칙 2). `deploy/compose` 의 토픽 목록도 같은 커밋에서 는다.

## 기각한 것

- **정의하지 않는다 — `rm_routes` 프로젝션으로 충분하다.** 5-1b 가 열어 둔 쪽이고 **더 단순하다.**
  그 단순함의 대가가 **운영자에게서 마지막 개입 창을 숨기는 것**이라 취하지 않았다. `rm_routes` 는
  `route.assigned` 로 계획을 알고 `delivery.status` 로 도착부터 알지만, 그 둘 사이의 구간 —
  출발했는지 — 에 대해서는 **말할 값 자체가 없다.** 프로젝션을 아무리 잘 만들어도 없는 사실을
  만들어 내지는 못한다.
- **`DEPARTED_CAMP` 를 `delivery.status.v1` 의 `status` 에 더한다.** 계약은 같은 major 안에서
  값이 늘 수 있으므로(§4.7) 형식상 가능하다. 그러나 그 토픽의 페이로드는 **stop 의 사실**
  (`stopSeq`·`orderIds` required)이라 라우트 하나의 출발이 stop 수만큼 반복된다 — 5-1b 가
  발행하지 않기로 한 바로 그 이유이고, dispatch 는 그 반복을 전부 「철 지난 상태」로 세게 된다.
- **`route.assigned` 에 `departedAt` 을 나중에 채운다.** 그 이벤트는 **계획**이고 이것은
  **사실**이다([ADR-047](ADR-047-delivery-status-is-a-fact-not-a-revision.md) 결정 3 과 같은 축).
  계획 이벤트를 사실로 갱신하면 개정 번호로 거르는 소비자(§6.8 4단계)가 사실을 함께 버린다.
- **ops-api 가 tracking 에 동기 조회한다.** 불변규칙 4 가 허락하는 방향이긴 하다(ops → 코어).
  그러나 출발은 **사건**이지 조회 대상이 아니다 — 폴링으로 바꾸면 「언제 떠났나」의 해상도가
  폴링 주기가 되고, 그 주기가 개입 창보다 길면 이 결정이 사려던 것이 사라진다.
- **페이로드를 `{routeId, departedAt}` 둘로 줄인다.** 더 작지만 ops 가 나머지 넷을 자기
  프로젝션에서 조인해야 하고, 그중 `plannedDeparture` 는 **개정마다 다르다.** 편차를 내려면
  어느 개정과 견주는지가 같은 이벤트 안에 있어야 한다(결정 3).

## 결과

- §4.1 에 행 하나. 소비자는 **ops 뿐**이다 — dispatch 는 소비자가 아니다. 재계획이 이 사실을
  쓰게 되면 그때가 ADR-048 재검토 지점 1 의 두 번째 절반이고, 그 판단은 여기서 하지 않는다.
- tracking 에 outbox 발행 한 줄(묶음 B). `DEPARTED_CAMP` 처리에 이미 자리가 있다 —
  `RecordScanService` 가 `fromCamp` 로 갈라지는 지점이다.
- **`ScanType.isPublished()` 의 뜻이 좁아진다.** 지금은 「발행되는가」로 읽히지만 앞으로는
  「`delivery.status.v1` 로 발행되는가」다. 메서드 이름이 그 구별을 말하지 않으므로 묶음 B 에서
  자바독을 고치거나 이름을 좁힌다 — 고치지 않으면 다음 사람은 `DEPARTED_CAMP` 가 아무 데도
  안 나간다고 읽는다.
- **비용**: 토픽 하나, 파티션 12개, DLQ 하나. 발행량은 하루 라우트 수(peak 한 캠프 90 × 10 캠프)
  라 다른 토픽 대비 무시할 수준이다 — `delivery.status` 는 같은 날 stop 수만큼 나간다.
- **되돌리는 방법**: 소비자가 ops 하나뿐이므로 `rm_routes` 에서 그 칸을 빼고 발행을 끄면 된다.
  토픽은 남지만 `route:{id}:progress` 의 교훈대로 **쓰는 쪽만 남은 것을 유지하지 않는다** —
  그때는 §4.1 의 행도 함께 지운다.

## 재검토 지점

1. **출발 정시율이 실제로 갈리는가.** `plannedDeparture − departedAt` 이 늘 0 근처면 이
   이벤트가 여는 것은 「개입 창」뿐이고 KPI 쪽 근거는 값을 못 한 것이다. 반대로 그 값이 크고
   at-risk 와 상관이 높으면 §8.1 의 정시율을 **출발 기준으로도** 내는 것이 다음 칸이다.
2. **dispatch 가 소비자가 되는가.** ADR-048 재검토 지점 1 의 나머지 절반이다 — `no-anchor` 가
   잦으면 재계획이 출발 편차를 앵커로 쓸 수 있고, 그때 이 토픽의 소비자 목록이 는다.
   **지금 미리 넣지 않는 이유**는 소비자 없는 소비자 목록이 §7.2 의 `route:{id}:progress` 와
   같은 모양이기 때문이다(2026-09-23 에 그것을 지웠다).
3. **`stopCount` 가 정말 필요한가.** ops 는 같은 값을 `route.assigned` 프로젝션에서 그 개정본
   기준으로 이미 안다 — 실으면 **같은 사실의 둘째 출처**가 될 수 있다. 실어 두는 이유는 페이로드
   스냅샷이 자기 완결적이어야 한다는 쪽(불변규칙 4)이지만, 묶음 B 에서 소비자가 스키마를 쓸 때
   **쓰지 않는다고 판명되면 빼는 것이 맞다.** 이 줄이 그 판정의 자리다.

   **값을 할 이유가 하나 생겼다** (2026-09-23,
   [ADR-051](ADR-051-first-fact-creates-the-row-absence-is-not-a-value.md)). 프로젝션은
   **먼저 온 사실로 행을 만든다** — 즉 `route-departed` 가 그 라우트의 `route.assigned` 보다
   먼저 와서 `rm_routes` 행을 *만드는* 경우가 있고, 그때 `stopCount` 는 그 행이 가진 유일한
   규모 값이다. 그렇다고 **지금 닫지 않는다** — 프로젝션이 실제로 그 칸을 읽는지는 코드로
   보이며, 여기서는 「값을 할 이유가 생겼다」까지만 적는다.

   **닫았다 — 뺀다** (2026-09-24, 발행 전이라 비용이 없다). 코드가 보인 답: ops 의 프로젝션은
   이 칸을 읽지 않는다 — 「한 칸에 두 출처의 사실을 접지 않는다」(ADR-051 결정 2 의 적용) 아래서
   `rm_routes.stop_count` 의 출처는 `route.assigned` 하나다. 위의 「값을 할 이유」도 ADR-051 이
   이미 답한 상황이었다: 행은 먼저 온 사실이 만들고, stop 수는 `route.assigned` 가 몇 초 뒤
   채우며, 그때까지 화면은 「—」다. tracking 의 `COUNT(DISTINCT stop_seq)` 로 그 몇 초를 메우면
   **같은 사실의 둘째 출처**(진실은 dispatch 의 계획)가 되고, 그것은
   [ADR-048](ADR-048-replan-reads-its-own-db.md) 이 캐시를 지운 이유와 같다.
   원칙 한 줄: **부재를 다른 출처로 메우지 않는다.**
