# ADR-054 — 웨이브 조기 마감은 운영자가 컷오프를 앞당기는 결정이다

| 항목 | 내용 |
|---|---|
| 상태 | Accepted (2026-09-24) |
| 결정일 | 2026-09-24 |
| 관련 문서 | `docs/DESIGN.md` §5.2 · §5.5 · §9.1 · `contracts/openapi/fulfillment-service.yaml` · §9.5 RB-07 |
| 관련 ADR | [ADR-020](ADR-020-cutoff-ownership-wave-grace-promise-revision.md) (컷오프 소유권 · grace · 약속 개정) · [ADR-025](ADR-025-wave-admission-share-lock.md) (편입 공유 락 · 마감 배타 락) · [ADR-028](ADR-028-unassigned-policy.md) (파생값 옆에 근거를 저장한다) · [ADR-052](ADR-052-delegation-client-is-generated-from-the-committed-contract.md) (위임 · 감사 `UNKNOWN`) |

---

## 맥락

Phase 6 DoD 의 첫 문장이 「운영자가 UI 에서 웨이브를 조기 마감한다」다. fulfillment 에는 REST 표면이
없었고, 이것이 그 서비스의 첫 운영 엔드포인트다(작업 2).

웨이브는 `(campId, tier, cutoffAt)` 당 하나이고 컷오프는 order-service 가 접수 시점에 정한다(ADR-020).
그러면 **컷오프 전에 웨이브를 닫는 순간 무슨 일이 생기는지는 이미 정해져 있다.** 닫힌 뒤에 같은
`cutoffAt` 을 들고 온 주문은 편입이 `FOR SHARE` 로 상태를 보고(ADR-025) 다음 컷오프로 밀리며,
`fulfillment.planned` 에 개정된 창과 `promiseRevised: true` 가 실린다(ADR-020 결정 3).

order-service 는 웨이브가 닫혔다는 것을 모르므로 컷오프까지 남은 시간 동안 **닫힌 웨이브의 창을
계속 약속한다.** 그 동안 접수된 주문은 전부 약속을 받자마자 개정된다. 이것이 조기 마감의
대가다. 근거: 관측(재현됨) — `WaveEarlyCloseIT` 가 수동 마감 뒤 같은 `cutoffAt` 의 주문이 다음
웨이브로 가서 `promiseRevised` 와 `cause="manual"` 로 세어지는 것을 본다.

## 결정

### 1. 컷오프 전 마감을 허용한다 — 늦은 주문은 이미 있는 개정 경로를 탄다

이 기능의 뜻은 「운영자가 컷오프를 앞당긴다」다. 그 대가를 재는 축은 **이미 두 개 있다**:
개정 횟수(`dawnline_promise_revised_total`)와 원 약속 기준 정시율(`on_time_promised`, §8.1).
약속은 말없이 깨지지 않는다 — 개정된 창이 고객에게 되돌아가고(ADR-020 결정 3), SLO 는 원 약속으로
잰다. 새 경로도 새 이벤트도 필요 없다.

### 2. `reason` 은 필수다 — 코어 계약에서부터

컷오프 전 마감은 **남은 시간 동안의 모든 접수가 약속을 받자마자 개정되는 결정**이다. 감사 행에 「왜」가
없으면 `UNKNOWN` 을 닫는 사람도, 다음 날 개정 급증을 조사하는 사람도 근거를 볼 수 없다. 파괴적 운영
커맨드의 표준이고, ops-web 확인 화면의 문구(「남은 N분 동안 접수되는 주문은 약속이 개정됩니다」)가 이
필드를 채우는 자리다.

필수 표시는 **코어 계약(`CloseWaveRequest.reason`, 공백 불가 · 200자)** 에 둔다. ops-api 의 위임
클라이언트는 그 문서에서 생성되므로(ADR-052) 필수가 코드에 그대로 들어온다. fulfillment 는 그 값을
저장하지 않고 **마감 로그 한 줄에 싣는다** — 코어 로그의 `auditId`(§9.3) 옆에 이유가 있어야 RB-07 의
해소가 한 화면에서 끝난다. 길이 상한은 취소 사유와 같은 이유(자유 텍스트에 개인정보가 섞일 여지를
줄인다)로 같은 값이다.

### 3. 마감 원인은 파생하지 않고 저장한다 — `waves.close_cause`

`SCHEDULED | MANUAL` 한 칸(V3). `closed_at` 과 **함께 채워지고 함께 비어 있다**(CHECK).

파생 — `closedAt < cutoffAt + grace` 면 수동이다 — 은 **「스케줄러는 그 전에 닫지 않는다」는 동작과
`grace` 설정값에 기대는 숨은 의존**이다. grace 를 바꾸면 과거 판정이 움직이고, 스케줄러가 한 주기
늦게 돈 날은 그 차이만큼 판정이 흔들린다. 근거: 추정 — 판정식이 설정값을 읽는다는 사실에서 따라
나오고, 잰 것이 아니다. ADR-028 이 우선도의 근거(`promise_revised`)를 파생값 옆에 저장한 것과 같은
이유다: **「왜 이 값인가」는 그때의 사실로 답해야 한다.**

백필: V3 이전에 닫힌 웨이브는 전부 `SCHEDULED` 다 — 수동 경로가 없었다.

### 4. `promise_revised_total` 에 `cause` 라벨 — 그 칸에서

개정을 부른 것은 **주문의 원래 `cutoffAt` 의 웨이브**가 닫혀 있었다는 사실이고, 라벨 값은 그 웨이브의
`close_cause` 다.

| `cause` | 뜻 | 늘면 |
|---|---|---|
| `scheduled` | grace 로 흡수하지 못한 지연(ADR-020 의 원래 의미) | grace 를 늘릴 것이 아니라 지연의 원인을 본다 |
| `manual` | 운영자가 앞당긴 컷오프의 대가 | 결정이 낳은 값이다 — 감사 행의 `reason` 을 본다 |

라벨이 없으면 운영자가 누른 결과가 「grace 가 모자라다」로 읽힌다. 원래 라벨(`camp`·`tier`)만 보던
합계는 그대로다.

### 5. 마감 본문은 하나다 — 스케줄러와 수동이 같은 코드를 부른다

`WaveClosing` 이 `FOR UPDATE` → `OPEN` 확인 → 집계 → `CLOSED` → `wave.closed` outbox 를 한 번에 한다.
스케줄러(`CloseDueWavesService`)와 수동 경로(`CloseWaveService`)는 원인만 다르게 넘긴다. 두 벌이면
「운영자가 닫으면 되는데 자동은 안 된다」 같은 차이가 생긴다(dispatch `PlanController` 의 같은 판단).

**수동 경로는 Redis 락을 잡지 않는다.** 중복 마감을 막는 것은 `FOR UPDATE` 와 상태 전이이고, 락은
동시에 시작하는 낭비를 줄일 뿐이다(§5.2 「세 겹」). 사람이 누른 요청을 「다른 인스턴스가 처리 중」으로
돌려보낼 이유가 없다 — 먼저 잡은 쪽이 닫고 뒤는 409 를 받는다.

게이지(`dawnline_wave_orders`)는 **커밋 뒤에** 갱신한다. 본문이 옮겨 오며 순서를 바로잡았다(CLAUDE.md
「카운터는 커밋 뒤에 센다」).

### 6. 표면 — `POST /api/v1/waves/{waveId}/close`

| 응답 | 뜻 |
|---|---|
| 200 `WaveView` | 닫았다. `closeCause=MANUAL` |
| 400 `validation-failed` | `reason` 이 비었거나 200자를 넘는다 |
| 404 `not-found` | 없는 웨이브 |
| 409 `wave-not-open` | 이미 닫혀 있다. `currentState`·`closeCause`·`closedAt` 을 싣는다 |

**409 가 `UNKNOWN` 해소의 근거다.** 응답을 못 받은 마감을 다시 누르면 409 가 오고, `closeCause=MANUAL`
이면 앞의 요청이 적용된 것이다(`SCHEDULED` 면 스케줄러가 먼저 닫았다). 재처리와 달리 이 커맨드는 멱등이
아니다 — 두 번째 요청은 적용되지 않고 **말한다.** 그래서 RB-07 의 「코어 상태를 보고 닫는다」가 응답
하나로 끝난다.

## 고려한 대안과 기각 이유

| 대안 | 기각 이유 |
|---|---|
| `cutoffAt` 이후에만 허용(사실상 grace 생략) | 벌 수 있는 시간이 최대 90초 — DoD 의 「조기 마감」과 다른 기능이다 |
| 원인을 `closedAt < cutoffAt + grace` 로 파생 | 결정 3 — 설정값과 스케줄러 동작에 기대는 숨은 의존, grace 를 바꾸면 과거가 움직인다 |
| `reason` 을 선택으로 | 결정 2 — 가장 필요한 순간(`UNKNOWN` 해소 · 개정 급증 조사)에 빈칸이다 |
| 수동 마감 뒤 같은 컷오프의 둘째 웨이브를 연다 | 자연키 `(campId, tier, cutoffAt)` 이 깨지고, dispatch 는 웨이브 하나에 계획 하나다(§5.3 `wave_id` UNIQUE) |
| 수동 경로도 Redis 락을 잡는다 | 결정 5 — 정확성에 기여하지 않고, 스케줄러와 겹친 순간 사람의 요청이 이유 없이 거절된다 |
| 마감 코드를 수동 경로에 따로 둔다 | 결정 5 — 두 벌은 갈라진다 |

## 결과

- **장점**: 대가가 이미 있는 두 축으로 드러나고, 그중 하나(개정 횟수)는 원인까지 가른다. 409 가
  `UNKNOWN` 을 응답 하나로 닫는다. 마감 코드가 하나로 모였다.
- **비용**: 조기 마감 뒤 컷오프까지 접수되는 주문은 약속을 받자마자 개정된다 — order-service 가 닫힌
  웨이브를 모르기 때문이다. 마이그레이션 하나(V3)와 라벨 하나가 는다.
- **되돌리는 방법**: 엔드포인트를 지우면 스케줄러 경로만 남는다. `close_cause` 는 그대로 두어도 무해하다
  (모든 행이 `SCHEDULED`).

## 재검토 지점

1. **`cause="manual"` 이 일상이 되면** 「접수 시점에 이미 닫힌 창을 약속하지 않는다」를 연다 —
   order-service 가 닫힌 웨이브를 알아야 하고, 그것은 `wave.closed` 의 새 소비자다(§4.1 변경). 지금
   열지 않는 이유는 조기 마감이 드문 결정이라는 가정이고, 그 가정이 틀렸는지는 이 라벨이 말한다.
2. **`reason` 을 fulfillment 에도 저장해야 하는 날** — 감사 행은 ops-api 에 있고 90일 뒤 웨이브 행이
   지워지므로(ADR-023) 지금은 둘째 사본을 두지 않는다. 코어만 보고 조사해야 하는 경우가 생기면 연다.
