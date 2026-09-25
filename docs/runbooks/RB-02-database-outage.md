# RB-02 — DB 장애

| 항목 | 내용 |
|---|---|
| 대상 | PostgreSQL — 로컬은 컨테이너 하나에 서비스별 데이터베이스 다섯(`dawnline_order` · `_fulfillment` · `_dispatch` · `_tracking` · `_ops`) |
| 알림 | **`DawnlineOutboxLag` 가 먼저 운다**(릴레이가 판정 불가로 멈춘다). 30분이 넘으면 `DawnlineConsumerRetryStuck`(재시도 나이). 뒤따를 수 있는 것: `DawnlineKpiRefreshStale`(ops DB). `DawnlineDlqNew` 는 **울지 않는다** — DB 장애는 DLQ 로 가지 않는다(§3) |
| 경계 | **일시적 실패가 30분 넘게 이어지면 그건 장애가 아니라 설정이다** — 틀린 자격 증명 · 지워진 표 · 틀린 판정. 소비자는 일시적 실패를 끝없이 재시도하므로 기다림에 끝이 없다. 끝을 정하는 것은 사람이고, 그 문턱이 `DawnlineConsumerRetryStuck` 의 30분이다([ADR-015 후속 정정](../adr/ADR-015-outbox-publish-side-quarantine.md)). `db_integrity` 가 반복되면 결정적일 가능성이 높다 — 데이터를 고치거나 그 레코드를 격리한다(사람이 거는 격리 경로는 아직 없다 — 필요해지면 [ADR-053](../adr/ADR-053-dlq-replay-is-addressed-to-the-failed-group.md) 의 재검토로 연다) |
| 관련 설계 | §7.1 · §8.4(「PostgreSQL 다운」 행) · §8.6(레디니스는 마이그레이션 완료만) · ADR-027 |

**먼저 본다 — 메트릭** 어느 서비스가 DB 를 잃었나 — `prom 'dawnline_outbox_leader == -1'` 과 `prom 'max by (service) (hikaricp_connections_pending)'`.
`-1` 은 「리더십을 판정할 수 없다」이고, 릴레이는 DB 세션 없이는 판정할 수 없다. 다섯이 다 `-1` 이면 인스턴스(컨테이너)가, 하나면 그 데이터베이스나
그 계정이 문제다.

명령의 `dc` · `prom` · `logs` · `sql` 은 [README](README.md) 의 공통 준비다.

---

## 1. 무엇이 멈추고 무엇이 계속되나

| | DB 가 없는 동안 |
|---|---|
| 그 서비스의 HTTP | 5xx. 레디니스는 마이그레이션 완료만 보므로 이미 뜬 인스턴스는 트래픽을 계속 받는다 |
| 그 서비스의 발행 | **멈춘다** — 리더십 판정 불가(`-1`), 발행할 행도 못 읽는다. 쓰이지 않은 행은 없으므로 잃는 이벤트도 없다 |
| 그 서비스의 소비 | **멈춘다** — 막힌 레코드를 끝없이 재시도하고 그 파티션의 뒤는 기다린다. DLQ 로는 가지 않는다 — 아래 3 |
| 다른 서비스 | 계속된다(DB 가 서비스마다 다르다). 로컬은 컨테이너가 하나라 다섯이 함께 멈춘다 |

## 2. 복구

```bash
dc ps postgres
dc exec -T postgres pg_isready
logs postgres 10m | tail -50
```

컨테이너가 멈췄으면 다시 띄운다. 볼륨(`dawnline_postgres-data`)은 그대로다 — **`down -v` 를 쓰지 않는다.**

```bash
dc up -d postgres
```

하나의 데이터베이스만 문제면 연결 수부터 본다 — 풀은 인스턴스당 10 + 릴레이 전용 세션 1 이다(§8.2):

```bash
sql admin "SELECT datname, usename, state, count(*) FROM pg_stat_activity GROUP BY 1, 2, 3 ORDER BY 4 DESC"
```

**돌아오면 저절로 되는 것**: 커넥션 풀이 다시 붙는다 · 릴레이가 다음 폴링에 락을 다시 쥔다(로그 「릴레이 리더가 됐습니다」, 게이지 `1`) ·
웨이브 마감 스케줄러가 다음 주기에 밀린 마감을 한다(「웨이브 마감 실행 실패. 다음 주기에 다시 시도합니다」가 멈춘다) · `PLANNING` 에 남은
계획은 10분 뒤 회수된다([RB-04](RB-04-plan-stall-and-rerun.md) §1) · 정리 배치는 다음 실행이 이어서 지운다.

## 3. 소비는 멈춰서 기다린다 — DLQ 로 가지 않는다

소비 중의 DB 예외는 **일시적**이다(ADR-015 후속 정정의 경계표 — `db_connection` · `db_resource` · `db_transient` 행). 일시적 실패는 백오프
(200 ms · 1 s · 5 s, 그 뒤 5초마다)로 **끝없이** 재시도하고, 그 파티션의 뒤는 기다린다. §8.4 가 「pause」라고 부르던 것이 이것이다 — 순서가
지켜지고, DB 가 돌아오면 스스로 따라온다. 한 번의 시도는 커넥션 풀의 대기(Hikari 30초)를 다 쓰므로 재시도는 약 35초에 한 번이다.

**관측(재현됨, 2026-09-25 로컬 — `make chaos-db`)** — fulfillment 계정의 로그인을 막고 세션을 끊은 채 주문 **1,200**건, 6분 13초(12:32:26 → 12:38:39 UTC):

| | 값 |
|---|---|
| DLQ | **0건**(`*.dlq` 끝 오프셋 합 36 → 36) |
| 복구 뒤 | 30초 안에 전부 처리 — 1,200 = 후보 1,023 + 배차 불가 177, 빠진 주문 0(검증 표 V1) |
| 재시도 | `dawnline_event_retry_total{reason=~"db_.*"}` 0 → 33 · `dawnline_event_retry_age_seconds` 최대 328초, 복구 뒤 0 |
| 장애 중 운영자 커맨드 | 조기 마감 하나 → 504, 감사 `UNKNOWN`([RB-07](RB-07-audit-unknown.md) — 적용될 수 없었다: 아무도 그 DB 에 들어가지 못했다) |

**브로커의 그룹 랙과 클라이언트의 랙 지표는 다르다** — 같은 실행에서 `kafka-consumer-groups --describe` 는 파티션마다 약 100(합 약 1,200)을
보였는데(`kafka_consumergroup_lag` — kafka-exporter 가 같은 값을 낸다, §11) 클라이언트 지표 `kafka_consumer_fetch_manager_records_lag` 의 합은 최대 500 이었다. 클라이언트 지표는 「브로커의 끝 − **가져온** 위치」라서, 이미
가져왔지만 재시도에 막힌 레코드를 세지 않는다. **멈춘 소비를 보는 것은 랙이 아니라 재시도의 나이다.** 그룹의 실제 랙은 위의 명령으로 본다
([RB-01](RB-01-kafka-recovery.md) §2.1).

그래서 DB 복구 뒤의 일은 **기다렸다가 따라왔는지 보는 것** 하나다 — 아래 4. 재처리할 것이 없다.

**그 전(7-5 의 재현)** — 같은 장애를 200건으로 냈을 때 6건이 DLQ 로 갔다(3회 재시도 뒤). 재처리하니 전부 `SUCCEEDED` 였다 — 독약이 아니었다.
그 관측이 이 절을 바꿨다(§8.4 · ADR-015 후속 정정).

## 4. 확인

- `dawnline_outbox_leader` 가 서비스마다 `1` 하나 · `dawnline_outbox_lag_seconds` 가 SLO 안(RB-01 §1.4).
- `hikaricp_connections_pending` 이 0.
- `dawnline_event_retry_age_seconds` 가 0 으로 돌아왔다 · 그룹의 랙이 풀렸다(`kafka-consumer-groups --describe`, RB-01 §2.1).
- DLQ 가 늘지 않았다 — 늘었다면 그것은 결정적 실패다(RB-05).
- **검증 표** — `make chaos-verify STATE=<기준 파일>` 이 카오스와 같은 표(V1–V7)를 낸다. 장애 전에 `tools/chaos/verify.sh baseline <파일>` 로
  기준을 남겨 두었다면 복구 뒤 그 파일로 잰다.

## 참조

- `docs/DESIGN.md` §4.6 · §7.1 · §8.4 · §8.6
- [ADR-027](../adr/ADR-027-outbox-relay-leader-lock.md) — 리더십이 DB 에 있는 이유(판정 불가 = 발행할 행도 못 읽음)
