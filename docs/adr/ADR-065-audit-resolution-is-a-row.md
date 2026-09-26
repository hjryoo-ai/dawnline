# ADR-065 — 감사 해소는 칸이 아니라 행이다: `RESOLVE_AUDIT`, 그리고 코어는 커맨드를 받았다고 말한다

| 항목 | 내용 |
|---|---|
| 상태 | Accepted (2026-09-26) |
| 결정일 | 2026-09-26 (방향은 2026-09-25 사용자 결정 — IMPLEMENTATION_PLAN 7-3b) |
| 관련 문서 | `docs/DESIGN.md` §5.5 「커맨드 위임」 · §9.1 · §9.3 · `docs/runbooks/RB-07-audit-unknown.md` |
| 관련 ADR | [ADR-052](ADR-052-delegation-client-is-generated-from-the-committed-contract.md) (결과 넷 · 재검토 지점 4 — 자동 해소) · [ADR-054](ADR-054-early-wave-close-is-an-operator-cutoff.md) (조기 마감) |

---

## 맥락

ops-api 는 운영자 커맨드의 감사 행을 `PENDING` 으로 먼저 커밋하고 코어를 부른다. 결과는 넷이고 모름을 값으로 접지 않는다 —
타임아웃 · 응답 도중 끊김 · 코어의 5xx 는 `UNKNOWN` 이다(§5.5). 그 `UNKNOWN` 을 사람이 닫는 절차가 RB-07 이었고, 마지막 단계가
**SQL `UPDATE`** 였다:

```sql
UPDATE audit_logs SET result = '<SUCCEEDED|FAILED>' WHERE id = '<id>' AND result IN ('UNKNOWN', 'PENDING')
```

세 가지가 걸렸다.

1. **감사 행을 고친다.** 「그때 모른다고 적었다」는 사실이 지워진다. 카운터(`dawnline_ops_commands_total{result="UNKNOWN"}`)는
   되돌리지 않으니 표와 카운터가 서로 다른 말을 한다.
2. **근거를 적을 칸이 없다.** RB-07 §3 은 「무엇을 근거로 닫았는지는 사건 기록(티켓 · 포스트모템)에 남긴다」고 적었다 — 누가 · 언제 ·
   왜가 감사 표 밖에 있었다. 감사 표가 감사를 못 하는 유일한 커맨드가 감사 해소였다.
3. **근거를 찾기 어렵다.** 코어는 커맨드의 **성공**만 INFO 로 남기고, `RUN_PLAN` 은 성공도 남기지 않는다(RB-07 §2 — 관측: 2026-09-25 발행된
   웨이브에 보낸 `RUN_PLAN` 의 `auditId` 를 싣는 줄 0). 「줄이 없다」는 닿지 않았다 · 거절됐다 · 줄 없이 적용됐다 셋을 가르지 못했다.

실제 사례가 둘 있다 — 7-3① · ② 의 `make chaos-db` 가 fulfillment DB 가 멈춘 동안 보낸 조기 마감이 504 → `UNKNOWN`(2026-09-25 12:33 · 2026-09-26
00:14, 로컬). 인위 주입이 아니다.

## 결정

### 1. 해소는 새 행이다 — `UNKNOWN` 행은 그대로 둔다

`POST /api/v1/audit/{auditId}/resolve` `{resolution, reason}` 이 감사 행 **하나를 더한다**:

| 칸 | 값 |
|---|---|
| `action` · `target_type` · `target_id` | `RESOLVE_AUDIT` · `AUDIT` · 해소할 행의 id |
| `request` | `{resolution, reason}` — 이 커맨드의 인자다(§5.5 「`request` 에는 인자만」 그대로) |
| `actor` | 토큰의 `sub` — 커맨드와 같다(`OPS_OPERATOR` 이상) |
| `result` | `SUCCEEDED`(해소를 적었다) 또는 `REJECTED`(대상이 해소할 수 있는 행이 아니다 — 아래 3) |

대상 행은 **고치지 않는다.** 감사 표는 덧붙이기만 한다. 「지금 열린 `UNKNOWN`」은 해소 행이 없는 `UNKNOWN` 이다(RB-07 의 첫 SQL).

### 2. 해소의 값은 `APPLIED` · `NOT_APPLIED` 둘이다 — 결과 넷을 빌려 쓰지 않는다

RB-07 은 `SUCCEEDED` · `FAILED` 로 닫았다. 그런데 그 둘은 위임 결과의 이름이고 뜻이 좁다 — `SUCCEEDED` 는 「코어가 2xx 로 답했다」,
`FAILED` 는 「**연결이 맺어지지 않았다**」다(§5.5). 해소가 말하는 것은 「그 커맨드가 코어에 **적용됐는가**」이고, `FAILED` 로 닫은 조기
마감(연결은 맺어졌고 코어가 DB 에 못 들어가 504)은 `FAILED` 의 정의와 다르다. 이름을 빌리면 같은 칸의 같은 값이 행마다 다른 뜻이 된다.

### 3. 해소할 수 있는 행 — `UNKNOWN`, 그리고 5분 넘은 `PENDING`. 한 번만

- 대상이 `UNKNOWN` 이거나 `PENDING` 이고 5분이 지났다(RB-07 의 정의 그대로 — 위임 타임아웃은 최대 60초다). 아니면 409 `audit-not-resolvable`.
- 이미 `SUCCEEDED` 인 해소 행이 있으면 409 `audit-already-resolved`(그 행의 id 를 싣는다). **해소는 한 번이다** — 두 사람이 다른 판정을 적으면
  표가 두 말을 한다. 판정이 틀렸으면 그것은 사건이고 포스트모템이 다룬다(이 ADR 은 「해소의 정정」을 두지 않는다 — 재검토 지점).
- 두 요청이 동시에 오면 대상 행을 `FOR UPDATE` 로 잡은 뒤 판정한다 — 둘째는 첫째의 커밋을 보고 `audit-already-resolved`. ops-api 는
  JDBC 라 영속성 컨텍스트의 낡은 사본 문제(ADR-025 후속 정정)가 없다.
- 없는 행은 404. `reason` 이 비었거나 200자를 넘으면 400 이고 **행을 남기지 않는다**(조기 마감의 `reason` 과 같은 규칙).
- **거절도 행이다** — 409 · 404 는 `result=REJECTED` 인 해소 행을 남긴다(모든 커맨드는 `audit_logs` 에, 코어의 4xx 가 `REJECTED` 인 것과
  같다). 400 은 커맨드가 성립하지 않은 것이라 남기지 않는다.
- 한 트랜잭션이다(잠금 · 판정 · 삽입). 위임이 없으므로 `PENDING` 단계가 없다. 카운터(`action="RESOLVE_AUDIT"`)는 커밋 뒤에 센다.

### 4. 코어는 커맨드를 받았다고 말한다 — `MdcFilter` 의 INFO 한 줄

`X-Dawnline-Audit-Id` 가 정규 UUID 로 온 요청마다 `libs/observability` 의 `MdcFilter` 가 **처리 전에** 한 줄을 남긴다:
`운영자 커맨드를 받았습니다: POST /api/v1/…` — `auditId` 는 이미 MDC 에 있다. 코어 넷이 같은 필터를 쓰므로 한 자리다. 경로에는 id 만
있고(웨이브 · 주문 · 라우트 · outbox 행) 쿼리와 본문은 싣지 않는다(§9.3 개인정보).

그래서 RB-07 §2 의 판단이 바뀐다: **그 `auditId` 의 수신 줄이 없으면 닿지 않았다**(그 코어가 떠 있었고 로그가 남아 있다면). 있으면 닿았고,
적용 여부는 여전히 코어의 현재 상태가 말한다 — 수신은 적용이 아니다.

## 고려한 대안과 기각 이유

| 대안 | 기각 이유 |
|---|---|
| 대상 행의 `result` 를 고친다(RB-07 §3 그대로) · 칸(`resolved_by` · `resolution`)을 더한다 | 맥락 1 — 「그때 모른다고 적었다」가 사라진다. 칸을 더하면 마이그레이션과 함께 감사 행이 **변하는** 표가 된다 |
| 해소 값을 `SUCCEEDED` · `FAILED` 로 | 결정 2 — 위임 결과의 이름과 뜻이 갈라진다 |
| ops-api 가 코어 상태를 다시 읽어 자동으로 닫는다 | ADR-052 재검토 지점 4 — `UNKNOWN` 이 실제로 쌓이면 연다. 지금은 두 건이고 판정에 사람의 맥락(그 시각 NOLOGIN)이 들어간다. 자동 해소가 열리면 **같은 행**을 쓴다(`actor` 가 시스템) |
| 수신 줄을 각 코어의 컨트롤러에 | 네 코어 × 커맨드마다 한 줄 — 새 커맨드가 빠지면 그 커맨드만 흔적이 없다. 필터는 헤더가 온 요청 전부를 본다 |
| 수신 줄과 함께 완료 줄(상태 코드)도 | 계획이 정한 것은 한 줄이다. 완료 줄은 5xx 를 가르지 못한다(커밋 뒤의 5xx) — 현재 상태 질의를 대신하지 못하므로 더하는 값이 작다 |

## 결과

- 감사 표가 해소까지 감사한다 — 누가 · 언제 · 무엇으로 판정했는가가 한 표에 있다. RB-07 §3 의 SQL `UPDATE` 가 사라진다.
- `dawnline_ops_commands_total{action}` 에 `RESOLVE_AUDIT` 가 는다(닫힌 값 — §9.1 · 카탈로그 · 기동 때 미리 등록).
- 대가: 「열린 `UNKNOWN`」을 세려면 해소 행을 빼는 질의가 필요하다(RB-07 첫 SQL). `audit_logs` 에는 `target_id` 인덱스가 없고 해소 조회는
  순차 스캔이다 — 행 수가 운영자 커맨드 수라(설계상 상한이 없는 유일한 표, §7.1 보존 표) 지금 규모에서 맞다. 재검토 지점.

## 재검토 지점

- `audit_logs` 가 수만 행을 넘으면 해소 조회(`action='RESOLVE_AUDIT' AND target_id=?`)의 계획을 EXPLAIN 으로 다시 본다(불변규칙 11).
- 해소의 판정이 틀린 사례가 나오면 「해소의 정정」(해소 행을 대상으로 하는 해소)을 연다 — 지금은 두지 않는다.
- 자동 해소(ADR-052 재검토 지점 4)가 열리면 이 행을 쓴다.
