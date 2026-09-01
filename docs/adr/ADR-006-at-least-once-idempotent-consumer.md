# ADR-006 — at-least-once 전달 + 멱등 소비자 (Kafka EOS 미사용)

| 항목 | 내용 |
|---|---|
| 상태 | Accepted |
| 결정일 | 2026-08-29 |
| 관련 문서 | `docs/DESIGN.md` §4.4, §4.5, §4.6, §8.5, §13 · `CLAUDE.md` 불변 규칙 2 |
| 관련 ADR | ADR-002(Outbox) — 이 결정의 원인 |

---

## 맥락

목표 G4: "어떤 컴포넌트를 죽여도 데이터가 유실되거나 **중복 배송되지 않는다**."

이 시스템에서 이벤트 중복 처리는 실제 피해를 만든다.

- `wave.closed` 중복 → 같은 웨이브에 계획이 두 번 돌아 **라우트 중복 생성**.
- `order.dispatched` 중복 → 주문 상태 머신이 잘못된 전이를 시도.
- `route.assigned` 중복 → 배송 건 중복 생성. 문자 그대로 중복 배송이다.

Kafka는 `processing.guarantee=exactly_once_v2` 라는 이름의 기능을 제공한다.
이름만 보면 위 문제가 해결될 것 같으므로, **왜 그것으로는 안 되는지**를 명확히 하는 것이 이 ADR의 핵심이다.

## 결정

- 전달 보장은 **at-least-once** 를 전제한다. 중복이 발생한다고 가정하고 설계한다.
- 모든 Kafka 리스너는 **멱등 소비자**로 만든다. `processed_events(event_id, consumer)` 를 PK로 두고,
  **비즈니스 로직 · processed_events INSERT · 자기 outbox INSERT 를 하나의 DB 트랜잭션**으로 처리한다.
  이미 존재하면(PK 충돌) 처리를 생략한다. 구현은 `libs/messaging` 의 `IdempotentConsumer` 하나로 통일한다.
- **Kafka 트랜잭션 / EOS 는 사용하지 않는다.**
- 프로듀서는 `enable.idempotence=true`, `acks=all` 만 사용한다. 이는 EOS가 아니라 **파티션 내 프로듀서 재시도로 인한
  중복·재정렬 방지**이며, 브로커 세션 범위에서만 유효하다. 릴레이 재시작 후의 재발행은 여전히 중복을 만든다.

## 근거

### 1. Kafka 트랜잭션의 원자성 경계는 Kafka 클러스터 안이다

Kafka 트랜잭션이 원자적으로 묶는 것은 정확히 두 가지다.

- 여러 파티션(여러 토픽 포함)으로의 `send`
- `sendOffsetsToTransaction` 으로 넘긴 **소비 오프셋 커밋**

실측(`kafka-clients` 4.2.1, `javap org.apache.kafka.clients.producer.Producer`):

```
public abstract void initTransactions();
public abstract void beginTransaction() throws ProducerFencedException;
public abstract void sendOffsetsToTransaction(Map<TopicPartition, OffsetAndMetadata>, ConsumerGroupMetadata);
public abstract void commitTransaction() throws ProducerFencedException;
public abstract void abortTransaction() throws ProducerFencedException;
```

**PostgreSQL 쓰기를 이 경계에 넣는 API가 없다.** 우리가 지켜야 하는 원자성은 "주문 상태 UPDATE + 이벤트 발행"이고,
그중 절반이 트랜잭션 밖에 있다.

### 2. 2PC로 묶을 수도 없다

XA 2단계 커밋을 하려면 리소스가 `prepare` 상태를 외부 코디네이터에게 넘길 수 있어야 한다.
실측: `kafka-clients` 4.2.1 jar 에 `XAResource` 구현이 **하나도 없고**(`unzip -l | grep -ci xaresource` → `0`),
위 인터페이스에도 prepare에 해당하는 메서드가 없다. 커밋 결정을 보류했다가 코디네이터의 지시로 확정하는 동작이
API 수준에서 불가능하므로 JTA 트랜잭션 매니저가 Kafka를 XA 리소스로 등록할 방법이 없다 — **구현 가능성 자체가 없다.**

### 3. 남는 것은 "best-effort 1PC"뿐이고, 그것은 창을 좁힐 뿐 없애지 못한다

Spring Kafka는 두 트랜잭션 매니저의 커밋을 순서대로 호출하는 `ChainedKafkaTransactionManager` 를 제공한다.
실측: `spring-kafka` 4.1.1 에 클래스는 아직 존재하지만 `@Deprecated` 다
(`javap -v … ChainedKafkaTransactionManager` → `Deprecated: true`). 이유는 이 방식이 원자성을 주지 못하기 때문이다.
커밋 순서를 어떻게 잡아도 결과는 둘 중 하나다.

| 커밋 순서 | 두 커밋 사이에 프로세스가 죽으면 |
|---|---|
| DB 커밋 → Kafka 커밋 | 상태는 바뀌었는데 **이벤트가 없다(유실)**. 주문은 존재하지만 영원히 배송되지 않는다 |
| Kafka 커밋 → DB 커밋 | 이벤트는 나갔는데 **상태가 없다(허깨비 이벤트)**. 하류가 존재하지 않는 주문을 계획한다 |

두 경우 모두 G4 위반이다. **EOS를 켜도 "DB 상태 변경과 이벤트 발행의 원자성"은 얻지 못한다.**
우리에게 필요한 원자성이 정확히 그 지점이므로, EOS는 우리 문제를 풀지 않는다.

그 원자성은 ADR-002의 Outbox가 준다 — 두 리소스 문제를 단일 PostgreSQL 트랜잭션으로 환원한다.
그 대신 릴레이가 "Kafka 발행 성공 → `published_at` 기록" 사이에 죽으면 재발행이 일어난다.
**즉 Outbox를 택한 순간 발행은 구조적으로 at-least-once가 된다.** 이는 부작용이 아니라 의도된 교환이다.

### 4. 소비 측에서도 EOS는 우리 부수효과를 보호하지 않는다

EOS의 read-process-write 보장은 **Kafka → Kafka** 파이프라인(예: Kafka Streams)에 대한 것이다.
우리 리스너의 부수효과는 PostgreSQL 행이고, 트랜잭션이 abort돼도 Kafka는 그 행을 롤백해 주지 않는다.
오프셋만 롤백되어 **같은 메시지를 다시 받는데 DB에는 이미 효과가 남아 있다** — 정확히 중복 처리 상황이다.

### 5. 그러므로 중복은 제거 대상이 아니라 흡수 대상이다

중복을 없앨 수 없다면 남은 선택은 **효과의 멱등성**뿐이다. 멱등 소비자를 어차피 만들어야 한다면
EOS의 비용 — `transactional.id` 관리(롤링 배포 시 fencing), `read_committed` 소비자의 LSO 대기 지연,
방치된 트랜잭션이 `transaction.timeout.ms` 동안 파티션 소비를 막는 실패 모드, 처리량 손실 — 을 지불할 이유가 없다.
우리의 대안은 이미 쓰고 있는 리소스의 PK 하나(`processed_events`)다.

## 고려한 대안과 기각 이유

| 대안 | 장점 | 기각 이유 |
|---|---|---|
| `exactly_once_v2` (EOS) | 이름이 주는 안심, Kafka→Kafka 구간의 진짜 원자성 | 위 근거 1–4. 우리 부수효과가 외부 DB이므로 보장 범위 밖이다. 비용만 지불하고 문제는 남는다 |
| Redis `SETNX` 로 중복 제거 | 빠르고 DB 부하가 없다 | Redis 키가 사라지면 정확성이 깨진다 — `CLAUDE.md` 규칙 7(Redis는 진실 저장소가 아님) 위반. 진실은 DB에 있어야 한다. 핫 경로 사전 필터로만 유용한데, DB PK 충돌 비용이 이미 충분히 싸다 |
| 자연 멱등만 사용(UPSERT·상태 머신이 재적용을 흡수) | 테이블 하나가 준다 | 상태 머신이 흡수하지 못하는 부수효과가 있다: outbox 재기록(같은 이벤트가 하류로 또 나감), 카운터·감사 로그 중복. 상태 머신 방어(규칙 6)와 `processed_events` 를 **병행**한다 |
| 수동 오프셋 커밋으로 "정확히 한 번" 흉내 | 단순 | 처리와 커밋 사이의 크래시 창은 그대로다. 근거 3과 같은 문제 |

## 결과

**장점**

- 실패 모드가 단순하다: **의심스러우면 재시도한다.** 재시도가 항상 안전하다는 것이 이 설계의 핵심 성질이다.
- 릴레이와 소비자를 자유롭게 수평 확장·재시작할 수 있다.
- 증명 가능하다. Phase 0 DoD(같은 이벤트 2회 전달 → 1회 처리), 카오스 검증 SQL(§13)로 중복 0을 확인한다.

**비용**

- **모든 리스너가 규율을 지켜야 한다.** 하나라도 `processed_events` 체크를 빠뜨리면 그 경로만 중복에 노출된다.
  → ArchUnit 규칙(리스너는 `adapter.in.messaging` 에만 존재) + `IdempotentConsumer` 단일 진입점으로 강제한다.
- **`processed_events` 가 서비스마다 무한히 증가한다.** 보존 기간은 "같은 이벤트가 재전달될 수 있는 최대 시간"
  (DLQ 재처리 포함) 이상이어야 하며, 그 이후 정리한다.
- **트랜잭션이 커진다.** 비즈니스 로직 + processed + outbox가 한 트랜잭션이므로 락 보유 시간이 길어진다.
  계획처럼 오래 걸리는 작업은 트랜잭션 밖에서 수행하고 결과 반영만 짧은 트랜잭션으로 커밋한다.
- **멱등성은 중복을 막을 뿐 순서를 주지 않는다.** 순서는 파티션 키 범위에서만 보장되며(§4.5),
  키가 다른 이벤트의 역전(예: `order.cancelled` 가 `fulfillment.planned` 보다 먼저 도착)은 상태 머신과 취소 마커로 흡수한다.

**되돌리는 방법**

EOS는 컨슈머 그룹 단위로 켤 수 있으므로, 외부 DB 부수효과가 없는 순수 Kafka→Kafka 경로가 생기면
그 경로만 전환해도 나머지에 영향이 없다. 이 결정을 유지하는 한 `processed_events` 는 모든 서비스의 필수 테이블이다.

## 참조

- `docs/DESIGN.md` §4.4(전달 보장), §4.5(순서·파티셔닝), §4.6(재시도/DLQ), §8.5(멱등성 지점 목록), §13(카오스 검증)
- `CLAUDE.md` 불변 규칙 2(멱등 소비자 필수), 6(상태 전이는 상태 머신 메서드로만), 7(Redis는 진실 저장소가 아님)
- 실측 근거: `kafka-clients` 4.2.1, `spring-kafka` 4.1.1 (2026-08-29, `javap` / `unzip -l` 로 확인)
