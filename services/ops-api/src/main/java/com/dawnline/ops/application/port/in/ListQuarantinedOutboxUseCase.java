package com.dawnline.ops.application.port.in;

import com.dawnline.ops.application.port.out.CoreReply;
import com.dawnline.ops.domain.CoreService;
import org.jspecify.annotations.Nullable;

/**
 * 한 코어의 outbox 격리 목록 (DESIGN.md §4.6 · §5.5). 조회라 감사 행을 남기지 않는다 — {@code OPS_VIEWER} 에게 열린다.
 */
public interface ListQuarantinedOutboxUseCase {

    /**
     * @param service 코어
     * @param limit   최대 행 수, 없으면 코어의 기본
     * @return 코어의 답
     */
    CoreReply list(CoreService service, @Nullable Integer limit);
}
