package com.dawnline.order.application.port.out;

import com.dawnline.common.GeoPoint;
import java.util.Optional;

/**
 * 주소 → 좌표 변환 포트 (DESIGN.md §5.1).
 *
 * <p>기본 구현은 우편번호 앞 3자리 조회 테이블 + 지터다. 실서비스에서는 외부 지오코딩 API 로
 * 교체되는 지점이며, 그 교체가 도메인·유스케이스를 건드리지 않도록 포트로 끊어 둔다.
 *
 * <p><strong>왜 {@code Optional} 인가</strong>: 알 수 없는 우편번호는 예외가 아니라 정상적인 결과다.
 * 주문 접수는 그때 400 을 주면 되고, 그 판단은 유스케이스가 한다. 어댑터가 예외를 던지면
 * "외부 API 장애" 와 "그런 주소 없음" 이 같은 경로로 올라와 구분이 사라진다.
 */
public interface Geocoder {

    /**
     * 우편번호와 주소로 좌표를 찾는다.
     *
     * @param postalCode  우편번호
     * @param addressLine 도로명 주소 전체 (같은 우편번호 안에서 위치를 흩는 데 쓴다)
     * @return 좌표. 알 수 없는 우편번호면 빈 값
     */
    Optional<GeoPoint> locate(String postalCode, String addressLine);
}
