package com.dawnline.sim.driver;

/**
 * 스캔 종류 — tracking 스캔 API 의 {@code type} 값 (DESIGN.md §5.4).
 *
 * <p>tracking 의 {@code ScanType} 을 재사용하지 않는다. 도구가 서비스 모듈에 의존하면
 * 「도구가 서비스를 안다」가 되고, 그 방향은 §5.6 이 「도구는 REST 로만 붙는다」로 막아 둔 것이다.
 * 대신 <strong>이 열거가 계약의 무엇과 같아야 하는지를 테스트가 대조한다</strong> —
 * {@code ScanContractTest} 가 {@code contracts/openapi/tracking-service.yaml} 의
 * {@code ScanRequest.type} 열거와 양방향으로 비교한다. 서로를 비추는 목록은 대조 검사 없이는
 * 갈라지고, 갈라진 쪽은 <em>빈자리</em>라 눈에 띄지 않는다 (CLAUDE.md 코딩 컨벤션).
 */
public enum ScanType {

    /** 캠프 출발. 라우트의 사건이라 {@code stopSeq} 를 무시하고 라우트 전체에 적용된다. */
    DEPARTED_CAMP,

    /** 배송지 도착. */
    ARRIVED,

    /** 전달 완료. */
    COMPLETED,

    /** 전달 실패. */
    FAILED;

    /** 이 스캔이 stop 을 끝내는가 (다음 stop 으로 넘어가도 되는가). */
    public boolean isTerminal() {
        return this == COMPLETED || this == FAILED;
    }
}
