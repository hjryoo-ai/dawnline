package com.dawnline.order.archunitsample;

import com.dawnline.fulfillment.archunitsample.ForeignAggregate;

/**
 * 규칙 3 위반 표본 — order 서비스가 fulfillment 의 타입을 직접 참조한다.
 *
 * <p>이것이 왜 위반인가: 모노레포에서는 다른 서비스의 클래스가 <strong>컴파일된다</strong>.
 * 그래서 "잠깐만 이 타입 하나 쓰자" 가 컴파일도 되고 테스트도 통과한다. 그러나 그 순간 두 서비스는
 * 함께 배포해야 하는 하나가 되고(불변규칙 3), 다음 단계는 대개 상대 DB 를 직접 읽는 것이다.
 * 필요한 데이터는 이벤트 페이로드 스냅샷이나 자기 DB 프로젝션으로 가진다(불변규칙 4).
 */
public final class CrossServiceUseCase {

    private final ForeignAggregate foreign = new ForeignAggregate();

    /**
     * @return 남의 서비스에서 가져온 값 — 이것이 위반이다
     */
    public String waveId() {
        return foreign.waveId();
    }
}
