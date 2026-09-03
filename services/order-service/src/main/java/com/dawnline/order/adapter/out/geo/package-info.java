/**
 * 지리 정보 아웃바운드 어댑터 — 우편번호 → 좌표({@code Geocoder}), 권역 → 지원 티어
 * ({@code TierEligibility.ServiceableZones}).
 *
 * <p>둘 다 실서비스에서는 외부 지오코딩 API 와 {@code zones} 테이블(§5.2, fulfillment-service 소유)로
 * 바뀌는 자리다. 포트로 끊어 두었으므로 그 교체가 도메인·유스케이스를 건드리지 않는다.
 */
package com.dawnline.order.adapter.out.geo;
