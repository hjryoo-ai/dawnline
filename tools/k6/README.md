# tools/k6 — order-service 부하·계약 스크립트

두 스크립트는 **재는 것이 다르다.** 같은 문서에 넣되 절을 나누는 이유가 이것이다
(`docs/benchmarks/phase1-orders-k6.md`).

| 스크립트 | 재는 것 | 결과의 성격 |
|---|---|---|
| `orders.js` | 500 rps 60초에서의 지연 분포·오류율 (§8.1 SLO) | **수치**. 목표 미달이어도 그대로 적는다 |
| `rate-limit.js` | 고객별 레이트 리밋의 계약 (§7.2) | **통과/실패**. 통합 테스트의 연장이다 |

## 실행

```bash
# 스택이 떠 있어야 한다
make up            # 또는 make up-lean (관측성 스택 제외)

# 부하
k6 run tools/k6/orders.js
# → summary.md, summary.json 을 현재 디렉터리에 남긴다

# 레이트 리밋 동작 검증
k6 run tools/k6/rate-limit.js
# → rate-limit-summary.md, rate-limit-summary.json

# Makefile 로도 된다 (k6 가 없으면 docker 이미지로 실행한다)
make k6-orders
make k6-rate-limit
```

환경 변수로 조정한다: `BASE_URL`(기본 `http://localhost:8081`), `RATE`, `DURATION`,
`CUSTOMERS`, `WARMUP_RATE`, `WARMUP_DURATION`.

## 읽는 법 — 목표 미달보다 먼저 볼 것

**미달 자체는 실패가 아니다. 원인을 모르는 것이 실패다.** 그래서 두 값을 p99 보다 먼저 본다.

1. **`dropped_iterations`** — 0 이 아니면 부하가 500 rps 에 <em>못 미쳤다</em>. k6 가 VU 를
   확보하지 못해 반복을 버린 것이고, 그러면 서비스가 느려질수록 부하가 가벼워진다.
   이때의 p99 는 "500 rps 에서의 p99" 가 아니므로 SLO 달성/미달 어느 쪽도 말할 수 없다.
2. **`order_client_errors` 와 그 아래 `problem_*` 분해** — 4xx 가 섞여 있으면 그 요청들은
   지오코딩·DB 를 거치지 않고 빠르게 끝난 것이라 p99 를 <em>낮춘다</em>. 특히
   `problem_validation_failed` 가 0 이 아니면 `lib/orders.js` 의 우편번호 표가
   `PostalPrefixGeocoder` 와 어긋난 것이다 — 서비스 문제가 아니라 스크립트 문제다.

그 다음에 지연을 본다. 워밍업 20초는 측정에서 빠져 있다(`phase:warmup` 태그) — JIT 이
덜 된 구간의 숫자는 서비스의 지연이 아니기 때문이다.

## 왜 고객을 1만 명으로 흩뿌리는가

`POST /orders` 앞에 고객별 레이트 리밋이 있다(용량 60, 초당 1 리필, §7.2). 한 고객으로 500 rps 를
쏘면 60건 뒤로는 전부 429 이고, 그러면 **레이트 리밋의 성능**을 재게 된다.

500 rps ÷ 10,000명 = 고객당 0.05 rps. 게다가 고객을 무작위가 아니라 **순번으로** 돌기 때문에
30,000건이 정확히 1인당 3건으로 갈린다 — "우연히 한 고객에게 몰렸다" 가 원리적으로 불가능하다.

## `rate-limit.js` 가 검증하는 계약

1. 한 고객이 계속 쏘면 429 가 나온다.
2. 429 에는 `Retry-After` 가 있고 201 에는 없다.
3. 429 본문은 Problem Details 이고 `code` 는 `rate-limited`, `type` 이 채워져 있다.
4. 버킷은 용량 60·초당 1 리필이다 — 40초 × 5 rps(200건) 중 통과는 100건 언저리다.
5. **멱등 재요청도 한도를 소모한다.** 이미 접수된 키를 다시 보내도 버킷이 비어 있으면 429 다.
   이 조항이 없으면 키 하나를 계속 재전송해 한도를 우회할 수 있다. 리필 뒤에는 같은 키가
   200 재생으로 돌아오는 것까지 확인한다 — 리밋은 <em>지연</em>이지 거절이 아니다.

버킷 설정을 바꾸면 `CAPACITY`·`REFILL_PER_SECOND` 도 함께 바꾼다. 어긋나면 임계값이 깨져
그 사실을 알려 준다.
