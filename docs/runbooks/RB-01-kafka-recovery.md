# RB-01 — Kafka 복구: 발행이 멈췄다 · 소비가 밀린다

| 항목 | 내용 |
|---|---|
| 대상 | 발행 측(outbox 릴레이 → 브로커) · 소비 측(브로커 → 리스너) |
| 알림 | `DawnlineOutboxLag`(리더가 `1` 인 경우) · `DawnlineConsumerLag` ([README](README.md) 1) |
| 관련 설계 | §4.4(outbox · 릴레이 리더십) · §4.6(재시도 · DLQ · 경계) · §6.7(랙이 부르는 FAST) · §8.4 · ADR-015 후속 정정 · ADR-016 · ADR-027 |
| 경계 | **일시적 실패가 30분 넘게 이어지면 그건 장애가 아니라 설정이다.** 소비자는 일시적 실패를 끝없이 재시도하고(DLQ 로 가지 않는다), 발행 측 릴레이도 일시적 실패를 끝없이 기다린다(ADR-015). 둘 다 스스로 끝나지 않는다 — 30분(`DawnlineConsumerRetryStuck`)을 넘기면 원인은 브로커의 장애가 아니라 자격 증명 · ACL · 토픽 · 설정이다 |

**먼저 본다 — 메트릭** 어느 쪽이 막혔나: `max by (service) (dawnline_outbox_lag_seconds)`(발행) 와 `sum by (consumergroup, topic) (kafka_consumergroup_lag >= 0)`(소비 — 브로커가 아는 랙, 그룹 이름이 서비스다).
발행이 막혔으면 1, 소비가 밀렸으면 2. 브로커가 죽으면 둘 다 오른다 — 1 이 먼저다(소비는 브로커가 돌아오면 저절로 따라온다).

명령의 `dc` · `prom` · `logs` · `sql` 은 [README](README.md) 의 공통 준비다.

---

## 1. 발행이 멈췄다 — `DawnlineOutboxLag`

**브로커가 죽어도 쓰기는 계속된다** — 주문 API 는 주문과 outbox 행을 한 트랜잭션에 쓰고 201 을 돌려준다(§8.4, 레디니스에 브로커가 없다 — ADR-016). 쌓이는 것은
`outbox_events` 의 미발행 행이고, 복구되면 릴레이가 순서대로 비운다. **급한 것은 브로커 복구 하나다.**

### 1.1 리더가 있는가

```bash
prom 'dawnline_outbox_leader'
```

| 그 서비스의 값 | 뜻 | 갈 곳 |
|---|---|---|
| `1` 이 하나 | 리더가 발행하는데 브로커가 받지 않는다 | 1.2 |
| `-1` | 리더십을 판정할 수 없다 — DB 세션을 잃었다. 발행할 행도 못 읽으므로 같은 사건이다(ADR-027 정정) | [RB-02](RB-02-database-outage.md) |
| 전부 `0` | 아무도 리더가 아니다 — 락을 **이 서비스의 릴레이가 아닌 세션**이 쥐었다 | 1.3 |

### 1.2 브로커

```bash
dc ps kafka                                  # 상태
make topics                                  # 목록이 나오면 브로커는 받는다
logs order-service 10m | grep 'outbox 발행 실패(일시적'   # 릴레이가 본 예외 — 서비스는 알림의 service
```

브로커가 멈췄으면 다시 띄운다. 볼륨(`dawnline_kafka-data`)은 그대로다 — **`down -v` 를 쓰지 않는다.**

```bash
dc up -d kafka
```

돌아오면 **손으로 할 일이 없다.** 릴레이는 100 ms 마다 폴링하고, 일시적 실패로 멈춘 배치의 나머지는 다음 폴링에서 다시 보낸다(「outbox 발행이
중단됐습니다. 남은 행은 다음 폴링에서 재발행합니다」). 그래서 **같은 이벤트가 두 번 나갈 수 있다** — 소비자의 `processed_events` 가 흡수한다
(불변규칙 2). `dup` 이 잠시 오르는 것은 정상이다.

### 1.3 리더가 없다 — 락을 누가 쥐었나

릴레이 리더십은 서비스 DB 의 advisory lock 이다(`classid` = `1145132878`, ASCII `DAWN`). 쥔 세션을 본다:

```bash
sql order "SELECT a.pid, a.application_name, a.client_addr, a.backend_start, a.state
             FROM pg_locks l JOIN pg_stat_activity a USING (pid)
            WHERE l.locktype = 'advisory' AND l.granted AND l.classid = 1145132878"
```

살아 있는 인스턴스의 세션이 아니면(끝난 테스트 · 남은 도구 연결) 그 세션을 끝낸다 — 세션이 끝나면 서버가 락을 즉시 푼다(TTL 없음):

```bash
sql order "SELECT pg_terminate_backend(<pid>)"
```

**살아 있는 인스턴스의 세션을 끝내지 않는다** — 그 인스턴스는 다음 폴링에 다시 쥐고, 그 사이 두 리더가 생길 창은 없지만 원인은 그대로다.

### 1.4 확인

- `dawnline_outbox_lag_seconds` 가 SLO(p95 2초, §8.1) 안으로 내려오고 `dawnline_outbox_unpublished` 가 0 으로 간다.
- 소비 쪽이 따라온다 — 2.3 의 확인.

---

## 2. 소비가 밀린다 — `DawnlineConsumerLag`

**먼저 본다 — 메트릭** 그 서비스의 처리율 — 멈췄나, 느린가:

```bash
prom 'sum by (consumer, outcome) (rate(dawnline_event_processed_total{service="<service>"}[5m]))'
```

### 2.1 처리율이 0 — 멈췄다

| 볼 것 | 뜻 | 대응 |
|---|---|---|
| 서비스 로그에 DB 예외 · `hikaricp_connections_pending` > 0 | 리스너가 DB 를 기다린다 | [RB-02](RB-02-database-outage.md) — 막힌 레코드를 끝없이 재시도하고 뒤는 기다린다. DLQ 로 가지 않는다(RB-02 §3) |
| `dawnline_event_retry_age_seconds` > 0 | 한 레코드가 재시도 중이다 — 사유는 `sum by (reason) (increase(dawnline_event_retry_total{service="<service>"}[10m]))` | 결정적(`argument` · `domain`)이면 네 번째 배달에서 DLQ 로 가고 파티션이 풀린다 — 그 뒤는 [RB-05](RB-05-dlq-and-outbox-quarantine.md) §2. 일시적이면 원인이 풀릴 때까지 멈춘다 — 경계 행(30분) |
| 그룹의 멤버가 계속 바뀐다 | 리밸런스가 반복된다 | 인스턴스가 재기동을 반복하는지 본다(`dc ps` — OOM · 헬스체크). `max.poll.records=100` 한 배치의 처리가 `max.poll.interval.ms` 를 넘으면 그룹에서 빠진다 |

그룹의 파티션별 랙과 멤버 — **브로커가 아는 랙**(커밋된 오프셋 기준)이다. 메트릭으로는 `kafka_consumergroup_lag{consumergroup="<group>"}`(kafka-exporter, 같은 값)이고, 클라이언트 지표 `kafka_consumer_fetch_manager_records_lag` 는 이미 가져온 레코드를 세지 않아서 재시도에 막힌 파티션을 작게 보인다(RB-02 §3 의 관측):

```bash
dc exec -T kafka /opt/kafka/bin/kafka-consumer-groups.sh --bootstrap-server localhost:9092 \
  --describe --group <order-service|fulfillment-service|dispatch-service|tracking-service|ops-api>
```

### 2.2 처리율이 있는데 랙이 는다 — 유입이 처리를 넘는다

피크다. 확장 경로는 §8.2 — 인스턴스를 늘리되 **파티션 수(12)가 상한**이다. 사전 점검은 [RB-06](RB-06-peak-readiness.md).

- **dispatch 의 랙은 계획을 FAST 로 보낸다** — 설계된 동작이다(§6.7). `dawnline_plan_degraded_total{reason="LAG"}` 가 그 수이고 그 계획들의
  비용이 오른다. 랙이 풀리면 다음 계획부터 FULL 로 돌아온다(래치가 아니다, ADR-034).
- **ops-api 의 랙은 대시보드와 KPI 만 늦춘다** — 코어의 정확성과 무관하다. 길어지면 `DawnlineKpiPromiseUnknown` 이 뒤따른다.
- **order-service 의 랙은 취소 경합을 넓힌다** — `DawnlineCancelTooLate` 가 뒤따를 수 있다.

### 2.3 확인

- `sum by (consumergroup, topic) (kafka_consumergroup_lag >= 0)` 이 1,000 아래로 내려온다(브로커 기준 — 클라이언트 지표 `records_lag` 는 재시도에 막힌 레코드를 세지 않는다, §2.1).
- `dawnline_event_retry_age_seconds` 가 0 이다 — 재시도 중인 파티션이 없다.
- 처리율의 `dlq` 가 0 이다. 0 이 아니면 그 레코드는 [RB-05](RB-05-dlq-and-outbox-quarantine.md) §2 로 간다.
- **검증 표** — 복구 뒤에 돌린다. 카오스와 같은 표다(V1–V7 — 유실 · 사유별 배차 불가 · 라우트 stop 주문 중복 · `processed_events` 의 PK ·
  감사 `UNKNOWN` · DLQ 증가 · outbox 미발행/격리):

  ```bash
  bash tools/chaos/verify.sh baseline /tmp/rb01.state        # 장애를 알아챈 때 — 이 시각 뒤의 주문과 DLQ 증가를 본다
  bash tools/chaos/verify.sh check /tmp/rb01.state --wait 600 --expect-dlq 0
  ```

  모든 줄이 ✅ 여야 한다(종료 코드 0). V1 이 「빠진 주문 N」이면 아직 따라오는 중이다 — `--wait` 가 그만큼 기다린다. V7 의 격리가 0 이
  아니면 브로커 부재가 아닌 다른 원인이다 — 브로커 부재는 일시적이라 격리되지 않는다([RB-05](RB-05-dlq-and-outbox-quarantine.md)).

## 3. 검증 — `make chaos-kafka` (2026-09-26 로컬, 근거: 관측(재현됨))

브로커를 5분 멈춘 채(`docker compose stop kafka`) 주문 **1,200**건(ops-demo)과 운영자 조기 마감 하나:

| | 값 |
|---|---|
| 주문 API | 1,200 전부 201 · p99 14.0 ms — 레디니스에 브로커가 없다(ADR-016) |
| outbox | 미발행이 1,201 까지 쌓였다(주문 1,200 + 마감 1). 가장 오래된 미발행의 나이가 58 → 298초로 자랐다 |
| 알림 | `DawnlineOutboxLag` 가 주문이 끝나기 전에 실제 Prometheus 에서 firing |
| 릴레이 리더 | 5 그대로 — 브로커 부재는 리더십과 무관하다(리더십은 자기 DB 의 advisory lock, ADR-027 후속 정정) |
| 운영자 조기 마감 | 200 · 감사 `SUCCEEDED` — 코어는 DB + outbox 로 받는다. `wave.closed` 는 복구 뒤에 나가 계획까지 갔다 |
| 장애 중의 검증 표 | V1 빠진 주문 1,200 · V5 「모름」(브로커가 없어 DLQ 끝 오프셋을 못 읽는다 — 0 으로 접지 않는다) · V7 order 1200/0 — **검사가 유실을 볼 수 있다** |
| 복구 뒤 | 40초 안에 미발행 0 · 알림 꺼짐. 검증 표 전부 ✅ — 1,200 = 후보 1,199 + 배차 불가 1(`OUT_OF_STOCK`, 시드) · DLQ 0 · outbox 격리 0 |

**읽는 법** — 브로커가 없으면 쌓이는 것은 **outbox** 이지 주문이 아니다. 그래서 첫 신호는 소비 랙이 아니라 `DawnlineOutboxLag` 다(랙은 브로커와
함께 보이지 않는다 — exporter 도 브로커에 묻는다). 복구 뒤의 일은 따라오는지 보는 것 하나다 — 재처리할 것이 없다.

## 참조

- `docs/DESIGN.md` §4.4 · §4.6 · §6.7 · §8.2 · §8.4
- `libs/messaging/.../OutboxRelay.java` · `AdvisoryLockRelayLeadership.java` · `DawnlineErrorHandlers.java`
