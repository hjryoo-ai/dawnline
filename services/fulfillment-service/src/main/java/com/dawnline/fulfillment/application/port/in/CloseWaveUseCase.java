package com.dawnline.fulfillment.application.port.in;

import java.util.UUID;

/**
 * 운영자 조기 마감 (DESIGN.md §5.2, ADR-054).
 *
 * <p><strong>컷오프 전에도 닫는다.</strong> 그 뒤 컷오프까지 같은 {@code cutoffAt} 으로 접수되는 주문은 다음
 * 웨이브로 가고 약속이 개정된다 — 그것이 이 커맨드의 대가이고, {@code reason} 이 필수인 이유다.
 */
public interface CloseWaveUseCase {

    /** {@code reason} 의 최대 길이 — 취소 사유와 같은 값이다(자유 텍스트에 개인정보가 섞일 여지를 줄인다). */
    int MAX_REASON_LENGTH = 200;

    /**
     * @param waveId 웨이브
     * @param reason 왜 닫는가 — 공백이 아니어야 한다
     * @return 닫힌 웨이브 ({@code closeCause=MANUAL})
     * @throws com.dawnline.common.error.NotFoundException    없는 웨이브
     * @throws com.dawnline.common.error.DomainException      {@code wave-not-open} — 이미 닫혀 있다. 본문의
     *                                                        {@code closeCause} 가 누가 닫았는지 말한다
     */
    WaveView close(UUID waveId, String reason);
}
