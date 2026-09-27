package com.dawnline.tracking.adapter.in.web;

import com.dawnline.web.ProblemDetailsAdviceSupport;
import com.dawnline.tracking.domain.TrackingErrorCode;
import java.util.Map;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * 이 서비스의 <strong>유일한</strong> {@code @ControllerAdvice} 다. 오류 응답의 모양이 한 곳에서만
 * 정해져야 단말이 그것을 계약으로 삼을 수 있다.
 *
 * <p>모양 자체는 {@link ProblemDetailsAdviceSupport} 에 있다 (ADR-049). 여기 남는 것은 이
 * 서비스만의 답 하나 — {@link #retryAfterSeconds()} 다.
 */
@RestControllerAdvice
public class ProblemDetailsAdvice extends ProblemDetailsAdviceSupport {

    /**
     * 잠시 후 <em>같은 요청을 그대로</em> 재시도하면 되는 오류와 그 대기 시간(초).
     *
     * <p>{@code shipment-contended} 하나다(DESIGN.md §5.4) — 스캔이 다른 쓰기와 세 번 겹쳤다. 적용되지 않았고 스캔은 상태 머신이 멱등을
     * 만들므로 그대로 다시 보내면 된다. 1초인 이유: 겹침의 상대는 개정 반영 하나(수십 ms)이거나 같은 라우트의 다른 스캔이다 — 그보다 길게
     * 기다리게 할 근거가 없다.
     *
     * <p>404(아직 {@code route.assigned} 를 못 받은 창)는 여기 없다. 재시도가 통하지만 <em>언제</em>인지를 우리가 모른다(브로커 랙에
     * 달려 있다) — 모르는 값을 적어 두면 그 숫자가 계약이 된다. <strong>비었다는 것을 빈칸이 아니라 이 메서드로 말하는 이유</strong>는
     * ADR-049 결정 3 이다 — 기본값이 있으면 이 판단이 코드에서 사라진다.
     */
    @Override
    protected Map<String, Integer> retryAfterSeconds() {
        return Map.of(TrackingErrorCode.SHIPMENT_CONTENDED.code(), 1);
    }
}
