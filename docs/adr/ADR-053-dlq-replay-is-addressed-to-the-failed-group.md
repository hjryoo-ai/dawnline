# ADR-053 — DLQ 재처리는 실패한 소비자 그룹에게만 의미 있는 사건이다

| 항목 | 내용 |
|---|---|
| 상태 | Accepted (2026-09-24) |
| 결정일 | 2026-09-24 |
| 관련 문서 | `docs/DESIGN.md` §4.2 · §4.4 · §4.6 · §5.5 · §9.1 · §13 · `docs/runbooks/RB-05-dlq-and-outbox-quarantine.md` · `CLAUDE.md` 불변 규칙 1 · 2 |
| 관련 ADR | [ADR-006](ADR-006-at-least-once-idempotent-consumer.md) (at-least-once + 멱등 소비자) · [ADR-015](ADR-015-outbox-publish-side-quarantine.md) (발행 측 실패 분류) · [ADR-017](ADR-017-order-state-machine-absorbs-out-of-order-events.md) · [ADR-051](ADR-051-first-fact-creates-the-row-absence-is-not-a-value.md) (순서를 흡수하는 축 규칙) · [ADR-052](ADR-052-delegation-client-is-generated-from-the-committed-contract.md) (감사 행 `PENDING` 선기록, 재검토 지점 4) |

---

## 맥락

§4.6 의 표에는 처음부터 한 줄이 있었다 — 「DLQ 재처리: ops-api `POST /admin/dlq/{topic}/replay`
(운영자 확인 후)」. Phase 6 묶음 B 가 그 줄을 채운다. 커맨드 위임([ADR-052](ADR-052-delegation-client-is-generated-from-the-committed-contract.md))과
달리 이것은 ops-api 가 **직접** 하는 일이고, 그래서 두 질문이 이 설계의 축이다.

1. **재처리한 이벤트가 원래 `eventId` 를 유지하는가** — 멱등 소비자가 중복을 막는 근거가 그 id 다.
2. **누가 눌렀는가** — 감사.

계획을 세우다가 §4.4 의 논거에서 빈 곳이 나왔다.

> `processed_events` 보존 14일. … DLQ 보존 30일은 이 창과 무관하다 — DLQ 에 들어간 이벤트는 처리
> 트랜잭션이 롤백된 것이므로 `processed_events` 에 성공 기록이 없고, replay 의 안전성이 이 테이블에
> 의존하지 않는다.

이 문장은 **실패한 소비자 그룹 하나**에 대해서만 참이다. 재처리는 원래 토픽으로 다시 발행되므로 그
토픽의 **모든** 그룹이 받는다. 그 이벤트를 이미 성공 처리한 다른 그룹은 `processed_events` 로만 중복을
거르고, 그 행은 14일 뒤 정리된다. 그래서 15~30일 된 DLQ 레코드를 재처리하면 그 그룹들에서 **같은 이벤트가
두 번 처리된다.** 근거: 관측(재현됨) — 설계서를 읽어서 찾았고, 필터를 뺀 통합 테스트에서 재현했다(아래
「관측」).

토픽마다 소비자 그룹은 여럿이다(§4.1 — `order.placed` 는 fulfillment·ops, `delivery.status` 는
order·dispatch·ops). 그룹 하나가 실패해 DLQ 로 보낸 레코드는 **그 그룹의 실패**이지 이벤트의 실패가 아니다.

## 결정

### 1. 원래 바이트를 그대로 다시 보낸다 — `eventId` 유지는 value 불변과 같은 말이다

`eventId` 는 헤더가 아니라 봉투, 즉 **value 바이트 안**에 있다(§4.2). 그러므로 「`eventId` 를 유지한다」는
「value 를 한 바이트도 바꾸지 않는다」와 같다. 재발행은 이렇다.

- 키와 value 는 **바이트 그대로**. 원래 토픽(`kafka_dlt-original-topic`)의 **원래 파티션**(`kafka_dlt-original-partition`)으로.
- 원래 헤더(`traceparent`·`eventType`·`schemaVersion`)는 유지하고, Spring 이 DLQ 적재 때 붙인 `kafka_dlt-*` 만 뺀다.
- 읽기와 쓰기 모두 `byte[]` 직렬화기다. **객체로 읽었다가 다시 쓰는 경로를 두지 않는다** — 그런 경로가
  있으면 필드 순서·숫자 표기·알 수 없는 필드가 조용히 바뀌고, 그 순간 「같은 이벤트」가 증명이 아니라 주장이 된다.

### 2. 재발행은 대상 그룹을 지목하고, 지목되지 않은 그룹은 기록 없이 건너뛴다

- 재발행 레코드에 헤더 **`dawnline-replay-for=<원래 소비자 그룹>`** 을 싣는다. 값은 `kafka_dlt-original-consumer-group` 에서 읽는다.
- 모든 서비스의 리스너 컨테이너에 `libs/messaging` 의 레코드 필터(`ReplayTargetFilter`)가 걸린다. 헤더가
  있고 값이 자기 그룹이 아니면 **리스너를 부르지 않는다** — 오프셋만 진행한다.
- **건너뛴 레코드는 `processed_events` 에 적지 않는다.** 그룹 X 가 `replay-for=Y` 레코드를 건너뛰며
  `(eventId, X)` 를 적으면, 나중에 같은 이벤트를 **X 대상으로** 재처리할 때 `dup` 으로 막힌다. 흔적은
  카운터 하나다 — `dawnline_event_processed_total{outcome="replay_not_target"}`. `dup` 과 섞지 않는다:
  `dup` 은 「이미 처리했다」이고 이것은 「내 일이 아니다」다.
- **원래 그룹 헤더가 없는 DLQ 레코드는 재처리하지 않는다**(감사 `REJECTED`). Spring Kafka 는 그 헤더를
  리스너 실패(`ListenerExecutionFailedException`)일 때만 붙인다(4.1.1 바이트코드로 확인). 대상을 모르는
  재처리는 모든 그룹에게 가고, 그것이 이 결정이 막는 바로 그 모양이다.

이것이 §4.4 의 논거를 **참으로 만든다.** 재처리가 대상 그룹에게만 의미 있는 사건이면 다른 그룹의
`processed_events` 보존은 무관해지고, 대상 그룹은 롤백됐으므로 그 이벤트의 처리 행이 구조적으로 없다.

### 3. 불변규칙 1 은 해당하지 않는다 — **이 발행의 상태는 감사 행이다**

재처리는 도메인 상태를 바꾸지 않는다. 이미 발행된 바이트를 다시 보낼 뿐이다. 그래서 outbox 를 거치지 않고
ops-api 의 `adapter.out.messaging` 이 `KafkaTemplate<byte[], byte[]>` 로 직접 보낸다. 유스케이스는 포트만
안다(ArchUnit 규칙 6 은 그대로 성립한다).

**outbox 가 없는 발행이 허용되는 이유는 「상태가 없어서」가 아니라 「상태가 다른 곳에 있어서」다.** 이
발행의 상태는 `audit_logs` 의 행이다. `PENDING` 을 커밋한 **뒤에** 보내고, 브로커의 ack 로 닫는다 — outbox
행이 「발행할 것이 있다」를 먼저 적고 릴레이가 `published_at` 으로 닫는 것과 같은 순서다. 둘 사이에 죽으면
행은 `PENDING` 으로 남고, 그것이 「보냈는지 모른다」의 흔적이다.

outbox 를 거치는 안은 기각했다(아래 대안). 릴레이는 봉투를 **다시 조립**하고(§4.6 발행 측), 조립하지 못하는
바이트는 격리한다 — 재처리 대상은 대개 소비자가 열지 못한 바이트다.

### 4. 감사는 레코드 하나에 한 행, 그리고 재처리 커맨드는 멱등이다

- 행 하나 = DLQ 레코드 하나. `action=DLQ_REPLAY`, `target_type=EVENT`, `target_id=eventId`(value 에서 읽지
  못하면 `NULL` — V1 이 허용한다), `request={topic, partition, offset, targetGroup}`. `actor` 는 토큰의 `sub`.
  그래서 「이 이벤트를 누가 재처리했나」를 `eventId` 로 찾는다.
- 결과: 브로커 ack → `SUCCEEDED`. 보내기 전에 ops-api 가 거절(레코드 없음·원래 그룹 헤더 없음·원래 토픽
  불일치) → `REJECTED`. 브로커가 돌려준 재시도 불가 오류 → `FAILED`(쓰이지 않은 것이 확실하다). 그 밖 전부
  (타임아웃, 재시도 가능 오류의 소진, 분류할 수 없는 예외) → `UNKNOWN`. 분류는 §4.6 발행 측 표의
  `PublishFailureClassifier` 를 그대로 쓴다 — 「결정적」은 쓰이지 않았다는 확신이고, 「일시적」은 모른다는 뜻이다.
- **`PENDING` 과 ack 사이에서 죽으면 그대로 다시 눌러도 된다.** 대상 그룹이 첫 번째를 처리했으면 그 그룹의
  `processed_events` 가 두 번째를 `dup` 으로 막고, 다른 그룹은 두 번 다 건너뛴다. 그래서 `UNKNOWN` 과 오래된
  `PENDING` 인 `DLQ_REPLAY` 행을 닫는 방법은 **다시 누르기**다(RB-05). 이것이 [ADR-052](ADR-052-delegation-client-is-generated-from-the-committed-contract.md)
  재검토 지점 4(`UNKNOWN` 의 자동 해소)의 **첫 사례**다 — 해소가 필요 없는 이유가 멱등이다.

### 5. 재처리는 정의상 순서를 어긴다 — 흡수하는 것은 소비자다

재발행된 레코드는 파티션 **끝**에 붙는다. 같은 키의 더 새 이벤트가 이미 지나갔다면 그 뒤에 도착한다.
§4.5 의 키 단위 순서는 재처리에 성립하지 않는다. 그것을 흡수하는 것은 소비자 다섯 자리의 축 규칙
(역행 무시·판정 키 — [ADR-017](ADR-017-order-state-machine-absorbs-out-of-order-events.md) 계열과
[ADR-051](ADR-051-first-fact-creates-the-row-absence-is-not-a-value.md))이지 **재처리가 순서를 지키는 것이 아니다.**
ops 프로젝션은 이것을 테스트로 본다 — 「사건 하나가 DLQ 로 빠지고 나머지가 흐른 뒤 그 사건만 맨 끝에 온다」를
모든 사건에 대해 돈다(`ProjectionShuffleTest`·`ProjectionShuffleIT` 의 재처리 순서).

### 6. 표면

- `GET /api/v1/admin/dlq/{topic}` — 목록. 칸은 위치(파티션·오프셋)·시각·`eventType`·`eventId`·원래 그룹·예외
  클래스뿐이고, **value 도 예외 메시지도 싣지 않는다**(§9.3 — 둘 다 주소를 담을 수 있다).
- `POST /api/v1/admin/dlq/{topic}/replay` `{records:[{partition, offset}]}` — 운영자가 목록에서 **고른 것만**.
  이것이 §4.6 의 「운영자 확인 후」다. 한 번에 100개까지. 답은 레코드마다 감사 id 와 결과다.
- `{topic}` 은 원래 토픽이고 **계약에 있는 토픽만** 받는다 — ops-api 가 구독하는 열한 토픽이 곧 계약 목록이다
  (`ProjectionTopicsTest` 가 계약 디렉터리와 대조한다). 그 밖은 404 이고 감사하지 않는다 — 아무것도 시도하지 않았다.
- 권한은 메서드 규칙 그대로다. `GET` 은 `OPS_VIEWER`, `POST` 는 `OPS_OPERATOR` 이상.

## 관측 (2026-09-24, `DlqReplayIT` — 실제 Kafka 4.3.1 · PostgreSQL 18)

DLQ 레코드는 운영과 같은 `DeadLetterPublishingRecoverer` 로 만들고(헤더 모양이 같다), 한 경우는 실제 리스너 실패로
넣었다 — 그 레코드의 `originalGroup` 이 `ops-api` 로 나와, Spring 이 헤더에 적는 그룹과 필터가 비교하는 그룹이 같은
출처임을 확인했다. 7개 경우 모두 통과. 그리고 음성 표본 셋(각각 되돌렸다):

| 되돌린 것 | 빨개진 검사 | 뜻 |
|---|---|---|
| 필터를 뺀다(`record -> false`) | 「보존이 지나 `processed_events` 가 지워진 뒤에도 …」 — `expected: 0 but was: 1` · 「다른 그룹을 지목한 재처리는 건너뛰고 …」 | **맥락의 결함이 재현됐다** — ops-api 가 성공 처리했던 이벤트를, 그 행이 정리된 뒤 fulfillment 대상 재처리에서 두 번째로 처리했다 |
| 건너뛴 뒤 `(eventId, ops-api)` 가 적힌 상태를 만든다 | 「… 나중에 자기를 지목하면 처리한다」 — `expected: "PLACED" but was: null` | 결정 2 의 「기록하지 않는다」를 어기면 자기 대상 재처리가 `dup` 으로 막힌다 |
| 어댑터가 value 를 열어 새 `eventId` 로 다시 조립한다(outbox 식 재조립) | 바이트 대조(축 1) · 「다시 눌러도 한 번만」 · 「나중에 자기를 지목하면」 | 재조립하면 누를 때마다 다른 이벤트가 되고 멱등이 사라진다 — 결정 1 이 그 경로를 두지 않는 이유 |

순서(결정 5)는 따로 쟀다. 프로젝션 시나리오의 사건 31개 중 **씨 25회 셔플(IT)에서 한 번이라도 맨 끝에 온 것은 18개**,
씨 300회(단위)는 31개 전부였다 — 그러나 둘 다 「나머지는 인과 순서, 그 사건만 맨 끝」이라는 재처리의 모양은 아니었다.
그래서 그 모양을 모든 사건에 대해 결정적으로 돈다(30개 — 마지막 사건을 끝으로 옮기면 인과 순서 그대로라 뺀다). 판정을
「마지막에 온 것이 이긴다」로 바꾸자 첫 사건(`order.placed O1`)을 끝으로 옮긴 경우에서 `DISPATCHED ≠ PLACED` 로 빨개졌다.

## 고려한 대안과 기각 이유

- **(A) 나이 상한 — 14일보다 오래된 DLQ 레코드는 재처리를 거부한다.** 라이브러리를 바꾸지 않는다. 그러나
  문제를 푸는 것이 아니라 DLQ 보존을 사실상 14일로 줄이는 것이고, 두 보존 기간을 묶는 조건이 하나 더 생긴다
  — `processed_events` 보존을 바꾸는 사람이 DLQ 재처리의 상한까지 알아야 한다.
- **outbox 를 거쳐 재발행** — 불변규칙 1 의 모양은 맞추지만 릴레이가 봉투를 다시 조립한다. 조립은
  §4.6 발행 측의 결정적 실패 경로이고, 소비자가 열지 못한 바이트는 거기서 격리된다. 그리고 그 경로는
  value 를 객체로 읽었다 쓰는 경로다(결정 1).
- **건너뛰기를 `IdempotentConsumer` 안에서** — 그 API 는 봉투와 `eventId` 를 받고 레코드 헤더를 모른다.
  다섯 서비스의 리스너가 헤더를 옮겨 적어야 하고, 한 곳이라도 빠지면 그 리스너만 조용히 전 그룹 재처리가 된다.
  컨테이너의 필터는 리스너 코드를 거치지 않는다. 그리고 게이트 **앞**이라 `processed_events` 를 만질 길이 없다.
- **건너뛴 레코드를 `dup` 으로 센다** — 결정 2. 「이미 처리했다」와 「내 일이 아니다」는 다른 사실이고,
  `dup` 이 늘었다는 알림이 재처리 때문인지 재전달 때문인지를 가릴 수 없게 된다.
- **대상 그룹을 모르면 전 그룹에 보낸다** — 결정 2. 그것이 이 ADR 이 막는 모양이다.
- **콘솔 프로듀서로 원래 토픽에 되돌리는 수동 절차 유지** — 대상 헤더가 없다. RB-05 에서 지웠다.

## 결과

**장점**
- `processed_events` 14일과 DLQ 30일이 서로를 모른다. 둘 중 하나를 바꾸는 사람이 다른 하나를 알 필요가 없다.
- 「누가 무엇을 재처리했나」가 `eventId` 로 한 줄에 나온다.
- 재처리 커맨드가 멱등이라 `UNKNOWN` 의 해소가 사람의 판단이 아니라 버튼이다.

**비용**
- `libs/messaging` 이 바뀌어 **다섯 서비스 모두**의 소비 경로에 필터 하나가 선다. 헤더가 없는 레코드에는
  헤더 조회 한 번이 비용의 전부다.
- 헤더가 하나 는다(§4.2). 봉투의 중복이 아니라 **배달의 사실**이라 봉투에는 넣을 수 없다 — value 를 바꾸면
  결정 1 이 깨진다.
- 필터가 없는 옛 배포가 섞이면 그 서비스는 지목되지 않은 재처리도 처리한다(이전과 같은 동작). 이 저장소는
  전 서비스를 함께 배포한다.

**되돌리는 방법**: 필터 빈을 빼면 모든 그룹이 재처리를 받는 이전 동작이다. 그때는 (A) 나이 상한을 함께 넣어야
§4.4 가 다시 참이 된다.

## 재검토 지점

1. **소비자 그룹 이름이 서비스 이름과 달라지면** — 지금은 다섯 서비스 모두 `group-id` = 서비스 이름이고 필터는
   컨테이너가 알려 주는 그룹 id(`KafkaUtils.getConsumerGroupId()`)와 헤더를 비교한다. Spring 이 DLQ 헤더에
   적는 값과 같은 출처라 이름 규칙에는 기대지 않지만, 한 서비스가 한 토픽을 두 그룹으로 읽기 시작하면 「실패한
   그룹」과 「처리할 그룹」이 같은지 다시 본다.
2. **Spring Kafka 를 올릴 때** — 원래 그룹 헤더가 붙는 조건(리스너 실패일 때만)이 바뀌었는지 본다. 헤더가 없는
   레코드는 거절되므로 조용히 틀리지는 않지만, 재처리할 수 없는 레코드가 는다.
