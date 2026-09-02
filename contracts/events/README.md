# contracts/events — 이벤트 계약

Dawnline 의 Kafka 이벤트는 **코드보다 계약이 먼저**다 (CLAUDE.md 불변규칙 8).
새 이벤트나 새 필드는 여기 스키마와 `examples/` 를 먼저 고치고, 계약 테스트를 추가한 다음 코드를 바꾼다.

근거 문서: `docs/DESIGN.md` §4.1(토픽) · §4.2(봉투) · §4.3(페이로드) · §4.5(파티셔닝) · §4.7(진화 규칙).

---

## 1. 파일 규칙

| 종류 | 경로 | 규칙 |
|---|---|---|
| 봉투 스키마 | `envelope.v1.schema.json` | 모든 토픽 공통 |
| 페이로드 스키마 | `<eventType>.v<major>.schema.json` | 토픽 `dawnline.<eventType>.v<major>` 와 1:1 |
| 예시 | `examples/<eventType>.v<major>[.<variant>].example.json` | 스키마당 최소 1개 |

예시 파일명 → 스키마 매핑 정규식 (계약 테스트가 그대로 쓸 것):

```
^(?<eventType>[a-z][a-z0-9-]*(?:\.[a-z][a-z0-9-]*)+)\.v(?<major>\d+)(?:\.(?<variant>[a-z0-9-]+))?\.example\.json$
  → 스키마 파일 = <eventType>.v<major>.schema.json
```

`variant` 는 같은 이벤트의 다른 분기를 담는다. 현재 `fulfillment.planned.v1.unserviceable.example.json` 하나뿐이다.

### 현재 있는 것 / 아직 없는 것

Phase 0 에서는 §4.3 에 페이로드 구조가 명시된 4종만 만들었다.

| 토픽 | 스키마 | 추가 시점 |
|---|---|---|
| `dawnline.order.placed.v1` | O | Phase 0 |
| `dawnline.fulfillment.planned.v1` | O | Phase 0 |
| `dawnline.wave.closed.v1` | O | Phase 0 |
| `dawnline.route.assigned.v1` | O | Phase 0 |
| `dawnline.order.cancelled.v1` | O | Phase 1 (order-service — 발행자) |
| `dawnline.order.dispatched.v1` | O | Phase 1 (order-service — **소비자 주도**), 발행은 Phase 3 |
| `dawnline.delivery.status.v1` | O | Phase 1 (order-service — **소비자 주도**), 발행은 Phase 5 |
| `dawnline.plan.failed.v1` | X | Phase 3 (dispatch-service) |
| `dawnline.delivery.at-risk.v1` | X | Phase 5 (tracking-service) |

없는 스키마를 추측으로 미리 만들지 않는다. §4.3 에 페이로드가 정의되어 있지 않은 것을 지금 만들면
서비스 구현 시점에 반드시 틀린다. 해당 Phase 에서 **스키마를 먼저** 추가하고 이 표를 갱신한다.

### 예외: 소비자가 먼저 정의하는 계약 (consumer-driven)

`order.dispatched` 와 `delivery.status` 는 order-service 가 **소비**하는데, 발행자는 각각
Phase 3·5 에나 생긴다. 그렇다고 리스너를 그때까지 미루면 §5.1 상태 머신의 `PLANNED` 이후 전이가
Phase 1 에서 검증되지 않는다.

그래서 이 둘은 **소비자가 계약을 먼저 정의한다**. 추측이 아니다 — 요구사항을 가진 쪽이 소비자이기
때문이다. order-service 는 상태 전이에 필요한 최소 필드만 적고, 그것이 곧 발행자가 지켜야 할 계약이
된다. Phase 3·5 의 발행자는 이 계약을 만족시키되, 자기에게 필요한 필드는 §4.7 의 규칙대로
**추가만** 한다(같은 major 안에서 필드 추가는 소비자를 깨지 않는다).

리스너 통합 테스트는 예시 이벤트를 Testcontainers Kafka 에 직접 발행해 돌린다. 발행자 서비스가
없어도 완결되며, `make demo` 에서 이 리스너들이 실제로 발화하는 것은 Phase 3 이후다.

---

## 2. 예시 파일은 "봉투로 감싼 Kafka 레코드 value 전체"다

`examples/*.json` 은 페이로드 단독이 아니라 **컨슈머가 실제로 받는 레코드 value 전체**다.

이유:

1. §4.7 의 계약 테스트는 "소비자는 `examples/*.json` 으로 역직렬화 테스트"라고 정한다. 소비자가 역직렬화하는 것은 봉투부터다. 페이로드만 저장하면 `EventEnvelope` 역직렬화 경로가 테스트되지 않는다.
2. 봉투 ↔ 페이로드 사이의 규칙(§4.5 파티션 키, eventType/schemaVersion 일치)을 예시 하나로 함께 회귀 검증할 수 있다.
3. 페이로드만 필요하면 `example["payload"]` 를 꺼내면 되므로 잃는 것이 없다. 반대 방향(페이로드만 저장 → 봉투 복원)은 불가능하다.

예시 5개는 **하나의 시나리오**로 이어져 있다 — 같은 주문(`01a04dad-…16b0`)이 접수되고(`order.placed`), 강남 캠프의 DAWN 웨이브에 편입되고(`fulfillment.planned`), 컷오프에 웨이브가 닫히고(`wave.closed`), 라우트 1번 stop 으로 배정된다(`route.assigned`).
`order.placed` → `fulfillment.planned` 는 `traceId` 가 같다(원인 전파). `wave.closed` → `route.assigned` 는 스케줄러가 시작한 별도 트레이스다. 부록 B 의 "traceId 한 줄 추적" 데모가 이 구조를 그대로 쓴다.

좌표는 부록 A 의 서울 근사 범위(위도 37.45–37.65, 경도 126.85–127.15) 안에 있고, `geohash7` 은 손으로 적은 값이 아니라 좌표를 실제로 인코딩한 값이다.

---

## 3. 계약 테스트가 검사해야 할 것

`libs/messaging` 의 계약 검증 유틸이 다음을 수행한다.

**발행자 측(publisher)** — outbox 에 넣기 직전 또는 발행 단위 테스트에서:
1. 봉투 전체를 `envelope.v1.schema.json` 으로 검증.
2. `payload` 를 `<eventType>.v<schemaVersion>.schema.json` 으로 검증.

**소비자 측(consumer)** — `examples/*.json` 으로:
3. 각 예시를 실제 `EventEnvelope` + 페이로드 record 로 역직렬화. Jackson 3(`tools.jackson.*`)은 `FAIL_ON_UNKNOWN_PROPERTIES=false` 여야 한다 (§4.7).
4. 위 1·2 를 예시에도 적용.

**스키마로 표현하지 않는 불변식** — 테스트 코드가 직접 검사한다:

| 검사 | 대상 | 왜 스키마가 아닌가 |
|---|---|---|
| `eventType` == 파일명의 eventType, `schemaVersion` == v\<major\> | 모든 예시 | 봉투 스키마는 토픽을 모른다 |
| `partitionKey` == payload 의 키 필드 (§4.1: order/fulfillment→`orderId`, wave→`campId`, route→`routeId`) | 모든 예시 | 키 필드가 이벤트마다 다르다 |
| `promisedWindow.start` < `end` | order.placed, fulfillment.planned | JSON Schema 로 필드 간 비교 불가 |
| `summary.stopCount` == `stops` 길이 | route.assigned | 위와 동일 |
| `stops[].seq` 가 1..n 연속 | route.assigned | 위와 동일 |
| 한 주문이 두 stop 에 중복 배정되지 않음 | route.assigned | 위와 동일 |
| `address.geohash7` == `Geohash.encode(lat, lng, 7)` | order.placed, fulfillment.planned | 파생값 일치는 계산이 필요하다 |

> **`format` 은 기본값이 주석(annotation)이다.** JSON Schema 2020-12 에서 `format` 은 검증기가 명시적으로 켜야 강제된다. 계약 테스트는 `date-time`/`uuid` 를 **assertion 으로 켠 상태**로 검증해야 한다. 끄면 `"placedAt": "어제"` 도 통과한다.

---

## 4. 결정 사항과 근거

### 4.1 `additionalProperties` 는 모든 오브젝트에서 `true` — 스키마를 닫지 않는다

모든 스키마에 `"additionalProperties": true` 를 **명시적으로** 적었다. 생략해도 같은 뜻이지만, "빼먹은 것"이 아니라 "고른 것"임을 남기기 위해서다.

닫지 않은 이유:

1. **§4.7 을 지키려면 닫을 수 없다.** 같은 major 안에서는 필드 추가가 허용된다. v1.1 발행자가 필드를 하나 추가하면, 아직 배포되지 않은 v1.0 소비자가 가진 스키마는 그 메시지를 **거부**한다. 진화를 허용하는 규칙과 진화를 막는 스키마를 동시에 둘 수는 없다.
2. **§4.6 에서 스키마 불일치는 즉시 DLQ + 알림이다.** 닫힌 스키마는 "무해한 필드 추가"를 "운영자 호출"로 바꾼다. 배포 순서만 어긋나도 장애가 되는 계약은 나쁜 계약이다.
3. **닫힌 스키마가 잡는 결함은 실제로는 다른 데서 잡힌다.** 발행자는 `record` 를 직렬화하므로 필드 이름은 컴파일러가 고정한다. 오타는 스키마가 아니라 타입 시스템이 막는다. 정작 위험한 것 — 필수 필드 누락, 타입 오류, enum 이탈, 좌표/금액 범위 — 은 `required` · `type` · `enum` · `pattern` · `minimum` 이 전부 잡는다.

그래도 발행자 측에서 "스키마에 없는 필드"를 경고하고 싶다면, 계약 파일을 닫지 말고 **테스트 하네스가** 생성된 JSON 의 키 집합과 스키마 `properties` 를 비교해 경고하면 된다. 엄격성은 테스트의 성질이지 계약의 성질이 아니다.

### 4.2 `traceId` 는 required 가 아니다

봉투에 정의는 하되 `required` 에서 뺐다. `traceId` 는 관측용 필드이고, 관측용 필드가 없다는 이유로 이벤트가 §4.6 규칙에 따라 DLQ 로 가면 안 된다. 트레이싱 미설정·샘플링 경로·부팅 직후 릴레이 재발행처럼 트레이스 컨텍스트가 비어 있을 수 있는 경로가 실재한다.
**있을 때는 W3C trace-id 형식(소문자 hex 32자)을 강제한다.** 없는 것은 허용하고, 이상한 것은 거부한다.

반대로 `partitionKey` 는 required 다. 없으면 §4.5 의 순서 보장이 무너지고, 이는 관측이 아니라 정확성 문제다.

### 4.3 UUIDv7 은 `eventId` 에만 강제한다

`envelope.eventId` 만 버전 nibble 까지 검사한다(`…-7xxx-[89ab]xxx-…`).
`eventId` 는 `processed_events` 의 멱등 키이자 시간순 정렬 키다(§4.4). v4 가 섞이면 인덱스 지역성이 무너지고 불변규칙 10 위반이다. 그리고 `eventId` 는 예외 없이 우리 코드(`Ids.newId()`)가 만든다 — 강제해도 깨질 외부 입력이 없다.

페이로드의 ID(`orderId`, `customerId`, …)는 UUID 형태만 검사한다. `customerId` 는 외부에서 들어오고, 벤치마크·시뮬레이터 픽스처가 임의 UUID 를 쓸 수 있다. 계약이 테스트 픽스처의 생성 방식까지 규정할 이유는 없다.

> 계약 테스트나 픽스처에서 봉투를 만들 때 `UUID.randomUUID()`(v4)를 쓰면 봉투 검증이 실패한다. `Ids.newId()` 를 써라. 이건 버그가 아니라 의도된 게이트다.

### 4.4 enum 은 값 집합이 설계서에 확정된 곳에만 쓴다

| 필드 | 형태 | 이유 |
|---|---|---|
| `serviceTier` | enum (`DAWN`/`SAME_DAY`/`NEXT_DAY`) | §2.2 에 확정 |
| `outcome` | enum (`PLANNED`/`UNSERVICEABLE`) | §5.2 6단계에 확정 |
| `producer` | pattern | 서비스가 늘 수 있고, 틀린 값이 미치는 영향은 라우팅이 아니라 관측뿐 |
| `strategy` | pattern | §6.6 "새 전략 추가는 인터페이스 구현 + 등록만으로 가능해야 한다" — enum 이면 계약 변경이 추가로 필요해진다 |
| `reason` | 문자열 | 아래 4.5 |

enum 값 추가는 같은 major 안에서 허용된다(§4.7). 다만 **소비자가 모르는 enum 값을 받았을 때 죽지 않도록** 하는 것은 소비자 코드의 책임이다.

### 4.5 `fulfillment.planned` 에 `reason` 을 추가했다

§5.2 6단계는 "주문 서비스는 이를 받아 상태 `FAILED`, **사유 기록**"이라고 정한다. 사유가 이벤트에 실려 있지 않으면 order-service 는 사유를 기록할 방법이 없다(불변규칙 4: 코어 서비스 간 동기 호출 금지).
그래서 `outcome=UNSERVICEABLE` 일 때 `reason` 을 필수로 만들었다. §4.3 의 필드 목록에 명시되지 않은 유일한 추가 필드다.

값은 enum 이 아니라 문자열이다. §5.2 의 필터 1~4 단계에서 파생되는 코드를 **권장 어휘**로 둔다:

| 값 | §5.2 단계 |
|---|---|
| `NO_FC_FOR_TIER` | 1. 티어를 지원하는 FC 없음 |
| `NO_COLD_FC` | 2. 냉장 필요한데 `supports_cold` FC 없음 |
| `OUT_OF_STOCK` | 3. 재고 부족 |
| `NO_ZONE_MATCH` | 4. geohash5 → 권역 매핑 실패 |
| `NO_ACTIVE_CAMP` | 4~5. 권역은 있으나 활성 캠프 없음 |

fulfillment-service 구현 시 이 목록이 확정되면 enum 으로 좁히는 것을 검토한다(같은 major 안에서 문자열 → enum 은 **축소**이므로 v2 가 필요하다. 좁히려면 그 전에 결정해야 한다).

### 4.6 페이로드에 넣지 않은 것

- **계획 결정 시각** — `fulfillment.planned` 에 `plannedAt` 같은 필드를 두지 않았다. 봉투의 `occurredAt` 이 정확히 그 값이다(§4.2: 발행 시각이 아니라 **사건 시각**). 같은 값을 두 군데 두면 반드시 어긋난다.
- **고객 이름·연락처·상세 주소의 별도 사본** — §9.3 의 로깅 정책과 같은 이유로 이벤트도 최소 수집이다. `address.line` 은 배송에 필요해서 싣지만 로그로는 나가지 않는다.
- **금액의 소수 표현** — 모든 금액은 정수 KRW 다(불변규칙 9). `costKrw` 에 `"type": "number"` 를 쓰면 계약 테스트가 거부한다.

### 4.7 페이로드 스키마는 서로 `$ref` 하지 않는다 — 파일마다 자기 완결

`fulfillment.planned` 는 `order.placed` 스냅샷을 포함하지만 `order.placed.v1.schema.json` 을 참조하지 않고 같은 정의를 자기 `$defs` 에 복제했다. 봉투도 `payload` 를 이벤트별 스키마로 참조하지 않는다.

이유:
- 파일 간 `$ref` 는 검증기에 **스키마 레지스트리 구성**을 요구한다. 파일 하나만 읽어서는 검증할 수 없게 되고, 언어·도구(Java networknt, python jsonschema, CI 스크립트)마다 리졸버를 맞춰야 한다.
- 스냅샷은 원본과 **함께 진화하지 않는다.** `order.placed` v2 에서 필드가 바뀌어도 `fulfillment.planned` v1 의 스냅샷 모양은 v1 그대로 유지되어야 한다. `$ref` 로 묶으면 원본 변경이 스냅샷 계약을 조용히 바꾼다.

대가는 중복이다. 한 파일 안에서는 `$defs` 로 묶어 중복을 최소화했고, 파일 간 중복(주소·시간창·화물·품목 정의)은 **의도적으로** 남겨 두었다.

---

## 5. 같은 major 안에서 스키마를 고칠 때 체크리스트

§4.7: **추가만 허용.** 필드 삭제·의미 변경·타입 변경·enum 값 삭제·제약 강화는 전부 새 major(`v2` 토픽 + dual-publish)다.

- [ ] 추가한 필드가 `required` 에 들어가지 않았는가? (기존 발행자는 그 필드를 보내지 않는다)
- [ ] 기존 필드의 제약을 **좁히지** 않았는가? (`maxLength` 축소, `minimum` 상향, enum 값 삭제, `pattern` 강화는 전부 파괴적)
- [ ] `examples/` 를 함께 갱신했는가? 기존 예시는 **그대로 통과해야 한다** (통과하지 못하면 그건 major 변경이다)
- [ ] 새 분기가 생겼다면 variant 예시를 추가했는가?
- [ ] 소비자 쪽 record 에 필드를 추가했다면, 그 필드가 없는 옛 메시지를 받았을 때의 동작(기본값·`@Nullable`)을 정했는가?
- [ ] 파괴적 변경이라면 ADR 을 추가하고 `docs/DESIGN.md` §4 를 먼저 고쳤는가?

---

## 6. 검증

Phase 0 시점의 검증은 python 으로 수행했다(스키마 유효성, 예시의 봉투·페이로드 통과, 봉투↔페이로드 일관성, geohash7 재계산, 음성 케이스 13건, 미지 필드 전방 호환).

```bash
python3 -m pip install --user jsonschema   # 4.23+ (draft 2020-12)
```

`format: date-time` 은 `jsonschema` 에 `rfc3339-validator` 가 없으면 검사되지 않는다. 검증 스크립트는 커스텀 `FormatChecker` 로 RFC 3339 를 직접 강제했다. 같은 함정이 JVM 쪽에도 있다 — **포맷 검증을 켰는지 반드시 확인할 것.**

정식 게이트는 `./gradlew build` 에 포함되는 `libs/messaging` 의 계약 테스트다(Phase 0 작업 3). 위 3절의 검사 항목이 그 테스트의 명세다.

### JVM 계약 테스트에 쓸 검증기 (실측 확인)

카탈로그의 `libs.json-schema-validator` = `com.networknt:json-schema-validator:3.0.7` 이다.
**3.x 는 1.x 와 API 이름이 전부 다르다.** Boot 3 시절 예제(`JsonSchemaFactory`, `JsonSchema`, `ValidationMessage`, `SchemaValidatorsConfig`)를 그대로 쓰면 컴파일되지 않는다.
jar 를 `javap` 로 직접 확인한 결과(2026-08-29):

| 확인한 것 | 값 |
|---|---|
| JsonNode 타입 | `tools.jackson.databind.JsonNode` — **Jackson 3**. Boot 4 기본 Jackson 과 같은 계열이라 변환이 필요 없다 |
| 진입점 | `com.networknt.schema.SchemaRegistry` (`withDefaultDialect(SpecificationVersion, Consumer<Builder>)`, `getSchema(String \| InputStream \| …)`) |
| 스펙 버전 | `SpecificationVersion.DRAFT_2020_12` 존재 |
| 검증 | `com.networknt.schema.Schema#validate(JsonNode)` → `List<com.networknt.schema.Error>` |
| 포맷 강제 | `SchemaRegistryConfig.builder().formatAssertionsEnabled(Boolean)` → `SchemaRegistry.Builder#schemaRegistryConfig(...)` |

`formatAssertionsEnabled(true)` 를 켜지 않으면 `date-time`·`uuid` 는 **검사되지 않는다**(3절의 경고와 같은 함정). 계약 테스트는 켠 상태로 만들고, "포맷 위반이 실제로 거부되는지"를 확인하는 음성 테스트를 최소 1건 둘 것.
