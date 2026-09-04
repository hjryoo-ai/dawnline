// =============================================================================
// tools/k6/lib/orders.js — 두 k6 스크립트가 함께 쓰는 요청 생성기
//
// 여기의 목적은 하나다: **부하 스크립트가 서비스의 유효성 검사에 걸려 죽지 않게** 한다.
// 400 이 섞인 채로 잰 p99 는 "빠른 실패" 의 평균이지 주문 접수의 지연이 아니다.
// =============================================================================

/**
 * 좌표를 찾을 수 있는 우편번호 앞 2자리.
 *
 * `PostalPrefixGeocoder.ANCHORS` 와 같아야 한다. 여기 없는 접두어를 보내면 좌표 조회가 실패해
 * 400 이 돌아오고, 그러면 **부하가 아니라 검증 실패를 측정하게 된다**.
 * (표를 바꾸면 이 배열도 바꾼다 — 그 사실을 `orders.js` 의 `problem_validation_failed` 카운터가
 *  0 이 아닌 값으로 알려 준다.)
 */
export const POSTAL_PREFIXES = [
  '01', '02', '03', '04', '05', '06', '07', '08',
  '10', '11', '12', '13', '14', '15', '16', '17', '18',
  '21', '22',
];

/** 서비스 티어. Phase 1 의 `AllTiersServiceableZones` 는 셋 다 허용한다. */
export const TIERS = ['DAWN', 'SAME_DAY', 'NEXT_DAY'];

/**
 * 인덱스 → 고객 id.
 *
 * 난수를 쓰지 않는다. UUID 마지막 마디에 인덱스를 그대로 박아서, 로그·Grafana 에서
 * 고객 id 만 보고 **몇 번째 가상 고객인지** 알 수 있게 한다. 같은 실행을 두 번 돌리면
 * 같은 고객 집합이 나오는 것도 의도한 것이다(재현).
 *
 * 형식은 유효한 UUID 다 — 13번째 자리 `4`(version), 17번째 자리 `8`(variant).
 * 서버가 `UUID.fromString` 으로 파싱하므로 아무 문자열이나 쓸 수 없다.
 *
 * @param {number} index 0 이상
 */
export function customerIdFor(index) {
  const tail = String(index).padStart(12, '0');
  return `00000000-0000-4000-8000-${tail}`;
}

/**
 * 인덱스 → 우편번호. 접두어를 고르게 돌고, 뒤 3자리는 인덱스에서 만든다.
 *
 * 뒷자리를 섞는 이유: `PostalPrefixGeocoder` 는 세 번째 자리로 좌표를 옮기고 주소 문자열
 * 해시로 지터를 준다. 전부 같은 우편번호로 쏘면 **한 지점에 3만 건**이 쌓여서, 나중에
 * 이 데이터를 Phase 2·3 이 웨이브·경로로 쓸 때 현실과 다른 모양이 된다.
 *
 * @param {number} index 0 이상
 */
export function postalCodeFor(index) {
  const prefix = POSTAL_PREFIXES[index % POSTAL_PREFIXES.length];
  const rest = String(Math.floor(index / POSTAL_PREFIXES.length) % 1000).padStart(3, '0');
  return `${prefix}${rest}`;
}

/**
 * `POST /api/v1/orders` 본문 하나.
 *
 * @param {number} index      주문 일련번호 (고객·우편번호·티어가 여기서 파생된다)
 * @param {number} customerNo 고객 인덱스. 부하 스크립트는 주문마다 다른 고객을 쓰고,
 *                            레이트 리밋 스크립트는 한 명으로 고정한다
 * @param {string} [customerId] 고객 id 를 직접 줄 때 (레이트 리밋 스크립트)
 */
export function orderPayload(index, customerNo, customerId) {
  const cold = index % 4 === 0;          // 냉장 25% — §8.2 피크 모델의 비율
  return {
    customerId: customerId || customerIdFor(customerNo),
    serviceTier: TIERS[index % TIERS.length],
    addressLine: `테스트로 ${index % 500 + 1}길 ${index % 90 + 1}`,
    postalCode: postalCodeFor(index),
    parcel: {
      weightG: 500 + (index % 20) * 250,       // 0.5 ~ 5.25 kg
      volumeCm3: 2000 + (index % 15) * 1000,   // 2 ~ 16 L
      requiresCold: cold,
      hazmat: false,
    },
    items: [
      { sku: `SKU-${String(index % 2000).padStart(5, '0')}`, qty: 1 + (index % 3) },
    ],
  };
}

/**
 * 응답 본문에서 Problem Details 의 `code` 를 꺼낸다. 실패하면 빈 문자열.
 *
 * 오류를 status 로만 세면 422 하나에 "다른 본문의 재요청" 과 "제공되지 않는 티어" 가 섞인다.
 * 원인을 나중에 추측하지 않으려면 여기서 코드를 봐야 한다.
 */
export function problemCode(response) {
  try {
    const body = response.json();
    return (body && body.code) ? String(body.code) : '';
  } catch (_ignored) {
    return '';
  }
}
