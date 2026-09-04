# RB-05 — DLQ 재처리 · Outbox 격리 재큐

| 항목 | 내용 |
|---|---|
| 대상 | 소비 측 DLQ 적재, 발행 측 outbox 격리 |
| 알림 | `DLQ 신규 > 0`, `dawnline_outbox_failed > 0` (DESIGN.md §9.4) |
| 관련 설계 | §4.6(재시도/DLQ, 발행 측 실패), §5.1(outbox DDL), ADR-015 |

이 런북은 **두 개의 다른 장애**를 다룬다. 먼저 어느 쪽인지 가른다.

| 알림 | 어디서 막혔나 | 이벤트의 현재 위치 |
|---|---|---|
| `dawnline_outbox_failed > 0` | **발행 측** — 브로커에 나가지도 못했다 | 그 서비스의 `outbox_events` 행 |
| DLQ 신규 > 0 | **소비 측** — 나갔는데 소비자가 처리하지 못했다 | `<topic>.dlq` 토픽 |

---

## 1. 발행 측 — outbox 격리 재큐

### 1.1 무슨 일이 일어난 것인가

릴레이가 그 행을 **봉투로 만들 수 없어서** 격리했다(§4.6 결정적 실패). 재시도해도 같은 결과라서,
그대로 두면 그 행이 뒤의 모든 이벤트를 막는다. 그래서 릴레이는 `failed_at` 을 찍고 넘어간다.

**뒤의 이벤트는 이미 흐르고 있다.** 급한 불은 꺼져 있으니 서두르지 말고 원인부터 본다.
격리된 이벤트 자체는 지워지지 않았다 — DB에 그대로 있다.

### 1.2 무엇이 격리됐는지 본다

PostgreSQL 컨테이너는 하나이고 그 안에 서비스별 데이터베이스가 5개 있다. 계정도 서비스마다 다르다
(`deploy/compose/initdb/01-roles-and-databases.sql`).

```bash
# 해당 서비스 계정으로 바로 붙는다 (order / fulfillment / dispatch / tracking / ops 중 하나)
docker compose -f deploy/compose/docker-compose.yml --env-file deploy/compose/.env \
  exec postgres psql -U dawnline_order -d dawnline_order

# 또는 관리자 계정으로 붙어서 데이터베이스를 옮긴다
make psql        # dawnline_admin 에 접속
\c dawnline_order
```

```sql
-- 격리된 행 목록. payload 는 개인정보가 있을 수 있으니 기본 조회에 넣지 않는다(§9.3).
SELECT id, event_type, topic, partition_key, publish_attempts, created_at, failed_at
  FROM outbox_events
 WHERE failed_at IS NOT NULL
 ORDER BY failed_at;

-- 유형별 집계 — 한 가지 원인인지 여러 가지인지 먼저 본다.
SELECT event_type, count(*), min(failed_at), max(failed_at)
  FROM outbox_events
 WHERE failed_at IS NOT NULL
 GROUP BY event_type;
```

### 1.3 원인을 찾는다

격리 시점의 `error` 로그에 예외가 그대로 남아 있다.

```bash
make logs SERVICE=order-service | grep '격리합니다'
```

자주 나오는 원인과 대응:

| 예외 | 원인 | 고치는 곳 |
|---|---|---|
| `eventType 은 점으로 구분한 소문자 kebab-case 여야 합니다` | `event_type` 이 §4.1 형식이 아니다 | 아래 1.4의 `event_type`·`headers` 교정 |
| `outbox 행에 schemaVersion 헤더가 없습니다` | `headers` 에 `schemaVersion` 이 빠졌다 | `headers` 교정 |
| `JacksonException` (payload 파싱) | `payload` 가 깨진 JSON | 페이로드 교정. 복원 불가면 1.5 |
| `RecordTooLargeException` | 레코드가 브로커 상한 초과 | 브로커 `max.message.bytes` 상향 또는 페이로드 축소 |

> **주의**: 원인을 고치지 않고 `failed_at` 만 지우면 릴레이가 다시 집어 다시 격리한다.
> `publish_attempts` 가 올라가는 것으로 그 상황을 알 수 있다.

### 1.4 고치고 재큐한다

한 행씩, `id` 를 명시해서 한다. **`WHERE failed_at IS NOT NULL` 만으로 일괄 UPDATE 하지 않는다** —
원인이 서로 다른 행이 섞여 있으면 고쳐지지 않은 행까지 다시 흘려보내게 된다.

```sql
BEGIN;

-- (a) 원인을 고친다. 예: eventType 형식 위반
UPDATE outbox_events
   SET event_type = 'order.placed',
       headers    = jsonb_set(headers, '{eventType}', '"order.placed"')
 WHERE id = '...';

-- (b) 격리를 푼다.
UPDATE outbox_events
   SET failed_at = NULL, publish_attempts = 0
 WHERE id = '...';

COMMIT;
```

100ms 안에 릴레이가 집어 간다. 확인:

```sql
SELECT id, published_at, failed_at, publish_attempts FROM outbox_events WHERE id = '...';
```

`published_at` 이 채워지면 끝이다. `failed_at` 이 다시 채워졌다면 원인이 아직 남아 있다 — 1.3으로 돌아간다.

### 1.5 고칠 수 없는 행

페이로드가 복원 불가능하게 깨진 경우다. **지우기 전에 반드시 내용을 보존한다.**

```sql
-- 감사용으로 내용을 먼저 남긴다(개인정보 취급 주의 — 보존 위치는 팀 정책을 따른다).
\copy (SELECT * FROM outbox_events WHERE id = '...') TO 'quarantined-row.csv' CSV HEADER

DELETE FROM outbox_events WHERE id = '...';
```

이벤트 하나를 잃는 것이므로 **하류 영향을 반드시 확인한다.** 그 애그리거트의 상태를 다른 경로로
다시 흘려보낼 수 있는지(재계획, 수동 커맨드) 먼저 검토한다.

### 1.6 확인

- `dawnline_outbox_failed` 가 0으로 내려온다(스크레이프 주기상 최대 5초).
- `dawnline_outbox_lag_seconds` 가 정상 범위다.

---

## 2. 소비 측 — DLQ 재처리

### 2.1 무엇이 DLQ에 들어갔는지 본다

```bash
make topics       # 토픽 목록에서 <topic>.dlq 확인

docker compose -f deploy/compose/docker-compose.yml --env-file deploy/compose/.env \
  exec kafka /opt/kafka/bin/kafka-console-consumer.sh \
  --bootstrap-server localhost:9092 \
  --topic dawnline.order.placed.v1.dlq --from-beginning --max-messages 10 \
  --property print.headers=true
```

DLQ 레코드의 헤더에 원인 예외와 원본 토픽·파티션·오프셋이 붙어 있다(`DawnlineErrorHandlers`).

### 2.2 원인별 대응

| 원인 | 대응 |
|---|---|
| 스키마 불일치(§4.6) | 소비자를 고쳐 배포한 뒤 replay |
| 하류 의존 장애가 3회 재시도를 넘겼다 | 의존을 먼저 복구하고 replay |
| 비즈니스 규칙 위반 | DLQ에 오면 안 되는 경우다. `dawnline_event_rejected_total` 로 가야 한다 — 소비자 버그 |

> **replay 버튼을 누르기 전에 알아야 할 것 — 24시간 넘은 `order.placed` 는 replay 해도 실패로 종결된다.**
>
> fulfillment-service 는 `cutoffAt` 이 24시간을 넘긴 `order.placed` 를 다음 웨이브로 밀지 않고
> `UNSERVICEABLE`(`reason=STALE_PLACED`)로 종결한다([ADR-020](../adr/ADR-020-cutoff-ownership-wave-grace-promise-revision.md)
> 후속 정정). order-service 는 그 주문을 `FAILED` 로 둔다.
>
> 이것은 결함이 아니라 방어다. 그 상한이 없으면 20일 묵은 주문이 replay 만으로 **오늘 날짜의 새
> 배송 약속**을 받는다 — 고객은 20일 전에 주문했고 시스템은 오늘 배송하겠다고 말한다.
>
> 그러므로 오래된 `dawnline.order.placed.v1.dlq` 레코드를 replay 하는 목적은 **배송을 살리는 것이
> 아니라 기록을 남기는 것**이다. 그 주문을 실제로 살리려면 고객에게 다시 접수받아야 하고,
> 그 판단은 사람이 한다. replay 전에 레코드의 `cutoffAt` 을 먼저 본다:
>
> ```bash
> # DLQ 레코드의 payload.cutoffAt 확인 (§2.1 의 콘솔 컨슈머 출력에서)
> ```
>
> `fulfillment_orders` 는 30일 보존이므로([ADR-023](../adr/ADR-023-fulfillment-retention.md)),
> **30일이 지난 DLQ 레코드는 fulfillment 쪽 기록도 이미 없다.** 두 창을 같은 30일로 맞춰 둔 이유다.
>
> **기록이 없는데 replay 해도 되는가 — 된다.** 재생·중복 `order.placed` 를 막는 것은 나이별로
> 세 겹이고, 기록이 사라진 뒤에는 세 번째 겹이 받는다.
>
> | 이벤트 나이 | 막는 것 |
> |---|---|
> | ~14일 | `processed_events` — 같은 `event_id` 를 두 번 처리하지 않는다 |
> | 14~30일 | `fulfillment_orders` PK — 행이 이미 있어 상태 머신이 무시한다 |
> | 30일~ | `STALE_PLACED` — 행이 없어도 `cutoffAt` 이 24시간을 넘어 종결된다 |
>
> 그래서 오래된 replay 는 **중복 주문을 만들지 않고 실패로 종결된다.** 이 안전성은 보존 30일이
> 상한 24시간보다 한참 길다는 데 의존한다 — 그 상한을 늘리는 변경은 ADR-023 의 30일을 함께
> 재검토해야 한다.

### 2.3 재처리

```
POST /api/v1/admin/dlq/{topic}/replay      (ops-api, OPS_OPERATOR 이상, audit_logs 기록)
```

> Phase 6 이전에는 이 엔드포인트가 없다. 그때까지는 DLQ 토픽을 원본 토픽으로 되돌리는
> 콘솔 프로듀서 수동 작업이며, **반드시 소비자를 먼저 고친 뒤에** 한다.

멱등 소비자(`processed_events`)가 있으므로 **이미 처리된 이벤트를 다시 넣어도 안전하다**(§4.4).
중복 replay 를 두려워하지 않아도 된다.

---

## 3. 예방

- 발행 측 격리는 대부분 `event_type`·`headers` 형식 문제다. 쓰기 경로가
  `Topics.requireValidEventType` 으로 막고 있으므로, 격리가 생겼다면
  **가드를 우회한 경로**(수동 INSERT, 마이그레이션 스크립트, 규칙 강화 이전의 과거 행)를 의심한다.
- 새 이벤트를 추가할 때 `contracts/events/*.schema.json` 과 예시를 먼저 갱신하면
  계약 테스트가 이 부류를 배포 전에 잡는다(§4.7).

## 참조

- `docs/DESIGN.md` §4.6, §5.1, §9.4
- `docs/adr/ADR-015-outbox-publish-side-quarantine.md`
- `libs/messaging/.../outbox/PublishFailureClassifier.java` — 결정적/일시적 판정 규칙
