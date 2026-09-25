# RB-03 — Redis 복구

| 항목 | 내용 |
|---|---|
| 대상 | Redis — 성능 · 조정 용도이고 진실 저장소가 아니다(불변규칙 7). 모든 키에 DB 폴백이 있다(§7.2 표) |
| 알림 | `DawnlineRateLimitBypassed` ([README](README.md) 1) |
| 관련 설계 | §7.2(키 카탈로그 · 폴백) · §8.4(「Redis 다운」 행) · §8.6(레디니스에 GEO 적재가 없다) · ADR-016 |

**먼저 본다 — 메트릭** 폴백이 누구에게서 일어나나 — `prom 'sum by (service) (rate(dawnline_geo_lookups_total{outcome="bypassed"}[5m]))'` ·
`prom 'rate(dawnline_rate_limit_decisions_total{outcome="bypassed"}[5m])'` · `prom 'rate(dawnline_at_risk_cooldown_bypassed_total[5m])'`.
여럿이면 Redis 자체(1), order-service 만이면 order-service 의 50 ms 예산(2).

명령의 `dc` · `prom` · `logs` · `sql` 은 [README](README.md) 의 공통 준비다.

---

## 0. 폴백이 도는 동안

**정확성은 그대로다 — 잃는 것은 셋이다.**

| 잃는 것 | 누가 | 크기 |
|---|---|---|
| **레이트 리밋** | order-service | 무인증 API 의 유일한 남용 방지 수단이 꺼진다(fail-open, §7.2). 이 알림의 이유다 |
| 조회 속도 | fulfillment | GEO · 권역 캐시 대신 DB 전체 조회 + 메모리 하버사인. 웨이브 마감 락 없이 진행하고 낙관적 락이 중복을 막는다 |
| at-risk 알림 수 | tracking | 쿨다운 없이 발행한다 — 알림이 늘지만 재계획은 dispatch 의 DB 쿨다운이 한 번으로 막는다(ADR-046) |

**그래서 `at-risk` 가 늘어 보이면 위험이 늘어난 것이 아니라 Redis 가 죽은 것일 수 있다** — `dawnline_at_risk_cooldown_bypassed_total` 이 그 구별이다.

## 1. Redis 가 죽었다

```bash
dc ps redis
dc exec -T redis redis-cli ping            # PONG
logs order-service 10m | grep 'Redis 호출에 실패해'   # 예외 — 연결 거부인가 타임아웃인가
```

멈췄으면 다시 띄운다. 볼륨은 그대로다 — **`down -v` 를 쓰지 않는다.** 키가 사라져도 정확성은 DB 로 회복된다(§7.2 원칙) — 비울 필요도, 복원할
필요도 없다.

```bash
dc up -d redis
```

**돌아오면 저절로 되는 것**:

- order-service 는 실패 뒤 `outage-bypass-ms`(기본 10초) 동안 Redis 를 부르지 않다가 다시 시도한다 — 레이트 리밋이 돌아온다.
- fulfillment 의 GEO 적재는 주기적으로 다시 시도한다(best-effort, §8.6). `dawnline_geo_index_loaded{index}` 가 1 로 돌아오는지 본다.
  적재 대상이 없다는 경고(「적재 대상이 없습니다. 시드가 비어 있는지 확인하세요」)는 Redis 가 아니라 시드 문제다.

## 2. Redis 는 살았는데 order-service 만 건너뛴다

order-service 의 Redis 명령 타임아웃은 **50 ms** 다(§7.2 — 핫패스 예산). Redis 가 느리면 죽은 것과 같게 보인다.

```bash
dc exec -T redis redis-cli --latency-history -i 1      # 몇 초 보고 Ctrl-C
dc exec -T redis redis-cli info stats | grep -E 'instantaneous_ops|rejected_connections'
```

느린 원인(큰 키 · 메모리 · 호스트 CPU)을 고친다. 예산을 늘리는 것은 대응이 아니다 — 예산을 넘은 응답을 기다린 뒤의 「허용」은 허용이 아니라
SLO 파괴다(§7.2).

## 3. 확인

- `dawnline_rate_limit_decisions_total{outcome="bypassed"}` 가 더 오르지 않는다 — 알림은 10분 창이 지나면 풀린다.
- `dawnline_geo_index_loaded` 가 둘 다 1.
- `dawnline_geo_lookups_total{outcome="bypassed"}` · `dawnline_at_risk_cooldown_bypassed_total` 이 멈춘다.
- **검증** — Redis 중단 중에도 발행이 멈추지 않고 지연이 오르지 않는다는 것(ADR-027 후속 정정)은 카오스 스크립트(`make chaos-redis`, 7-3)가 잰다.
  **기준은 재기 전에 적었다**(2026-09-26 — 이 줄이 첫 실행보다 먼저 커밋됐다): ① 장애가 **끝나기 전에** 검증 표 V1 · V7 이 ✅(주문 전부가 Redis
  없이 후보까지 · outbox 0/0) ② 장애 중 `max(dawnline_outbox_lag_seconds)` 의 최댓값 **≤ 5초**(5초마다 잰다 — 알림 문턱 30초의 1/6, 릴레이는
  100 ms 마다 돈다) ③ `DawnlineRateLimitBypassed` 가 실제 Prometheus 에서 울고 GEO `bypassed` 가 오른다 — 폴백은 조용하면 안 된다 ④ 복구 뒤
  레이트 리밋이 다시 판정한다(`bypassed` 그대로 · `allowed` 가 오른다) · 검증 표 V1–V7(DLQ 0).

## 참조

- `docs/DESIGN.md` §7.2 · §8.4 · §8.6
- [ADR-016](../adr/ADR-016-readiness-excludes-kafka.md) — 폴백이 있는 의존성을 레디니스에 넣지 않는 이유
