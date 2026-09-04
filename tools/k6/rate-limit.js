// =============================================================================
// tools/k6/rate-limit.js — 고객별 레이트 리밋 동작 검증 (Phase 1-6-1, §7.2)
//
// **이것은 부하 리포트가 아니다.** 통합 테스트의 연장이고, 재는 것은 지연이 아니라 계약이다.
// 그래서 결과는 p99 가 아니라 통과/실패이고, 임계값이 깨지면 k6 가 0 이 아닌 코드로 끝난다.
//
// 검증하는 계약 (§7.2, ADR-020 계열의 결정):
//   1. 한 고객이 계속 쏘면 429 가 나온다.
//   2. 429 에는 `Retry-After` 가 있고, 200/201 에는 없다.
//   3. 429 본문은 Problem Details 이고 `code` 는 `rate-limited` 다.
//   4. 버킷은 용량 60·초당 1 리필이다 — 40초 동안 5 rps(200건)를 쏘면
//      허용은 60(초기) + 40(리필) 언저리이지 200 이 아니다.
//   5. **멱등 재요청도 한도를 소모한다.** 이미 접수된 키를 다시 보내도, 버킷이 비어 있으면
//      200 재생이 아니라 429 다. 이 조항이 없으면 한도를 우회하는 방법이 생긴다 —
//      키 하나를 계속 재전송하면 되기 때문이다.
//
// 실행:
//   k6 run tools/k6/rate-limit.js
//   k6 run -e BASE_URL=http://localhost:8081 tools/k6/rate-limit.js
// =============================================================================

import http from 'k6/http';
import exec from 'k6/execution';
import { check, sleep } from 'k6';
import { Counter } from 'k6/metrics';
import { orderPayload, problemCode } from './lib/orders.js';

const BASE_URL = __ENV.BASE_URL || 'http://localhost:8081';
const RATE = Number(__ENV.RATE || 5);
const DURATION_SECONDS = Number(__ENV.DURATION_SECONDS || 40);

// §7.2 의 버킷 설정. 서비스 설정(dawnline.order.rate-limit.*)을 바꾸면 여기도 바꾼다 —
// 값이 어긋나면 아래 임계값이 깨지면서 그 사실을 알려 준다.
const CAPACITY = Number(__ENV.CAPACITY || 60);
const REFILL_PER_SECOND = Number(__ENV.REFILL_PER_SECOND || 1);

// 실행마다 새 고객을 쓴다. 앞선 실행이 남긴 버킷(TTL 60초)을 물려받으면
// "이미 비어 있는 버킷" 에서 시작해 4번 조항이 엉뚱하게 깨진다.
const RUN_ID = `k6rl-${Date.now()}`;
const CUSTOMER_ID = __ENV.CUSTOMER_ID || customerIdFromRun();

const allowed = new Counter('rl_allowed');
const limited = new Counter('rl_limited');
const unexpected = new Counter('rl_unexpected_status');

/** 이 실행 전용 고객 id. 타임스탬프 하위 12자리를 UUID 마지막 마디에 넣는다. */
function customerIdFromRun() {
  const tail = String(Date.now() % 1e12).padStart(12, '0');
  return `00000000-0000-4000-8000-${tail}`;
}

// 40초 × 5 rps = 200건. 허용은 초기 60 + 리필 40 ≈ 100 이어야 한다.
const EXPECTED_ALLOWED_MAX = CAPACITY + DURATION_SECONDS * REFILL_PER_SECOND + RATE;
// 차단 기대치에는 10% 여유를 둔다. k6 가 목표 rps 를 정확히 채우지 못하는 만큼을 빼 두지 않으면,
// 레이트 리밋이 아니라 **부하 생성기의 오차**로 실패하는 테스트가 된다.
const EXPECTED_LIMITED_MIN = Math.floor(RATE * DURATION_SECONDS * 0.9) - EXPECTED_ALLOWED_MAX;

export const options = {
  scenarios: {
    // 한 고객이 5 rps 로 계속 쏜다.
    steady: {
      executor: 'constant-arrival-rate',
      rate: RATE,
      timeUnit: '1s',
      duration: `${DURATION_SECONDS}s`,
      preAllocatedVUs: 2,
      maxVUs: 5,
      exec: 'steadyStream',
    },
    // 5번 조항 — 다른 고객으로, 순서가 중요한 시나리오를 단독으로 돌린다.
    replay: {
      executor: 'per-vu-iterations',
      vus: 1,
      iterations: 1,
      startTime: `${DURATION_SECONDS + 2}s`,
      maxDuration: '60s',
      exec: 'replayCountsTowardLimit',
    },
  },
  thresholds: {
    // 계약 검증이므로 체크는 하나도 실패하면 안 된다.
    checks: ['rate==1.00'],
    // 429 가 아예 안 나오면 레이트 리밋이 꺼져 있거나 Redis 가 죽어 fail-open 중이다.
    // 둘 다 "통과" 로 넘어가면 안 되는 상태다 (§10 — 무인증 API 의 유일한 남용 방지).
    rl_limited: [`count>=${EXPECTED_LIMITED_MIN}`],
    // 버킷 용량만큼은 통과해야 한다. 이보다 적으면 리밋이 너무 빡빡한 것이다.
    rl_allowed: [`count>=${CAPACITY}`, `count<=${EXPECTED_ALLOWED_MAX}`],
    rl_unexpected_status: ['count==0'],
  },
};

/** 5 rps 로 계속 쏘는 본 시나리오. */
export function steadyStream() {
  const seq = exec.scenario.iterationInTest;
  const response = post(`${RUN_ID}-steady-${seq}`, seq);
  classify(response);
}

/**
 * 멱등 재요청도 한도를 소모하는가.
 *
 * 순서: (1) 새 고객으로 주문 1건 접수 → 201, (2) 버킷을 비운다, (3) **(1)과 같은 멱등 키**로
 * 다시 보낸다 → 200 재생이 아니라 429 여야 한다.
 */
export function replayCountsTowardLimit() {
  const customerId = customerIdFromRun();
  const key = `${RUN_ID}-replay`;

  const first = http.post(`${BASE_URL}/api/v1/orders`,
    JSON.stringify(orderPayload(1, 0, customerId)),
    postOptions(key));
  check(first, { '재생 검증: 첫 요청이 201 이다': (r) => r.status === 201 });

  // 남은 토큰을 다 쓴다. 용량보다 넉넉히 보내 반드시 바닥나게 한다.
  let drained = false;
  for (let i = 0; i < CAPACITY + 10; i++) {
    const response = http.post(`${BASE_URL}/api/v1/orders`,
      JSON.stringify(orderPayload(i + 2, 0, customerId)),
      postOptions(`${key}-drain-${i}`));
    if (response.status === 429) {
      drained = true;
      break;
    }
  }
  check(drained, { '재생 검증: 버킷을 비웠다': (v) => v === true });

  // 같은 멱등 키의 재요청. 버킷이 비었으므로 429 여야 한다.
  const replay = http.post(`${BASE_URL}/api/v1/orders`,
    JSON.stringify(orderPayload(1, 0, customerId)),
    postOptions(key));
  check(replay, {
    '재생 검증: 멱등 재요청도 429 다 (한도를 소모한다)': (r) => r.status === 429,
    '재생 검증: 200 재생으로 한도를 우회할 수 없다': (r) => r.status !== 200,
  });

  // 버킷이 다시 차면 같은 키가 200 재생으로 돌아온다 — 리밋은 지연이지 거절이 아니다.
  sleep(Math.ceil(2 / REFILL_PER_SECOND) + 1);
  const afterRefill = http.post(`${BASE_URL}/api/v1/orders`,
    JSON.stringify(orderPayload(1, 0, customerId)),
    postOptions(key));
  check(afterRefill, {
    '재생 검증: 리필 뒤에는 같은 키가 200 재생이다': (r) => r.status === 200,
  });
}

function postOptions(idempotencyKey) {
  return {
    headers: { 'Content-Type': 'application/json', 'Idempotency-Key': idempotencyKey },
    tags: { endpoint: 'place_order' },
  };
}

function post(idempotencyKey, seq) {
  return http.post(`${BASE_URL}/api/v1/orders`,
    JSON.stringify(orderPayload(seq, 0, CUSTOMER_ID)),
    postOptions(idempotencyKey));
}

/** 응답 하나를 계약에 비춰 본다. */
function classify(response) {
  if (response.status === 201) {
    allowed.add(1);
    check(response, {
      '201 에는 Retry-After 가 없다': (r) => r.headers['Retry-After'] === undefined,
    });
    return;
  }
  if (response.status === 429) {
    limited.add(1);
    const retryAfter = Number(response.headers['Retry-After']);
    check(response, {
      '429 에 Retry-After 가 있다': (r) => r.headers['Retry-After'] !== undefined,
      '429 의 code 는 rate-limited 다': (r) => problemCode(r) === 'rate-limited',
      '429 의 type 이 채워져 있다': (r) => {
        const body = safeJson(r);
        return !!body && typeof body.type === 'string' && body.type.endsWith('/rate-limited');
      },
      '429 의 status 필드가 본문에도 있다': (r) => {
        const body = safeJson(r);
        return !!body && body.status === 429;
      },
    });
    check(retryAfter, {
      'Retry-After 는 1 이상의 정수다': (v) => Number.isInteger(v) && v >= 1,
      'Retry-After 가 버킷을 다 채우는 시간을 넘지 않는다': (v) => v <= CAPACITY / REFILL_PER_SECOND,
    });
    return;
  }
  unexpected.add(1);
  check(response, {
    '201 이나 429 만 나온다': () => false,
  });
}

function safeJson(response) {
  try {
    return response.json();
  } catch (_ignored) {
    return null;
  }
}

export function handleSummary(data) {
  const count = (name) => (data.metrics[name] ? data.metrics[name].values.count || 0 : 0);
  const checks = data.metrics.checks ? data.metrics.checks.values : {};
  const sent = count('rl_allowed') + count('rl_limited') + count('rl_unexpected_status');

  const markdown = [
    `| 항목 | 값 |`,
    `|---|---|`,
    `| 부하 | 고객 1명 × ${RATE} rps × ${DURATION_SECONDS}초 |`,
    `| 버킷 설정 | 용량 ${CAPACITY} · 초당 ${REFILL_PER_SECOND} 리필 |`,
    `| 보낸 요청 | ${sent} |`,
    `| 통과(201) | ${count('rl_allowed')} (기대: ${CAPACITY} ~ ${EXPECTED_ALLOWED_MAX}) |`,
    `| 차단(429) | ${count('rl_limited')} (기대: ${EXPECTED_LIMITED_MIN} 이상) |`,
    `| 예상 밖 status | ${count('rl_unexpected_status')} |`,
    `| 체크 통과율 | ${((checks.rate || 0) * 100).toFixed(2)}% (${checks.passes || 0}/${(checks.passes || 0) + (checks.fails || 0)}) |`,
    '',
  ].join('\n');

  return {
    stdout: `\n통과 ${count('rl_allowed')} / 차단 ${count('rl_limited')} / 예상 밖 ${count('rl_unexpected_status')}, 체크 ${((checks.rate || 0) * 100).toFixed(2)}%\n\n`,
    'rate-limit-summary.md': markdown,
    'rate-limit-summary.json': JSON.stringify(data, null, 2),
  };
}
