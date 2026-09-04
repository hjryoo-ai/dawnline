// =============================================================================
// tools/k6/orders.js — POST /api/v1/orders 부하 (IMPLEMENTATION_PLAN Phase 1-8)
//
// 재는 것: 500 rps 를 60초 지속했을 때의 지연 분포와 오류율 (DESIGN.md §8.1 SLO).
// 재지 않는 것: 레이트 리밋 동작 — 그건 rate-limit.js 다.
//
// 고객을 1만 명으로 흩뿌리는 이유:
//   500 rps ÷ 10,000 명 = 고객당 0.05 rps. 버킷 용량 60·초당 1 리필(§7.2)에 한참 못 미친다.
//   한 명으로 쏘면 60건 뒤 전부 429 가 되어 **레이트 리밋의 성능**을 재게 된다.
//   고객은 무작위가 아니라 순번으로 돈다 — 30,000건 ÷ 10,000명 = 정확히 1인당 3건이라
//   "우연히 한 고객에게 몰려서 429 가 났다" 는 가능성이 아예 없다.
//
// 목표 미달 자체는 실패가 아니다. **원인을 모르는 것이 실패다.**
// 그래서 이 스크립트는 통과/실패가 아니라 아래를 나눠서 남긴다:
//   - 응답을 status 별로 (201 / 200 재생 / 4xx / 429 / 5xx)
//   - 4xx 를 Problem Details `code` 별로 (검증 실패인지, 티어 불가인지, 레이트 리밋인지)
//   - dropped_iterations — 이 값이 0 이 아니면 **부하가 500 rps 에 못 미쳤다**.
//     그때의 p99 는 "500 rps 에서의 p99" 가 아니므로 SLO 달성이라고 말할 수 없다.
//
// 실행:
//   k6 run tools/k6/orders.js
//   k6 run -e BASE_URL=http://localhost:8081 -e RATE=500 -e DURATION=60s tools/k6/orders.js
// =============================================================================

import http from 'k6/http';
import exec from 'k6/execution';
import { check } from 'k6';
import { Counter, Rate, Trend } from 'k6/metrics';
import { orderPayload, problemCode } from './lib/orders.js';

const BASE_URL = __ENV.BASE_URL || 'http://localhost:8081';
const RATE = Number(__ENV.RATE || 500);
const DURATION = __ENV.DURATION || '60s';
const CUSTOMERS = Number(__ENV.CUSTOMERS || 10000);
const WARMUP_RATE = Number(__ENV.WARMUP_RATE || 50);
const WARMUP_DURATION = __ENV.WARMUP_DURATION || '20s';

// 이 실행을 구분하는 접두어. 멱등 키가 실행 간에 겹치면 두 번째 실행이 전부 200 재생이 되어
// **아무것도 새로 접수하지 않은 채** 빠른 응답만 측정하게 된다.
const RUN_ID = `k6-${Date.now()}`;

// --- 응답 분류 ---------------------------------------------------------------
const accepted = new Counter('order_accepted');          // 201
const replayed = new Counter('order_replayed');          // 200 (멱등 재생 — 여기서는 0이어야 한다)
const clientErrors = new Counter('order_client_errors'); // 4xx (429 제외)
const rateLimited = new Counter('order_rate_limited');   // 429 — 여기서는 0이어야 한다
const serverErrors = new Counter('order_server_errors'); // 5xx
const serverErrorRate = new Rate('order_server_error_rate');

// --- 4xx 의 원인 -------------------------------------------------------------
const problemValidationFailed = new Counter('problem_validation_failed');
const problemTierNotServiceable = new Counter('problem_tier_not_serviceable');
const problemIdempotentInFlight = new Counter('problem_idempotent_in_flight');
const problemOther = new Counter('problem_other');

// 접수(201)만의 지연. 전체 http_req_duration 에는 빠르게 떨어지는 오류가 섞인다.
const acceptedDuration = new Trend('order_accepted_duration', true);

export const options = {
  discardResponseBodies: false, // Problem Details 의 code 를 봐야 한다
  summaryTrendStats: ['min', 'avg', 'med', 'p(95)', 'p(99)', 'max'],
  scenarios: {
    // JIT·커넥션 풀·Hibernate 프록시가 데워지기 전의 숫자는 서비스의 지연이 아니다.
    // 이 구간은 측정에서 제외한다(태그 phase:warmup).
    warmup: {
      executor: 'constant-arrival-rate',
      rate: WARMUP_RATE,
      timeUnit: '1s',
      duration: WARMUP_DURATION,
      preAllocatedVUs: 30,
      maxVUs: 100,
      tags: { phase: 'warmup' },
    },
    measure: {
      executor: 'constant-arrival-rate',
      rate: RATE,
      timeUnit: '1s',
      duration: DURATION,
      startTime: WARMUP_DURATION,
      // 500 rps × 0.2초 = 100 VU 면 SLO 안에서는 충분하다. 상한을 1,000 으로 크게 둔 이유는
      // **느려졌을 때 부하를 줄이지 않기 위해서**다. VU 가 모자라면 k6 가 반복을 버리고
      // (dropped_iterations) 실제 부하가 500 rps 밑으로 내려간다 — 그러면 서비스가 느려질수록
      // 부하가 가벼워져서, 측정이 스스로를 구해 주는 꼴이 된다.
      preAllocatedVUs: 200,
      maxVUs: 1000,
      tags: { phase: 'measure' },
    },
  },
  thresholds: {
    // §8.1 SLO — 500 rps 지속 시 p99 ≤ 200 ms.
    'http_req_duration{phase:measure}': ['p(99)<=200'],
    // §8.1 SLO — 가용성 ≥ 99.9% (5xx 비율).
    'order_server_error_rate': ['rate<0.001'],
    // 부하가 실제로 걸렸는가. 이게 깨지면 위의 p99 는 500 rps 의 값이 아니다.
    'dropped_iterations': ['count==0'],
    // 워밍업에서부터 오류가 나면 스크립트나 환경이 잘못된 것이다. 60초를 더 버릴 이유가 없다.
    'checks{phase:warmup}': [
      { threshold: 'rate>0.99', abortOnFail: true, delayAbortEval: '5s' },
    ],
  },
};

export default function placeOrder() {
  const phase = exec.scenario.name;
  const measuring = phase === 'measure';

  // 전역 순번. VU 가 몇 개든 겹치지 않는다.
  const seq = exec.scenario.iterationInTest;
  const customerNo = seq % CUSTOMERS;

  const response = http.post(
    `${BASE_URL}/api/v1/orders`,
    JSON.stringify(orderPayload(seq, customerNo)),
    {
      headers: {
        'Content-Type': 'application/json',
        // 실행·시나리오·순번으로 유일하다. 겹치면 201 이 아니라 200 재생이 된다.
        'Idempotency-Key': `${RUN_ID}-${phase}-${seq}`,
      },
      tags: { endpoint: 'place_order', phase: phase },
    },
  );

  check(response, { '201 Created': (r) => r.status === 201 }, { phase: phase });

  if (!measuring) {
    return;
  }

  serverErrorRate.add(response.status >= 500);

  if (response.status === 201) {
    accepted.add(1);
    acceptedDuration.add(response.timings.duration);
    return;
  }
  if (response.status === 200) {
    replayed.add(1);
    return;
  }
  if (response.status >= 500) {
    serverErrors.add(1);
    return;
  }
  if (response.status === 429) {
    rateLimited.add(1);
  } else {
    clientErrors.add(1);
  }

  switch (problemCode(response)) {
    case 'validation-failed':
      problemValidationFailed.add(1);
      break;
    case 'tier-not-serviceable':
      problemTierNotServiceable.add(1);
      break;
    case 'idempotent-request-in-flight':
      problemIdempotentInFlight.add(1);
      break;
    case 'rate-limited':
      break; // order_rate_limited 가 이미 세었다
    default:
      problemOther.add(1);
  }
}

// -----------------------------------------------------------------------------
// 결과 파일. 문서에 **손으로 옮겨 적지 않기 위해서** 붙여넣을 수 있는 표를 만든다.
// 손으로 옮기면 옮기는 사람이 반올림하고, 반올림한 숫자는 나중에 비교할 수 없다.
// -----------------------------------------------------------------------------
export function handleSummary(data) {
  const metric = (name) => (data.metrics[name] ? data.metrics[name].values : {});
  const count = (name) => (metric(name).count || 0);

  const measured = data.metrics['http_req_duration{phase:measure}']
    ? data.metrics['http_req_duration{phase:measure}'].values
    : metric('http_req_duration');

  const total = count('order_accepted') + count('order_replayed') + count('order_client_errors')
    + count('order_rate_limited') + count('order_server_errors');
  const dropped = count('dropped_iterations');
  const ratio = (n) => (total > 0 ? ((n / total) * 100).toFixed(3) : '—');
  const ms = (v) => (v === undefined ? '—' : v.toFixed(1));

  const markdown = [
    `| 항목 | 값 |`,
    `|---|---|`,
    `| 목표 부하 | ${RATE} rps × ${DURATION} (워밍업 ${WARMUP_RATE} rps × ${WARMUP_DURATION} 제외) |`,
    `| 고객 수 | ${CUSTOMERS} |`,
    `| 총 요청 | ${total} |`,
    `| 버려진 반복 (dropped_iterations) | **${dropped}** |`,
    `| p50 | ${ms(measured['med'])} ms |`,
    `| p95 | ${ms(measured['p(95)'])} ms |`,
    `| p99 | ${ms(measured['p(99)'])} ms |`,
    `| max | ${ms(measured['max'])} ms |`,
    `| 201 접수 | ${count('order_accepted')} (${ratio(count('order_accepted'))}%) |`,
    `| 200 멱등 재생 | ${count('order_replayed')} |`,
    `| 4xx (429 제외) | ${count('order_client_errors')} |`,
    `| 429 | ${count('order_rate_limited')} |`,
    `| 5xx | ${count('order_server_errors')} (${ratio(count('order_server_errors'))}%) |`,
    `| — validation-failed | ${count('problem_validation_failed')} |`,
    `| — tier-not-serviceable | ${count('problem_tier_not_serviceable')} |`,
    `| — idempotent-request-in-flight | ${count('problem_idempotent_in_flight')} |`,
    `| — 그 밖의 code | ${count('problem_other')} |`,
    '',
    dropped > 0
      ? `> **경고**: 반복 ${dropped}건이 버려졌다. 실제 부하가 ${RATE} rps 에 못 미쳤으므로 위 p99 는 ${RATE} rps 의 값이 아니다.`
      : `> 반복이 버려지지 않았다 — 부하는 목표대로 걸렸다.`,
    '',
  ].join('\n');

  const text = [
    '',
    `p50 ${ms(measured['med'])}ms  p95 ${ms(measured['p(95)'])}ms  p99 ${ms(measured['p(99)'])}ms`,
    `201 ${count('order_accepted')}  4xx ${count('order_client_errors')}  429 ${count('order_rate_limited')}  5xx ${count('order_server_errors')}  dropped ${dropped}`,
    '',
    'summary.md / summary.json 을 docs/benchmarks/phase1-orders-k6.md 에 붙여 넣어라.',
    '',
  ].join('\n');

  return {
    stdout: text,
    'summary.md': markdown,
    'summary.json': JSON.stringify(data, null, 2),
  };
}
