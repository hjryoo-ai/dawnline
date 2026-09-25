# ADR-015 — Outbox 발행 측 실패를 결정적/일시적으로 나누고 결정적 실패만 격리한다

| 항목 | 내용 |
|---|---|
| 상태 | Accepted (**후속 정정 둘** — 아래 「[후속 정정 — 2026-09-24]」 · 「[후속 정정 — 2026-09-25] 소비 측 경계표」) |
| 결정일 | 2026-09-01 |
| 정정일 | 2026-09-24 — **재큐가 엔드포인트가 됐다**(코어 넷, `libs/messaging` 공유 코드). 「결과」 절의 「Phase 6까지 없다」는 원 결정의 문장이고 고쳐 쓰지 않는다 |
| 정정일 | 2026-09-25 (7-3) — **같은 원칙을 소비 측에 건다.** 일시적 실패는 끝없이 재시도하고 결정적 실패만 DLQ 로 간다. 무엇이 어느 쪽인지는 경계표 한 장이 정한다 |
| 관련 문서 | `docs/DESIGN.md` §4.4(전달 보장), §4.6(재시도/DLQ), §5.1(outbox DDL), §9.1·§9.4(메트릭·알림) |
| 구현 위치 | `libs/messaging/.../outbox/PublishFailureClassifier.java`, `OutboxBatchPublisher.java`, `db/migration/common/V000_3__outbox_quarantine.sql` |
| 런북 | `docs/runbooks/RB-05-dlq-and-outbox-quarantine.md` |

---

## 맥락

Phase 0 감사에서 실제 결함이 하나 나왔다.

`OutboxBatchPublisher` 는 배치의 행들을 봉투(`EventEnvelope`)로 되살려 보낸다. 그런데 봉투 조립이
**동기적으로** 실패할 수 있다 — `eventType` 형식 위반, `schemaVersion` 헤더 누락, 페이로드 JSON 파싱 실패.
이 예외는 배치 트랜잭션 밖으로 나가 트랜잭션을 롤백시키고, `OutboxRelay.poll()` 의
`catch (RuntimeException)` 이 warn 로그로 삼킨다. 다음 폴링(100ms)에서
`lockUnpublishedBatch` 가 `ORDER BY created_at, id` 로 **같은 행을 다시** 집어 온다.

결과는 무한 반복이다. 잘못된 행 하나가 그 서비스의 outbox **전체**를 영구히 막고, 그 뒤의 모든 이벤트가
나가지 못한다. 관측 가능한 신호는 계속 올라가는 `dawnline_outbox_lag_seconds` 뿐이다.

쓰기 경로에 가드를 추가해(`Topics.requireValidEventType`) 알려진 진입점을 막았지만, 그것만으로는 부족하다.
가드는 **오늘 아는 실패 모드**만 막는다. 직렬화기 교체, 스키마 규칙 강화, 수동 INSERT, 과거에 느슨한
규칙으로 들어온 행 — 이 중 무엇이든 같은 상태를 다시 만든다. 진행 보장은 가드가 아니라 릴레이 자체가
져야 하는 성질이다.

## 결정

릴레이의 발행 실패를 **결정적(deterministic)** 과 **일시적(transient)** 으로 나누고, 결정적 실패만 격리한다.

| 종류 | 예 | 처리 |
|---|---|---|
| 결정적 | 봉투 조립·`eventType` 검증·직렬화 실패, 그리고 Kafka 가 비재시도로 분류한 전송 오류(`InvalidTopicException`·`TopicAuthorizationException` 등) | 행을 격리(`failed_at` 기록, `publish_attempts` 증가), `error` 로그 + `dawnline_outbox_failed` 증가, **다음 행 계속 진행** |
| 일시적 | 브로커 연결 불가, 타임아웃, `KafkaException` | 격리하지 않는다. 그때까지의 진행분을 커밋하고 다음 폴링에서 재시도 |

- 판단 기준은 **단계**와 **Kafka 의 `RetriableException` 마커**이며, 분류는
  `PublishFailureClassifier` **한 곳**에만 둔다. 비재시도 예외 목록을 손으로 유지하지 않는다 —
  그 목록은 반드시 불완전해지고, 빠뜨린 것 하나가 곧바로 head-of-line blocking 으로 돌아온다.
- **Kafka 가 분류하지 않은 예외는 일시적으로 취급한다.** 격리는 사람의 개입을 요구하므로
  보수적인 쪽이 기본값이다.
- 격리 행은 릴레이의 조회 대상에서 빠진다(`WHERE published_at IS NULL AND failed_at IS NULL`).
  미발행 게이지도 격리 행을 세지 않는다 — 그건 `dawnline_outbox_failed` 의 몫이다.
- 복구는 수동이다: 원인 수정 후 `failed_at = NULL, publish_attempts = 0` (RB-05).

## 근거

- **진행 보장이 정확성보다 먼저 무너진다.** at-least-once는 "언젠가 도착한다"를 약속한다.
  head-of-line blocking은 그 약속을 조용히 깬다 — 데이터는 멀쩡한데 아무것도 나가지 않는다.
- **두 실패의 성질이 정반대다.** 결정적 실패는 몇 번을 재시도해도 같은 결과다. 재시도는 순수한 낭비이며
  그 대가로 뒤의 모든 이벤트를 인질로 잡는다. 일시적 실패는 반대로 기다리면 풀린다 — 여기서 격리하면
  브로커가 잠깐 흔들렸다는 이유로 멀쩡한 이벤트가 사람 손을 기다린다. 같은 정책으로 다룰 수 없다.
- **격리는 사람에게 넘기는 행위다.** 그래서 자동 폐기가 아니라 `failed_at` 표시 + 알림이다.
  이벤트는 DB에 그대로 남고, 원인을 고친 뒤 되돌릴 수 있다.
- **분류를 한 곳에 모으면 정책이 보인다.** 예외 타입 판단이 발행 루프에 흩어지면
  "이 예외가 왜 격리되는가"를 코드 전체에서 재구성해야 한다.

## 고려한 대안과 기각 이유

**(a) 무한 재시도 유지 (현행)**

- 장점: 이벤트를 절대 잃지 않고, 순서 보장(§4.5)이 완전하다. 코드가 가장 단순하다.
- 기각: 진행 보장이 없다. 잘못된 행 하나에 서비스 전체의 이벤트 발행이 인질로 잡히고,
  그 상태가 warn 로그 하나로만 드러난다. **이번 감사에서 실제로 도달 가능함이 확인된 상태다** —
  이론적 위험이 아니다.

**(b) DLQ 토픽으로 우회 발행**

- 장점: 소비 측 DLQ(§4.6)와 대칭이고, 격리된 이벤트를 Kafka 도구로 볼 수 있다.
- 기각: **실패 원인과 순환한다.** 결정적 실패의 상당수는 "이 행을 Kafka 레코드로 만들 수 없다"이다.
  봉투를 만들지 못해 실패한 행을 DLQ **토픽**으로 보내려면 다시 레코드를 만들어야 한다.
  원본 바이트를 그대로 싣는 우회는 가능하지만, 그러면 DLQ에 스키마를 만족하지 않는 레코드가 들어가
  DLQ 소비자가 같은 문제를 물려받는다. 행은 이미 우리 DB 안에 안전하게 있다 —
  더 위험한 곳으로 옮길 이유가 없다.

**(c) N회 재시도 후 자동 폐기**

- 장점: 사람 개입 없이 진행이 회복된다.
- 기각: 도메인 이벤트의 소실은 되돌릴 수 없다. 주문 하나가 조용히 사라지는 대가로 얻는 것이
  "알림을 보지 않아도 되는 편의"뿐이다. `publish_attempts` 는 기록하되 폐기 트리거로 쓰지 않는다.

**(d) 격리 대신 건너뛰기(`failed_at` 없이 다음 행부터 처리)**

- 장점: 스키마 변경이 없다.
- 기각: 매 폴링(100ms)마다 같은 행에서 같은 예외가 나고 같은 error 로그가 쌓인다.
  상태가 DB에 남지 않으므로 "지금 격리된 행이 몇 개인가"를 물을 수 없고, 알림도 만들 수 없다.

## 결과

**장점**

- 잘못된 행 하나가 서비스 전체를 막지 못한다. 진행 보장이 릴레이 자체의 성질이 된다.
- 격리 상태가 DB 컬럼과 게이지로 드러나므로 알림(§9.4)과 런북(RB-05)이 붙는다.
- 이벤트는 보존된다. 원인을 고치면 `UPDATE` 한 줄로 되돌아온다.

**비용**

- **§4.5의 순서 보장이 그 파티션 키에 한해 깨진다.** 격리 행 뒤에 같은 키의 이벤트가 있으면
  그것이 먼저 발행된다. 대안이 "전체 정지"이므로 받아들인 트레이드오프이며, §4.6에 명시했다.
  소비자는 이미 순서 역전을 상태 머신으로 흡수하도록 설계되어 있다(§4.5).
- **컬럼 2개와 부분 인덱스 조건 변경.** `outbox_events` 는 모든 서비스가 공유하므로
  마이그레이션이 5개 DB에 적용된다.
- **분류가 틀릴 수 있다.** 결정적인데 일시적으로 분류하면 예전 동작(무한 재시도)으로 돌아가고,
  반대면 회복 가능한 행이 격리된다. 그래서 기본값을 일시적으로 두고, 분류기를 한 곳에 모아
  테스트로 고정했다.
- **복구가 수동이다.** ops-api 재큐 엔드포인트는 Phase 6까지 없다. 그전까지는 SQL이다(RB-05). → 2026-09-24 후속 정정.

**되돌리는 방법**

`PublishFailureClassifier` 가 모든 예외를 일시적으로 분류하게 바꾸면 격리는 즉시 멈춘다(코드 한 줄).
컬럼은 남지만 `failed_at` 이 항상 NULL이 되어 무해하다. 인덱스 조건도 그대로 동작한다.

## 참조

- `docs/DESIGN.md` §4.6(발행 측 실패), §5.1(outbox DDL), §9.1·§9.4
- 관련 ADR: ADR-002(폴링 Outbox 릴레이), ADR-006(at-least-once + 멱등 소비자)
- `docs/runbooks/RB-05-dlq-and-outbox-quarantine.md`

---

## [후속 정정 — 2026-09-24] 격리 조회·재큐는 코어 넷에, 공유 코드 한 벌로

Phase 6 작업 2. 복구의 두 절반 중 **뒤의 절반**(`failed_at = NULL, publish_attempts = 0`)이 엔드포인트가 됐다.
앞의 절반(원인 수정)은 여전히 사람의 일이고, 이 정정이 그것을 바꾸지 않는다. 전문은 `docs/DESIGN.md` §4.6
「격리 조회·재큐 엔드포인트」.

### 결정

1. **네 코어 전부, 공유 코드 한 벌.** `libs/messaging` 의 `OutboxAdminController` 를 자동 설정이 등록한다.
   조건은 `OutboxRepository` 빈 + 서블릿 웹 앱이다. 서비스마다 컨트롤러를 두면 비용은 코드 네 벌이고, 켜는
   스위치를 서비스가 들면 **새 서비스가 한 줄을 잊었을 때 조용히 빠진다.** 조건으로 두면 비용은 문서 재생성뿐이다.
2. **표면.** `GET /api/v1/admin/outbox/quarantined?limit=` · `POST /api/v1/admin/outbox/{id}/requeue`.
   목록은 `payload`·`headers`·`partition_key` 를 싣지 않는다(§9.3). 재큐는 RB-05 의 SQL 과 같은 조건부
   `UPDATE … WHERE id = ? AND failed_at IS NOT NULL` 이고, 0 행이면 다시 읽어 404(없음)와
   409 `not-quarantined`(격리가 아님)를 가른다.
3. **409 는 지금 그 행이 어디 있는지 말한다** — 본문 최상위의 `currentState`(`PENDING`·`PUBLISHED`)와
   `publishedAt`. ADR-054 의 `wave-not-open` 과 같은 이유다: 응답을 못 받은 재큐를 다시 누른 사람이 이 409 로
   앞의 요청이 적용됐는지 읽는다(ops-api 감사 `UNKNOWN` 의 해소, §5.5).
4. **ops-api 는 끈다.** 조건은 맞는다(ops-api 에도 outbox 표와 릴레이가 있다). 그러나 ops-api 의 운영 표면은
   전부 감사 행을 남기고 이 경로는 남기지 않는다 — 켜 두면 **감사 없는 재큐**가 ops-api 에 생긴다. 끄는 자리는
   속성 `dawnline.messaging.outbox.admin-api=false` 이고, 끈 이유는 ops-api 의 테스트가 말한다(CLAUDE.md
   「제외한 것이 왜 제외인지를 검사하는 테스트를 함께 둔다」). 속성으로 잊는 방향은 **열리는 쪽**이라 조용하지
   않다 — 그 테스트가 빨개진다.
5. **새 인덱스는 없다.** 목록은 V000_4 의 `ix_outbox_failed (failed_at) WHERE failed_at IS NOT NULL` 을 탄다 —
   술어가 인덱스 술어와 같은 리터럴이다. **근거: 관측(재현됨)** — 200,000 발행 완료 행 + 격리 3 을 채우고
   `ANALYZE` 한 뒤(`reltuples=200003`, `docs/benchmarks/phase1-retention-indexes.md` 와 같은 크기) 생성된 형태의
   문장을 EXPLAIN 했다: `Bitmap Index Scan on ix_outbox_failed` → 3행 정렬, 0.014 ms · 버퍼 2. 재큐는
   `outbox_events_pkey` Index Scan 에 `failed_at IS NOT NULL` 필터이고 준비된 문장의 일반 계획도 같다.
   격리 행은 평상시 0 이다(알림이 지킨다). 그 수가 수천이 되면 정렬이 인덱스 밖에서 도는 비용을 다시 잰다.

### 고려한 대안

- **서비스마다 컨트롤러.** 네 벌이 갈라지는 자리가 없는데 네 벌을 둔다 — ADR-049 가 `ProblemDetailsAdviceSupport`
  를 뽑은 것과 같은 판단이다.
- **서비스가 켜는 스위치(`admin-api=true` 를 각 서비스가 적는다).** 잊으면 **닫히는 쪽**이라 조용하다. 결정 4 의
  스위치는 그 반대 방향이다.
- **ops-api 가 코어의 DB 를 직접 고친다.** 불변규칙 3 위반.
- **엔티티 전이 메서드(`OutboxEvent.releaseQuarantine()`) + 더티 체킹.** 읽고-고치고-쓰는 세 단계가 되고, 운영자가
  RB-05 에서 쓰던 문장과 다른 문장이 된다. 조건부 `UPDATE` 는 원자적이고 런북과 같은 문장이다.

### 재검토 지점

- **order-service 의 `/api/v1/admin/**` 은 고객 표면과 같은 포트에 있다.** 코어의 운영 경로가 무인증인 것은
  §10 의 결정(인증은 ops-api 가 맡는다)과 같지만, 고객 API 를 가진 서비스는 order 하나다. 목록은 격리된 행의
  `aggregateId`(주문 id)를 싣는다. **근거: 추정** — 재지 않았다. 실서비스 전환 시 §10 의 인증 재검토와 함께
  `/api/*/admin/**` 을 네트워크 경계에서 닫는다.
  **→ 2026-09-24 닫힘 — [ADR-055](ADR-055-operator-writes-on-cores-carry-an-internal-token.md).** 네트워크 경계로 미루지 않고
  내부 토큰으로 지금 닫았다. 범위는 이 경로 하나가 아니라 코어의 운영자 쓰기 전부다 — 같은 근거가 조기 마감·재계획·
  룰 수정·자원 등록·재배정에 그대로 적용된다. 목록(`GET`)은 대상이 아니다(§9.3 이 다룬다).
- **격리가 잦아지면** 한 건씩 누르는 재큐는 느리다. 일괄 재큐는 RB-05 1.4 가 SQL 에서도 막은 것(원인이 다른
  행이 섞인다)이라 넣지 않았다 — 그 판단이 바뀌는 날은 원인별로 묶을 칸(예외 클래스)이 행에 생기는 날이다.

---

## [후속 정정 — 2026-09-25] 소비 측 경계표 — DLQ 는 독약 메시지의 자리이지 장애의 자리가 아니다

7-3. 원 결정은 **발행 측**에 「결정적이면 격리, 일시적이면 기다린다」를 걸었다. 소비 측은 그 원칙 밖에 있었다 — `DawnlineErrorHandlers`
는 역직렬화 실패만 즉시 DLQ 로 보내고 **나머지 전부를** 3회 재시도 뒤 DLQ 로 보냈다. DB 가 5분 넘게 멈추면 그 사이에 배달된 레코드가
DLQ 로 가고, 복구 뒤에는 사람이 재처리해야 한다.

**근거: 관측(재현됨)** (2026-09-25, 7-5 — `docs/runbooks/RB-02-database-outage.md` §3): fulfillment 의 로그인을 막고 세션을 끊은 채 주문
200건, 5분 20초. 6건이 DLQ 로 갔고 194건은 복구 뒤 30초 안에 처리됐다. 전부가 가지 않은 이유는 커넥션 풀의 대기(30초 × 4번 — 레코드
하나에 약 2분)가 리스너를 붙잡았기 때문이다. §8.4 의 「소비자 재시도 후 pause」는 구현된 적이 없고, 멈춤처럼 보인 것은 그 대기였다.
DLQ 로 간 여섯은 **독약이 아니었다** — ops-api 로 재처리하니 전부 성공했다.

### 결정

1. **판정은 발행 측과 같은 두 값이다** — `FailureKind { DETERMINISTIC, TRANSIENT }`(`com.dawnline.messaging`). 발행 측
   `PublishFailureClassifier` 의 중첩 enum 을 공유 타입으로 올렸다. 발행 측 판정 규칙은 그대로다.
2. **일시적 → 끝없이 재시도.** 백오프 수열은 §4.6 과 같고(200ms · 1s · 5s) 5초에서 멈춘 채 되풀이한다. 그동안 그 파티션은 멈춘다.
   §8.4 가 「pause」라고 부르던 것이 이것이고, 장애 중에는 원하는 성질이다. 순서가 지켜지고(§4.5) 복구 뒤 사람의 일이 없다.
   **결정적 → 3회 재시도 뒤 DLQ**(지금과 같다). 역직렬화 · 스키마 불일치 → **즉시 DLQ**(지금과 같다).
3. **경계는 아래 표 한 장이다.** 원인 사슬 전체를 보고, **표의 위에서부터 사슬 어딘가에 걸리는 첫 행**이 판정이다. 걸리는 행이 없으면
   「그 밖」 — 일시적이다. 원 결정의 「애매하면 일시적」과 같은 방향이다. 소비 측에서 틀린 쪽의 대가는 이렇다. 결정적인데 일시적으로 판정하면
   **파티션이 선다.** 이것은 보이는 실패이고, 결정 4 의 게이지와 알림이 잡는다. 일시적인데 결정적으로 판정하면 **이벤트가 조용히 DLQ 로
   빠진다.** 이것은 사람이 찾아야 하는 실패다. 되돌리기 쉬운 쪽이 기본값이다.
4. **무한 재시도는 보여야 한다.** 카운터 `dawnline_event_retry_total{consumer, reason}`(몇 번 · 어느 행)과 게이지
   `dawnline_event_retry_age_seconds{consumer}`(지금 재시도 중인 레코드가 얼마나 오래 막고 있나 — 파티션 중 최대)를 둔다. 파티션 정지의
   신호는 「몇 번」이 아니라 「얼마나 오래」다. 게이지에 알림이 걸린다(§9.4). **일시적 실패가 30분 넘게 이어지면 그건 장애가 아니라
   설정이다** — 틀린 자격 증명, 지워진 표, 틀린 판정. RB-01 · RB-02 의 경계 행이 이 문장이다.
5. **표의 정본은 코드다** — `ConsumeFailure`(enum, 표의 행 순서 = 판정 순서). 이 문서의 표는 그 복사이고 `ConsumeFailureTableTest` 가
   행 이름 · 판정 · 순서를 대조한다(CLAUDE.md 「서로를 비추는 목록에는 대조 검사를 둔다」). 카운터의 `reason` 값도 같은 enum 에서 나오고,
   카탈로그의 닫힌 값과 대조된다(ADR-060 결정 2).

### 경계표

| 행 (`reason`) | 원인 사슬 어딘가에 있으면 | 판정 | 처리 |
|---|---|---|---|
| `deserialization` | `JacksonException` · `NonRetryableEventException` · Spring Kafka 의 기본 치명 목록(`DeserializationException` · `MessageConversionException` · `ConversionException` · `MethodArgumentResolutionException` · `NoSuchMethodException` · `ClassCastException`) | 결정적 | **즉시 DLQ** — 재시도 목록 밖이라 카운터에 세지 않는다 |
| `redis` | `org.springframework.data.redis` · `io.lettuce` 의 예외(연결 실패 `RedisConnectionFailureException`, 명령 시간 초과) | 일시적 | 끝없이 재시도. 대부분 여기까지 오지 않는다 — 코어의 Redis 호출은 폴백이 먼저 잡는다(§7.2). 아래 DB 행들보다 위인 이유: `RedisConnectionFailureException` 은 `DataAccessResourceFailureException` 이다 |
| `db_connection` | `CannotCreateTransactionException` · `CannotGetJdbcConnectionException` · `SQLTransientConnectionException` | 일시적 | 끝없이 재시도 — RB-02 재현의 Hikari 30초 대기가 이 행이다 |
| `db_resource` | `DataAccessResourceFailureException` 계열 · `SQLRecoverableException` | 일시적 | 끝없이 재시도 |
| `db_transient` | `TransientDataAccessException` 계열(`QueryTimeoutException` · 락 대기 · 교착 · 낙관적 락 충돌) · `SQLTransientException` | 일시적 | 끝없이 재시도 |
| `db_integrity` | `DataIntegrityViolationException` 계열 | 일시적 | 끝없이 재시도. **애매한 행이다.** 제약 결함이면 매번 같은 결과(결정적)이고, 두 인스턴스의 경합이면 재시도에서 멱등 검사가 통과해 풀린다(일시적). 애매하면 일시적이다 — 결함이면 파티션이 서고 결정 4 의 알림이 보인다. **반복되면 결정적일 가능성이 높다.** 그때는 데이터를 고치거나 그 레코드를 격리한다. 사람이 거는 격리 경로는 지금 없다(DLQ 는 자동뿐) — 그것이 필요해지면 [ADR-053](ADR-053-dlq-replay-is-addressed-to-the-failed-group.md) 의 재검토로 연다 |
| — (HTTP) | **해당 없음.** 코어의 리스너는 외부 HTTP 를 부르지 않는다 — 동기 호출은 ops-api → 코어 방향뿐이고(불변규칙 4), ops-api 의 위임은 리스너가 아니다 | — | 행을 지우지 않고 이유를 남긴다. 이 문장은 ArchUnit 규칙 12(코어 넷은 HTTP 클라이언트에 의존하지 않는다)가 지킨다. 그 규칙이 깨지는 날 이 칸이 실제 행이 된다 |
| `argument` | `IllegalArgumentException` | 결정적 | 3회 뒤 DLQ |
| `domain` | `DomainException` 하위(`ValidationException` · `NotFoundException` · `IllegalStateTransitionException` …) | 결정적 | 3회 뒤 DLQ. 비즈니스 규칙 위반(`EventRejectedException`)은 이 표에 오지 않는다 — `IdempotentConsumer` 가 흡수하고 커밋한다(§4.6) |
| `other` | 그 밖의 전부(`NullPointerException` · `IllegalStateException` …) | 일시적 | 끝없이 재시도 — 애매하면 일시적 |

**일시적 행이 왜 필요한가** — 기본값이 이미 일시적인데. 이 행들은 **결정적 행보다 위에 서서 거부권을 쥔다.** 원인 사슬에 DB 연결 실패가
있으면 겉이 `IllegalArgumentException` 이든 `DomainException` 이든 일시적이다. 장애가 도메인 예외에 싸여 올라와도 DLQ 로 가지 않는다.
그리고 카운터의 `reason` 이 「무엇 때문에 멈췄나」를 행 이름으로 말한다.

**행마다 음성 표본이 하나다** (`ConsumeFailureTest`). 일시적 행은 「그 예외를 결정적 예외로 감싼 것」이 일시적이라는 것을
본다 — 행을 지우면 그 경우가 결정적으로 뒤집혀 빨강이 된다. 결정적 행은 그 예외 단독이 결정적이라는 것을 본다 — 행을 지우면 「그 밖」으로
떨어져 빨강이 된다. 「그 밖」은 기본값을 결정적으로 바꾸면 빨강이다. HTTP 행의 표본은 규칙 12 에 있다.

### 원천에서 고친 것 하나

tracking 의 `RouteAssignedPayload` 는 계약보다 오래된 `route.assigned`(약속창 · 출발 시각 없음)를 `IllegalStateException` 으로 거절했다.
예전에는 3회 뒤 DLQ 였다. 이 표에서는 「그 밖」이라 **영원히 재시도**가 된다. 스키마 불일치이므로 `NonRetryableEventException` 으로
바꿨다 — §4.6 의 「스키마 불일치 → 즉시 DLQ」 행이다. 리스너 어댑터(`adapter.in.messaging`)가 직접 던지는 예외를 전부 훑었고, 표 밖의
타입은 이 둘뿐이었다. 응용 계층 안쪽의 `IllegalStateException` 은 훑지 않았다 — 그것들은 「그 밖」이 받고, 결함이면 알림이 보인다.

### 고려한 대안

- **Spring Kafka 의 `addNotRetryableExceptions` 목록만 늘린다.** 결정적 예외를 나열하고 나머지를 N회 뒤 DLQ 로 보내는 모양이다.
  목록 밖의 **일시적** 실패가 여전히 DLQ 로 간다. 관측된 결함이 바로 이것이다.
- **컨테이너를 멈춘다(`CommonContainerStoppingErrorHandler`).** 파티션 하나의 장애가 그 인스턴스의 모든 파티션을 세우고, 다시 켜는
  것이 사람의 일이 된다. 끝없는 재시도는 풀리는 순간 스스로 움직인다.
- **일시적도 N회 뒤 DLQ 로 보내고 N 을 크게 둔다.** 장애 길이에 상한을 거는 것이다. 그 상한을 넘는 장애는 관측된 결함을 그대로
  되풀이한다. 상한이 필요한 자리는 재시도가 아니라 **알림**이다 — 결정 4.

### 비용

- **파티션이 선다.** 결정적 실패를 일시적으로 판정하면 그 파티션의 뒤 레코드가 전부 기다린다. 발행 측 원 결정의 (a)(무한 재시도)가
  기각된 이유와 같은 위험이다. 다른 점은 **보인다**는 것이다. 게이지의 알림이 30분에 울리고, 카운터의 `reason` 이 어느 행인지 말한다.
- **`max.poll.interval.ms` 는 문제가 되지 않는다.** 재시도 한 바퀴는 폴 → 리스너(실패까지, 커넥션 대기 최대 30초) → 백오프(최대
  5초)이고, 기본값 300초 안에 든다. 컨슈머가 그룹에서 쫓겨나지 않는다는 것은 IT 가 본다(`ConsumerRetryIT` — 폴 간격 상한을 백오프 수열보다
  조금 크게 줄여 두고, 오래 재시도한 뒤에도 리밸런스가 없는지 본다).

### 재검토 지점

- **`db_integrity` 가 실제로 반복되는 날.** 경합이 아니라 결함이면 사람이 레코드를 격리할 경로가 필요하다(위 표).
- **`other` 가 카운터에 쌓이는 날.** 「애매하면 일시적」의 대가가 파티션 정지로 나타나고 있다는 뜻이다. 그 예외를 표의 결정적 행으로
  올릴지 판단한다. 올린다면 그 예외를 던지는 쪽을 먼저 본다 — 이번의 `RouteAssignedPayload` 처럼 원천에서 고치는 것이 먼저다.
