# RB-07 — 감사 `UNKNOWN` · 오래된 `PENDING` 해소

| 항목 | 내용 |
|---|---|
| 대상 | ops-api 의 운영자 커맨드 중 코어에 **적용됐는지 모르는** 것 — `audit_logs.result` 가 `UNKNOWN`, 또는 `PENDING` 인 채 오래된 행 |
| 알림 | `DawnlineOpsCommandUnknown` ([README](README.md) 1) — `DLQ_REPLAY` 는 제외(다시 누르는 것이 해소다, RB-05 §2.3) |
| 관련 설계 | §5.5(커맨드 위임 — 결과 넷, 상관 헤더 · 감사 해소) · §9.3(`auditId` · 수신 줄) · §9.5 · ADR-052 재검토 지점 4 · ADR-054 · ADR-065 |

**먼저 본다 — SQL** 해소할 행(해소 행이 없는 것) — 행의 `id` 가 코어 로그의 `auditId` 다:

```bash
sql ops "SELECT a.id, a.action, a.target_type, a.target_id, a.actor, a.request, a.result, a.created_at
           FROM audit_logs a
          WHERE (a.result = 'UNKNOWN' OR (a.result = 'PENDING' AND a.created_at < now() - interval '5 minutes'))
            AND a.action <> 'DLQ_REPLAY'
            AND NOT EXISTS (SELECT 1 FROM audit_logs r WHERE r.action = 'RESOLVE_AUDIT' AND r.target_id = a.id
                                                         AND r.result = 'SUCCEEDED')
          ORDER BY a.created_at"
```

명령의 `dc` · `prom` · `logs` · `sql` 은 [README](README.md) 의 공통 준비다.

---

## 0. 왜 모르는가

ops-api 는 감사 행을 `PENDING` 으로 **먼저** 커밋하고 코어를 부른다(§5.5). 결과는 넷이고 모름을 값으로 접지 않는다 — `FAILED` 는 **연결이 맺어지지
않았다**(요청이 코어에 닿지 않은 것이 확실한 유일한 경우)이고, 응답 전 타임아웃 · 응답 도중 끊김 · **코어의 5xx** 는 전부 `UNKNOWN` 이다. 5xx 는
「아무 일도 없었다」가 아니다 — 커밋 뒤 직렬화에서 난 예외도 500 이다. `PENDING` 이 오래 남은 것은 결과를 쓰기 전에 ops-api 가 죽었거나 결과
쓰기에 실패한 것이다(로그 「감사 결과를 쓰지 못했다 — 행은 PENDING 으로 남는다(RB-07)」).

## 1. 다시 누르기가 먼저인 둘 — `CLOSE_WAVE` · `REQUEUE_OUTBOX`

두 코어 커맨드는 이미 적용된 상태에서 **409 로 지금 위치를 말한다.** 같은 인자로 다시 누르고 응답을 본다 — 다시 누른 요청은 새 감사 행으로 남는다.

| 커맨드 | 다시 누른 응답 | 앞의 행 |
|---|---|---|
| `CLOSE_WAVE` | 409 `wave-not-open`, `closeCause=MANUAL` | 앞의 요청이 적용됐다 — **같은 웨이브의 다른 `CLOSE_WAVE` 행이 없는지** 먼저 본다(아래 SQL). 없으면 `SUCCEEDED` |
| | 409 `wave-not-open`, `closeCause=SCHEDULED` | 스케줄러가 먼저 닫았다 — 앞의 요청은 적용되지 않았다. `FAILED` |
| | 409 `not-next-wave` | 대상은 열려 있고 더 이른 열린 웨이브가 있다 — 앞의 요청도 같은 판정으로 적용되지 않았다. `FAILED`. 닫을 웨이브를 다시 고른다(`earlierWaveId`) |
| | 200 | 앞의 요청은 적용되지 않았고 **지금 적용됐다** — 새 행이 `SUCCEEDED`, 앞의 행은 `FAILED` |
| `REQUEUE_OUTBOX` | 409 `not-quarantined`, `currentState` `PENDING` · `PUBLISHED` | 풀려 있다 — 앞의 요청이 적용됐다. `SUCCEEDED` |
| | 200 | 앞의 요청은 적용되지 않았고 지금 적용됐다. 앞의 행은 `FAILED` |

```bash
sql ops "SELECT id, actor, result, created_at FROM audit_logs
          WHERE action = 'CLOSE_WAVE' AND target_id = '<waveId>' ORDER BY created_at"
```

**조기 마감을 다시 누르기 전에** — 그 웨이브가 이미 닫혔다면 409 로 끝나 부작용이 없다. 열려 있다면 다시 누르는 것은 **마감을 실제로 하는 것**이다 —
그 결정이 여전히 맞는지(컷오프 전인지, 그 캠프 · 티어에 열린 웨이브가 남는지 — [README](README.md) 2.3 「예방」) 보고 누른다.

## 2. 코어의 흔적을 보는 셋 — `RUN_PLAN` · `REASSIGN_STOP` · `CANCEL_ORDER`

코어는 ops-api 가 싣는 `X-Dawnline-Audit-Id` 를 MDC `auditId` 로 남기고, **그 헤더가 온 요청마다 처리 전에 수신 줄 하나**를 남긴다(§9.3
「수신 줄」, 2026-09-26 — ADR-065 결정 4). 먼저 그 줄을 찾는다:

```bash
logs <service> 24h | grep '"auditId":"<id>"'
# 수신 줄: "운영자 커맨드를 받았습니다: POST /api/v1/…"
```

| 수신 줄 | 뜻 | 다음 |
|---|---|---|
| **없다** (그 코어가 그 시각에 떠 있었고 로그가 남아 있다) | 요청이 코어에 **닿지 않았다** — 적용되지 않았다 | `NOT_APPLIED` 로 닫는다(3) |
| 있다 | 닿았다. **수신은 적용이 아니다** — 거절됐을 수도, 처리 중 5xx 였을 수도 | 아래 성공의 줄, 없으면 코어의 현재 상태 |

2026-09-26 전의 행은 수신 줄이 없던 때다 — 그 행에는 「줄이 없다」가 아무것도 말하지 않는다. 바로 현재 상태로 간다.

성공의 줄(`auditId` 를 싣는다):

| 커맨드 | 성공의 줄 | 거절(4xx) · 멱등 무동작 |
|---|---|---|
| `REASSIGN_STOP` | dispatch 「stop 재배정: orderId=…」 | 수신 줄뿐 |
| `CANCEL_ORDER` | order 「주문을 취소했습니다. orderId=…」 | 수신 줄뿐 |
| `RUN_PLAN` | **없다** — 재실행(웹 경로)은 성공도 INFO 줄을 남기지 않는다. 「웨이브 계획: … 결과=…」는 `wave.closed` 리스너의 줄이다 | 수신 줄뿐 — 바로 현재 상태로 |
| (참고) `CLOSE_WAVE` · `REQUEUE_OUTBOX` | 「웨이브를 수동 마감했습니다」 · 「outbox 격리를 풀었습니다」 | 수신 줄뿐 — 그래서 1 의 다시 누르기가 먼저다 |

**코어의 현재 상태** — 수신 줄은 있는데 성공의 줄이 없을 때의 근거다:

| 커맨드 | 상태를 보는 곳 |
|---|---|
| `RUN_PLAN` | `sql dispatch "SELECT status, finished_at, failure_reason FROM route_plans WHERE wave_id = '<target_id>'"` — `PUBLISHED` 이고 `finished_at` 이 행의 `created_at` 뒤면 그 요청이 적용됐다. 앞이면 이미 발행돼 있었다(멱등 — 적용할 것이 없었다). **다시 눌러도 안전하다** — `PUBLISHED` 면 `ALREADY_PUBLISHED` 로 아무것도 하지 않는다([RB-04](RB-04-plan-stall-and-rerun.md) §1.2) |
| `REASSIGN_STOP` | ops-api `GET /api/v1/routes/{routeId}`(dispatch 조회 위임) — 그 주문이 요청의 목적지 라우트에 있는가. **다시 누르지 않는다** — 이미 옮겨졌다면 두 번째가 무엇을 할지 이 런북이 보장하지 않는다 |
| `CANCEL_ORDER` | `sql order "SELECT status, updated_at FROM orders WHERE id = '<target_id>'"` — `CANCELLED` 면 적용됐다 |

## 3. 닫기 — 해소 행을 남긴다

앞의 행은 **고치지 않는다.** 판정을 새 감사 행으로 남긴다(ADR-065) — 누가 · 언제 · 무엇을 근거로가 같은 표에 남는다:

```bash
TOKEN=$(make -s token ROLE=OPS_OPERATOR ACTOR=<내 이름>)
curl -s -X POST -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' \
  -d '{"resolution":"<APPLIED|NOT_APPLIED>","reason":"<근거 — 다시 누른 행의 id · 로그 줄 · 현재 상태 질의>"}' \
  "http://localhost:$OPS_API_PORT/api/v1/audit/<id>/resolve" | jq
```

| 응답 | 뜻 |
|---|---|
| 200 | 해소 행이 적혔다 — 응답의 `auditId` 가 그 행, `targetAuditId` 가 앞의 행 |
| 409 `audit-already-resolved` | 누군가 이미 닫았다 — `resolutionId` 의 행을 본다. 판정이 다르면 그것은 사건이다(포스트모템) |
| 409 `audit-not-resolvable` | 대상이 `UNKNOWN` 도, 5분 넘은 `PENDING` 도 아니다 — 닫을 것이 없다(아직 도는 `PENDING` 이면 기다린다) |
| 404 | 그런 감사 행이 없다 |

- `resolution` 은 **적용됐는가**다 — `APPLIED` · `NOT_APPLIED`. 1 의 표에서 「`SUCCEEDED`」는 `APPLIED`, 「`FAILED`」는 `NOT_APPLIED` 다.
- `reason` 은 필수(200자까지)다. 무엇을 근거로 닫았는지 적는다 — 그 칸이 이제 감사 표 안에 있다.
- 409 · 404 도 `result=REJECTED` 인 해소 행으로 남는다. 카운터(`dawnline_ops_commands_total`)는 앞의 `UNKNOWN` 을 되돌리지 않는다 — 그것은
  「그때 모른다고 적었다」의 수이고, 그 자체가 사실이다. 해소는 `action="RESOLVE_AUDIT"` 로 따로 센다.
- 이 일을 코드로 옮기는 것(ops-api 가 코어 상태를 다시 읽어 닫기)은 `UNKNOWN` 이 실제로 쌓이면 연다 — ADR-052 재검토 지점 4. 그때도 같은 행을 쓴다.

## 참조

- `docs/DESIGN.md` §5.5 · §9.3 · §9.5
- [ADR-052](../adr/ADR-052-delegation-client-is-generated-from-the-committed-contract.md) · [ADR-054](../adr/ADR-054-early-wave-close-is-an-operator-cutoff.md) · [ADR-065](../adr/ADR-065-audit-resolution-is-a-row.md)
