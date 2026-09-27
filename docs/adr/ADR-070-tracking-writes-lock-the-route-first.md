# ADR-070 — tracking 의 쓰기는 라우트 행을 먼저 잡는다 · 편차는 라우트 행에 한 번 적는다

| 항목 | 내용 |
|---|---|
| 상태 | Accepted (2026-09-28) |
| 결정일 | 2026-09-28 |
| 관련 문서 | `docs/DESIGN.md` §5.4 · `docs/benchmarks/phase7-window-scenarios.md` §3.2 · §3.3 · `docs/IMPLEMENTATION_PLAN.md` 7-0 B11 |
| 관련 ADR | [ADR-045](ADR-045-revision-comparison-is-per-route.md) (라우트당 한 행 — 이 ADR 이 그 행을 잠금의 부모로 쓴다) · [ADR-047](ADR-047-delivery-status-is-a-fact-not-a-revision.md) (스캔은 주문으로 푼다) · [ADR-058](ADR-058-shipment-and-read-model-retention.md) (`ix_ship_updated` — HOT 을 막는 인덱스) · [ADR-068](ADR-068-replan-write-locks-and-moves-rows.md) (dispatch 의 쓰기 순서 — 이쪽은 다른 표, 다른 순서다) |

---

## 맥락

7-4 의 창 시나리오가 tracking 에서 둘을 드러냈다.

1. **교착 — 스캔과 개정 반영이 한 라우트의 배송들을 서로 다른 순서로 잡는다.** `normal-day` 에서 tracking DB 의 `deadlocks` 가 1 이었고 스캔 하나가
   500 으로 나갔다(리포트 §3.2). 그때는 짝을 몰랐다. **근거: 관측(재현됨)** — `ScanRevisionRaceIT` 를 먼저 쓰고 정정 전 코드에서 돌렸다:
   스캔이 첫 stop 의 배송을 쓴 채(편차 전파의 자동 flush) 멈춘 동안 같은 라우트의 개정을 반영하자 `deadlock detected … while updating tuple in
   relation "shipments"` 였다.
   - 스캔은 **stop 순서**로 잡는다 — 찍은 배송(전파 질의 직전의 자동 flush), 그 뒤 `ORDER BY stop_seq` 로 읽은 뒤따르는 배송들(커밋의 flush 가
     읽은 순서로 나간다).
   - 개정 반영은 **주문 id 순서**로 잡는다 — `findAll … ORDER BY order_id` 로 읽고 그 순서로 flush 한다.
   - 결정 전의 가설은 「ETA 전파의 갱신 순서가 고정돼 있지 않다」였다(근거: 추정). 관측은 반대였다 — 전파는 이미 stop 순이고, 다른 쪽이 주문 순이다.
2. **쓰기의 양 — 스캔 하나가 뒤 stop 전부를 다시 쓴다.** `shipments` 는 네 실행 모두 HOT 갱신 0 이었고 배송 하나가 37–94 번 고쳐졌다(리포트
   §3.3). **근거: 관측(재현됨)** — `ScanWriteVolumeIT` 의 그림자: 30-stop 라우트를 출발 · 도착 · 완료로 끝까지 돌리면 `shipments` 의 행 갱신이
   **990** 이다(배송당 33). 상태 전이만이면 90 이다. 도착 · 완료마다 편차가 바뀌어 뒤 stop 의 `eta_at` 을 모두 다시 쓰기 때문이다 — 라우트당 O(n²).
   tracking 풀 대기(최대 60–191)가 기사 스캔 구간에만 있는 것도 같은 자리다.

HOT 이 0 인 이유는 쓰기의 양이 아니다: `ix_ship_updated (updated_at)` 가 매 갱신마다 값이 바뀌는 칸에 걸려 있어 HOT 을 **구조적으로** 막는다 —
V4 의 주석이 그것을 예고했다. 그래서 이 ADR 이 줄이는 것은 HOT 비율이 아니라 갱신 수다.

## 결정

1. **tracking 의 쓰기 계층은 라우트 행(`route_revisions`) → `shipments` 이고, 모든 쓰기 경로가 이 순서를 지킨다.** 부모 행을 먼저 잡으면 같은
   라우트의 쓰기가 거기서 줄을 서고, 자식(배송)을 어떤 순서로 잡든 교착이 생기지 않는다 — 표준 잠금 계층이다.
   - **스캔**: 대상 배송을 읽은 뒤, 그 배송들이 **지금 있는** 라우트(ADR-047 결정 1)의 행을 id 순으로 `FOR UPDATE` 한다 — 배송을 고치기 전에.
     라우트는 배송이 말하므로 읽기가 먼저다. 읽은 뒤 잠금을 기다리는 사이 개정이 커밋했으면 배송은 옛 사본이고, flush 의 `version` 검사가
     낙관적 실패를 내 스캔이 새 트랜잭션으로 다시 한다(아래 결정 3 — 이미 있는 경로다).
   - **개정 반영**(재계획의 결과도 tracking 에는 `route.assigned` 개정으로 온다): 첫 문장이 `route_revisions` 의 claim 이다(`INSERT … ON CONFLICT
     DO UPDATE … WHERE revision < ?` — 조건이 거짓이어도 충돌한 행은 잠긴다). 배송은 그 뒤다.
   - **보존 정리**: 한 트랜잭션이 한 층만 지운다(`shipments` 배치와 `route_revisions` 배치가 따로 돈다) — 두 층을 한 트랜잭션에 잡지 않으므로 순서가 없다.
   - **다음 쓰기 경로**가 생기면 이 문장을 지킨다. 없으면 교착이 돌아온다.
2. **편차는 라우트 행에 한 번 적는다 — `route_revisions.deviation_seconds`.** 편차는 라우트의 사실이다(§5.4 「편차 전파는 애그리거트 밖」 의 근거
   그대로). 배송의 ETA 는 저장하지 않고 **planned + 편차**로 읽는 자리(at-risk 판정 · 페이로드)에서 계산한다 — `shipments.eta_at` 을 지운다(V5).
   - 스캔당 쓰기가 O(1) 이 된다: 찍은 배송의 상태 전이와 라우트 행 하나.
   - 값이 같으면 쓰지 않는다(`IS DISTINCT FROM`) — 이전의 `projectEta` 가 같은 값에 `false` 를 돌려주던 것과 같다.
   - 개정 반영은 편차를 **0** 으로 되돌린다 — 지금 `applyRevision` 이 `eta_at` 을 `planned_arrival` 로 되돌리는 것과 같은 뜻이다(새 개정의 계획
     시각이 이미 그 시점의 사정을 담는다).
   - **뜻이 하나 바뀐다**: 지나온 비종결 stop(찍고 아직 끝나지 않은 stop, 건너뛴 stop)도 같은 편차를 받는다. 이전에는 그 행이 앞선 전파의 값을
     들고 있었다. 그 값은 「이미 지나간 곳에 언제 도착할까」라서 어느 물음에도 답하지 않았다 — at-risk 의 남은 stop 목록에서만 읽히고, 거기서는
     라우트의 지금 편차가 더 맞는 값이다.
3. **스캔 재시도는 교착과 잠금 실패도 다시 한다 — 안전망이다.** `ContendedScanRetry` 가 `OptimisticLockingFailureException` 에 더해
   `PessimisticLockingFailureException` 을 잡는다(PostgreSQL 40P01 은 `CannotAcquireLockException` 으로 온다 — 관측). 결정 1 뒤에 교착이
   다시 보이면 그것은 재시도가 덮을 일이 아니라 순서를 어긴 새 쓰기 경로의 신호다 — B16 과 같은 자리(`pg_stat_database.deadlocks`)가 본다.
4. **B11 은 갱신 수와 풀 대기로 판정한다.** HOT 비율은 `ix_ship_updated` 가 정한다(맥락). BRIN 은 결정 2 뒤에도 풀 대기가 남을 때만 다시 본다.

## 근거

- **자식의 순서를 고정하는 것은 불완전하다.** 개정 반영을 stop 순으로 바꾸면 이 IT 는 초록이 되지만, 재계획은 stop 의 번호를 바꾸고(옛 번호와 새
  번호 중 무엇으로 정렬하나) relocate 는 다른 라우트의 배송을 건드린다. 부모를 먼저 잡으면 그 질문이 사라진다.
- **비용은 0 이다.** 결정 2 로 스캔은 어차피 라우트 행을 쓴다 — 잠금을 먼저 잡는 것은 같은 행을 더 일찍 잡는 것이다.
- **ETA 를 저장하면 둘째 출처다.** 편차는 라우트에 하나인데 배송마다 적으면 같은 사실이 n 곳에 있고, 그 n 곳을 맞추는 쓰기가 O(n²) 의 정체였다.

## 고려한 대안과 기각 이유

| 대안 | 기각 이유 |
|---|---|
| 개정 반영을 stop 순으로 flush(교착의 짝만 고친다) | 재계획의 번호 변경 · relocate 의 다른 라우트 행 — 순서의 키가 쓰기 도중에 바뀐다 |
| 배송 행을 전부 id 순으로 먼저 잠근다 | 스캔마다 라우트 전체를 잠근다 — 부모 행 하나로 같은 효과 |
| `SERIALIZABLE` | 교착을 직렬화 실패로 바꿀 뿐이고, 재시도가 전파의 O(n²) 까지 다시 한다 |
| 재시도만(결정 3 만) | 증상이다 — 교착은 그대로 나고 풀 대기는 그대로다 |
| `eta_at` 을 남기고 쓰지 않는다 | 읽는 쪽이 어느 값을 믿을지 모르는 둘째 출처가 남는다 |
| BRIN 부터(리포트 §4 의 처음 안) | HOT 을 막는 것은 인덱스 종류가 아니라 매 갱신마다 바뀌는 칸이고, 풀 대기의 출처는 갱신의 양이다 |

## 결과

- **장점**: 같은 라우트의 쓰기가 교착하지 않는다. 스캔당 쓰기가 O(1) 이다 — 30-stop 라우트의 그림자 990 → (아래 검증).
- **비용**: 같은 라우트의 스캔과 개정 반영이 라우트 행에서 줄을 선다 — 이전에는 서로 다른 배송을 건드리면 동시에 갔다. 한 라우트의 기사는 하나라
  스캔끼리의 줄은 거의 없고, 개정은 라우트당 드물다(근거: 추정 — turbulent 실행의 tracking 풀 대기가 본다). 스캔이 잠그기 전에 읽은 배송이
  낡으면 한 번 더 한다(결정 1).
- **되돌리는 방법**: V6 로 `eta_at` 을 되살리고 `planned_arrival + deviation_seconds` 로 채운 뒤 전파 쓰기를 되돌린다. 잠금 순서는 코드뿐이다.

## 검증

| 표본 | 기대 | 결과 |
|---|---|---|
| `ScanRevisionRaceIT` — 스캔이 첫 stop 을 쓴 채 멈춘 동안 같은 라우트의 개정(주문 id 가 방문 순서의 반대) | 교착 0, 스캔 200, 개정 적용 | 정정 전: 교착 1(개정 쪽이 `CannotAcquireLockException`) — (구현 커밋에서 채운다) |
| `ScanWriteVolumeIT` — 30-stop 라우트를 출발 · 도착 · 완료로 끝까지 | `shipments` 갱신 = 90(배송마다 셋), 라우트 행 갱신 ≤ 스캔 수 61 | 정정 전: `shipments` 990 · 라우트 행 0 — (구현 커밋에서 채운다) |
| 음성 표본 — 스캔의 라우트 행 잠금을 뺀다 | `ScanRevisionRaceIT` 빨강 | (구현 커밋에서 채운다) |
| 음성 표본 — 재시도에서 `PessimisticLockingFailureException` 을 뺀다 | 교착 재시도 단위 테스트 빨강 | (구현 커밋에서 채운다) |
