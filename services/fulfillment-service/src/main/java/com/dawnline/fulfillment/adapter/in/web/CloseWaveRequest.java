package com.dawnline.fulfillment.adapter.in.web;

import com.dawnline.fulfillment.application.port.in.CloseWaveUseCase;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.jspecify.annotations.Nullable;

/**
 * 조기 마감 요청 (ADR-054 결정 2).
 *
 * <p>{@code reason} 이 <strong>필수</strong>다. 컷오프 전 마감은 남은 시간 동안의 모든 접수가 약속을 받자마자
 * 개정되는 결정이고, 감사 행에 「왜」가 없으면 {@code UNKNOWN} 을 닫는 사람도 다음 날 개정 급증을 조사하는
 * 사람도 근거를 볼 수 없다. 필수 표시가 이 문서에 있어야 ops-api 의 생성 클라이언트가 그것을 코드로 받는다.
 *
 * <p>{@code min = 1} 은 {@code @NotBlank} 와 겹치지만 문서를 위해 둔다 — 없으면 springdoc 이 {@code minLength: 0} 을 적어
 * 문서가 「빈 문자열도 된다」고 말한다.
 *
 * @param reason 왜 닫는가. 자유 텍스트라 길이를 제한한다(취소 사유와 같은 200자)
 */
public record CloseWaveRequest(
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED,
                description = "왜 닫는가 — 감사 행과 마감 로그에 남는다. 컷오프 전 마감이면 남은 시간 동안 "
                        + "접수되는 주문은 약속이 개정된다")
        @NotBlank @Size(min = 1, max = CloseWaveUseCase.MAX_REASON_LENGTH) @Nullable String reason) {
}
