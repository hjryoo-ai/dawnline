package com.dawnline.ops.application;

import com.dawnline.ops.application.port.in.ListQuarantinedOutboxUseCase;
import com.dawnline.ops.application.port.out.CoreQueries;
import com.dawnline.ops.application.port.out.CoreReply;
import com.dawnline.ops.domain.CoreService;
import java.util.Objects;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 격리 목록 조회 — 코어의 답을 옮긴다. 감사도 카운터도 없다: 조회는 커맨드가 아니다(§5.5).
 */
public class QuarantineQueryService implements ListQuarantinedOutboxUseCase {

    private static final Logger log = LoggerFactory.getLogger(QuarantineQueryService.class);

    private final CoreQueries core;

    /**
     * @param core 코어 조회
     */
    public QuarantineQueryService(CoreQueries core) {
        this.core = Objects.requireNonNull(core, "core");
    }

    /** 포트는 예외를 던지지 않는 것이 계약이다. 새어 나오면 {@code OpsCommandService} 와 같이 「모름」으로 접는다. */
    @Override
    public CoreReply list(CoreService service, @Nullable Integer limit) {
        try {
            return core.listQuarantined(service, limit);
        } catch (RuntimeException e) {
            log.error("격리 목록 어댑터가 예외를 냈다 service={}", service.path(), e);
            return new CoreReply.Unknown(false, null, e.toString());
        }
    }
}
