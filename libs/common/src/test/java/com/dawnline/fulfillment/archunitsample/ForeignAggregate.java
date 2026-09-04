package com.dawnline.fulfillment.archunitsample;

/**
 * 규칙 3 검증용 표본 — <em>다른</em> 서비스의 타입.
 *
 * <p>이 클래스는 {@code libs/common} 의 테스트 소스에만 있다. 서비스들의 테스트 클래스패스에는
 * {@code libs/common} 의 테스트 산출물이 없으므로 실제 서비스 분석에 섞이지 않는다.
 */
public final class ForeignAggregate {

    /**
     * @return 웨이브 id 흉내
     */
    public String waveId() {
        return "wave-1";
    }
}
