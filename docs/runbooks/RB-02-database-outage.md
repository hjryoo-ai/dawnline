# RB-02 — DB 장애

| 항목 | 내용 |
|---|---|
| 대상 | PostgreSQL — 로컬은 컨테이너 하나에 서비스별 데이터베이스 다섯(`dawnline_order` · `_fulfillment` · `_dispatch` · `_tracking` · `_ops`) |
| 알림 | 전용 알림이 없다 — **`DawnlineOutboxLag` 가 먼저 운다**(릴레이가 판정 불가로 멈춘다). 뒤따르는 것: `DawnlineConsumerLag` · `DawnlineDlqNew` · `DawnlineKpiRefreshStale`(ops DB) |
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
| 그 서비스의 소비 | 리스너가 커넥션을 기다리며 사실상 멈추고, **몇 건은 재시도 3회 뒤 DLQ** 로 간다 — 아래 3 |
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

## 3. 장애 창에서 몇 건은 DLQ 로 간다 — 대부분은 브로커에서 기다린다

리스너를 멈추는(`pause`) 코드는 없다 — §8.4 표의 「소비자 재시도 후 pause」는 구현과 다르다. 소비 중의 DB 예외는 일시적 오류로 분류되어
백오프 3회(200 ms · 1 s · 5 s)를 재시도하고, 소진되면 그 레코드는 DLQ 로 간다(§4.6, `DawnlineErrorHandlers`). 그러나 **시도 한 번이 커넥션
풀의 대기 시간(Hikari 기본 30초)을 다 쓴다** — 네 번의 시도가 레코드 하나에 약 2분이고, 그 동안 리스너 스레드는 그 레코드에 묶여 뒤의
레코드를 읽지 않는다. 그래서 풀 대기가 사실상의 멈춤이 되고, DLQ 로 가는 것은 **장애 시간 ÷ 약 2분 × 막힌 리스너 스레드 수**만큼이다.

**관측(재현됨, 2026-09-25 로컬)** — fulfillment 계정의 로그인을 막고(`ALTER ROLE … NOLOGIN` + 세션 종료) 주문 200건을 넣었다. 5분 20초 동안:
릴레이 리더 `-1`, 로그 「Connection is not available, request timed out after 30001ms」, DLQ 는 넣고 약 2분 뒤부터 두 건씩 **6건**. 로그인을
돌려주자 30초 안에 나머지 **194건**이 `ok` 로 처리됐다(200 = 194 + 6). 6건은 아래 2 로 재처리했고 전부 `SUCCEEDED` · 원래 그룹 `ok` · 다른
그룹 `replay_not_target` 이었다.

그래서 DB 복구 뒤의 일은 **남은 몇 건의 재처리**다:

1. `prom 'sum by (consumer, eventType) (increase(dawnline_event_processed_total{outcome="dlq"}[1h]))'` — 장애 창의 DLQ 가 어느 그룹의 무엇인가.
2. [RB-05](RB-05-dlq-and-outbox-quarantine.md) §2 — 원인(DB)은 이미 고쳤으므로 그대로 재처리한다. 재처리는 원래 그룹에게만 가고 멱등이다.
3. **`order.placed` 는 24시간 안에 재처리한다** — 넘기면 `STALE_PLACED` 로 종결된다(RB-05 §2.2).
4. **DLQ 로 간 건은 뒤의 건보다 늦게 처리된다** — 같은 주문의 `order.cancelled` 가 먼저 처리됐을 수 있다. 소비자의 역행 무시와 취소 선착
   경로가 흡수한다(§4.6 · ADR-022). 「재처리했는데 상태가 그 이벤트의 것이 아니다」는 정상일 수 있다.

장애가 길수록 DLQ 몫이 늘고, 풀 대기 시간을 줄이면 같은 장애에서 DLQ 로 가는 건이 늘어난다 — 두 설정(백오프 · 풀 대기)이 함께 정하는 값이다.

## 4. 확인

- `dawnline_outbox_leader` 가 서비스마다 `1` 하나 · `dawnline_outbox_lag_seconds` 가 SLO 안(RB-01 §1.4).
- `hikaricp_connections_pending` 이 0.
- 장애 창의 DLQ 를 재처리했고 원래 그룹의 `outcome="ok"` 가 올랐다.

## 참조

- `docs/DESIGN.md` §4.6 · §7.1 · §8.4 · §8.6
- [ADR-027](../adr/ADR-027-outbox-relay-leader-lock.md) — 리더십이 DB 에 있는 이유(판정 불가 = 발행할 행도 못 읽음)
