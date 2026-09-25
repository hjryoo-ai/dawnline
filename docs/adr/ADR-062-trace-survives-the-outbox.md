# ADR-062 — outbox 를 지나도 트레이스는 이어진다: 릴레이는 저장된 traceparent 를 부모로 발행한다

| 항목 | 내용 |
|---|---|
| 상태 | Accepted (2026-09-25) |
| 결정일 | 2026-09-25 |
| 관련 문서 | `docs/DESIGN.md` §9.2 · §9.3 · §13 축 13 · `docs/IMPLEMENTATION_PLAN.md` 7-2 · A21 |
| 관련 ADR | [ADR-060](ADR-060-metrics-come-from-the-table.md) (의존 방향 — `libs/messaging` → `libs/observability`) · [ADR-027](ADR-027-outbox-relay-leader-lock.md) (릴레이) |

---

## 맥락

§9.2 는 「Kafka 헤더로 `traceparent` 전파」를 적었고 Phase 2 가 outbox 행의 `headers` 에 그 칸을 두었다. 7-2 의 첫 검사
(`OutboxTraceparentIT` — 요청 스팬 안에서 outbox 에 쓰고, 발행된 레코드의 traceId 가 같은지 본다)가 그 한 줄이 **한 번도
살아 있지 않았다**는 것을 두 자리에서 보였다.

1. **행의 `traceparent` 가 비어 있었다.** `TraceparentSupplier` 의 빈은 기본값 `NONE` 하나뿐이었다. 그 인터페이스의 문서는
   「`libs/observability` 가 실제 구현을 등록하면 그쪽이 이긴다」고 했지만 등록은 없었고, ADR-060 이 의존 방향을
   `libs/messaging` → `libs/observability` 로 정한 뒤로는 있을 수도 없었다. 기본값이 기능을 조용히 껐다. 근거: 관측(재현됨).
2. **행에 값을 실어도 발행된 traceId 가 달랐다**(저장 `117c…` → 발행 `e418…`). 템플릿 관측(§9.2 가 켠다)의 헤더 세터는
   `traceparent` 를 **지우고** 현재 컨텍스트로 다시 쓴다(`KafkaRecordSenderContext` — spring-kafka 4.1.1 바이트코드에서
   `Headers.remove` 뒤 `add`). 발행은 릴레이의 `@Scheduled` 폴링 안에서 일어나고, 그 폴링은 스스로 관측되어 자기 트레이스를
   갖는다. 근거: 관측(재현됨).
3. **`management.tracing.export.enabled=false` 는 전파까지 끈다.** Boot 4.1 의 W3C 전파기 빈이
   `@ConditionalOnEnabledTracingExport` 라서 그 키가 거짓이면 전파기가 no-op 이 된다(전파기의 `fields()` 가 빈 목록).
   `docker-compose.lean.yml` 은 「스팬 수집은 하되 OTLP 전송을 안 한다」고 적고 이 키를 썼다 — lean 스택에서는 서비스마다
   트레이스가 끊겼다. 근거: 관측(재현됨) — 그 키로 켠 IT 에서 전파기가 아무것도 주입하지 않았다.
4. **§9.2 의 「하나의 주문 traceId 로 order → fulfillment → dispatch → tracking」은 이벤트 흐름상 성립하지 않는다.** 계획은
   웨이브 마감(fulfillment 의 스케줄러)이 시작하고 주문 여럿을 모은다 — 주문의 트레이스는 dispatch 의 후보 적재에서 끝나고,
   tracking 은 계획의 트레이스(`route.assigned`)에만 나온다. 근거: 추정(리스너 표에서 읽었다 — Compose 스모크가 재현한다).

## 결정

### 1. 쓰는 쪽 — 행의 `traceparent` 는 현재 스팬에서

`TracerTraceparentSupplier` 가 현재 스팬의 컨텍스트를 Micrometer Tracing 의 전파기로 주입한 값을 준다. 손으로
`00-<trace>-<span>-<flags>` 를 조립하지 않는다 — 형식과 샘플링 플래그는 전파기가 안다(`propagation.produce: W3C`).

- 구현은 `libs/messaging` 의 `com.dawnline.messaging.tracing` 에 있다. 트레이싱 타입을 참조하는 코드는 그 패키지 하나이고
  `micrometer-tracing` 은 compileOnly 다 — 서비스에는 `spring-boot-starter-opentelemetry` 가 싣고 도구에는 없다.
- 자동 설정은 **클래스 조건으로만** 가른다: 트레이싱 클래스가 있으면 그 구현, 없을 때만 `NONE`. 트레이서 빈은 만들 때
  찾는다 — `@ConditionalOnBean` 은 자동 설정의 순서에 기대고, 그 순서가 어긋나면 다시 `NONE` 이 조용히 이긴다.

### 2. 보내는 쪽 — 릴레이는 저장된 `traceparent` 를 부모로 하는 수신 관측 안에서 보낸다

`KafkaRecordPublisher` 가 행의 헤더를 운반체로 하는 `ReceiverContext` 관측(`outbox relay`)을 열고 그 안에서 템플릿을 부른다.
수신 쪽 처리기가 운반체에서 부모를 꺼내고, 템플릿의 발행 스팬이 그 자식이 되어 **같은 traceId** 를 헤더에 다시 쓴다.

- 수신 문맥인 이유: outbox 표는 릴레이가 읽어 가는 큐다. 부모를 현재 스레드가 아니라 운반체에서 꺼내는 것이 수신 처리기의 일이다.
- 저장된 값이 없으면(트레이싱을 끈 배포가 쓴 행) 새 트레이스로 시작한다.
- 부수 효과: 관측 하나가 미터 `outbox_relay_seconds` 를 만든다. `dawnline_*` 밖이라 §9.1 카탈로그의 대상이 아니다(ADR-060 은
  `dawnline_*` 의 이름이 한 곳에서 나오게 한 것이다).

### 3. 받는 쪽 — Boot 가 만든 리스너 팩토리의 관측이 헤더를 부모로 잇는다

서비스는 리스너 컨테이너 팩토리를 만들지 않는다(`MessagingKafkaAutoConfiguration`) — Boot 의 팩토리에
`spring.kafka.listener.observation-enabled: true`(observability-defaults)가 걸리고, 소비 스팬이 레코드의 `traceparent` 를 부모로
삼는다. 리스너 안에서 쓴 outbox 행은 결정 1 로 그 소비 스팬을 싣는다 — 한 줄의 양쪽 끝이 이어진다.

### 4. 주문의 트레이스와 계획의 트레이스는 스팬 속성 `dawnline.wave_id` 로 잇는다

맥락 4 의 두 트레이스를 하나로 만들지 않는다(대안 B). 대신 **둘 다 같은 속성을 단다**: 주문 트레이스의 끝(dispatch 의
후보 적재 — `fulfillment.planned` 소비)과 계획 트레이스의 시작(dispatch 의 계획 — `wave.closed` 소비, 리스너가 계획 하나를
그 스레드에서 돈다)이 `dawnline.wave_id` 를 갖는다. 그러면 「`waveId` 로 이어 찾는다」는 문장이 아니라 TraceQL 한 줄이다:

```
{ span.dawnline.wave_id = "<waveId>" }
```

- 속성은 MDC 와 **같은 자리에서** 단다 — `MdcScope` 가 MDC 에 넣는 id(`orderId` · `waveId` · `routeId`)를 현재 스팬에도
  단다. 이름은 MDC 키에서 규칙으로 나온다: `dawnline.` + snake_case(`waveId` → `dawnline.wave_id`). 두 이름을 따로 적지
  않는다 — 로그와 트레이스가 같은 id 를 다른 이름으로 부르게 되는 첫 걸음이 그것이다.
- `MdcScope` 는 트레이싱이 없는 소비자(도구)도 쓰므로 OpenTelemetry API(`Span.current()`)가 클래스패스에 있을 때만 단다.

### 5. DoD 는 그 질의로 본다 — Compose 스모크

`make obs-check` 가 데모의 웨이브 하나로 위 질의를 Tempo API 에 묻고, **결과 트레이스들의 `service.name` 합집합에 코어 넷**
(order · fulfillment · dispatch · tracking)이 있는지 본다. 서비스 이름으로 검색해 트레이스를 하나씩 여는 것보다 검사의 뜻이
정확하다 — 「이 웨이브의 일이 네 서비스를 지나 한 질의로 찾아진다」가 §9.2 의 요구다.

### 6. lean 은 OTLP 익스포터만 끈다

`MANAGEMENT_TRACING_EXPORT_OTLP_ENABLED=false`. 맥락 3 의 키는 쓰지 않는다.

## 고려한 대안과 기각 이유

| 대안 | 기각 이유 |
|---|---|
| **A** — 릴레이의 템플릿 관측을 끄고 저장된 헤더를 그대로 싣는다 | 발행 스팬이 사라져 릴레이 지연이 트레이스에서 안 보인다 |
| **B** — 계획 스팬이 웨이브의 주문 트레이스마다 span link 를 건다 | 피크 웨이브는 주문이 수천이라 링크도 수천이다. 찾는 방향(웨이브 → 주문)은 속성 질의 하나로 같은 답을 준다 |
| **C** — 관측 대신 트레이서 스코프(`tracer.withSpan`)로 부모를 연다 | 템플릿 관측의 부모는 현재 **관측**(릴레이 폴링)이 먼저다 — 트레이서 스코프는 그 아래에 가려질 수 있다. 근거: 추정(Micrometer 의 부모 선택 규칙을 읽었다, 재지 않았다). 관측으로 열면 그 질문이 없다 |
| **D** — 제공자를 `libs/observability` 에 둔다(원래 문서) | 인터페이스가 `libs/messaging` 에 있고 의존은 그 반대 방향이다(ADR-060). 맥락 1 이 그 결과다 |
| **E** — 서비스 이름으로 검색해 트레이스를 하나씩 연다(스모크) | 「네 서비스가 한 트레이스에 있다」를 보지만 그것은 §9.2 의 요구가 아니다 — 주문과 계획은 두 트레이스다. 결정 5 가 요구 그대로를 묻는다 |

## 결과

- 한 주문의 일은 두 트레이스다 — 주문(`POST /orders` → fulfillment → dispatch 후보 적재 · order · ops-api)과 계획(웨이브 마감 →
  dispatch 계획 → tracking · order · fulfillment · ops-api). Grafana 에서는 TraceQL 한 줄로 함께 나온다.
- 모든 outbox 이벤트가 쓴 트랜잭션의 트레이스로 나간다 — DLQ 로 간 레코드도 실패한 소비의 트레이스를 잇는다(리커버러가 소비
  스팬 안에서 보낸다).
- lean 스택에서도 전파가 산다.

## 재검토 지점

- 배치 리스너가 들어올 때 — 리스너 관측은 레코드 단위라 배치에는 걸리지 않는다. 그때는 레코드마다 부모를 여는 자리가 따로 필요하다.
- 샘플링을 1.0 아래로 내릴 때(`DAWNLINE_TRACE_SAMPLE_RATE`, §8.2 피크) — 부모 기반 샘플링이라 한 트레이스는 함께 남거나 함께
  빠지지만, 주문과 계획은 두 트레이스라 **따로** 뽑힌다. 결정 5 의 질의가 한쪽만 돌려줄 수 있다.
