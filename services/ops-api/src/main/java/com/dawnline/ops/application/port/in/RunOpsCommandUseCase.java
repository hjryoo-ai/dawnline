package com.dawnline.ops.application.port.in;

import com.dawnline.ops.application.port.out.CoreReply;
import java.util.Objects;
import java.util.UUID;

/**
 * 운영자 커맨드 하나를 감사 기록과 함께 코어로 위임한다 (DESIGN.md §5.5 「커맨드 위임」).
 *
 * <p>감사 행을 쓰지 못하면 위임하지 않는다 — {@code DomainException}({@code unavailable}, 503)이 나간다.
 */
public interface RunOpsCommandUseCase {

    /**
     * @param actor   JWT 의 {@code sub}
     * @param command 커맨드
     * @return 감사 행 id 와 코어의 답
     */
    Outcome run(String actor, OpsCommand command);

    /**
     * @param auditId 감사 행 id — 응답 헤더로 운영자에게 돌아간다
     * @param reply   코어의 답
     */
    record Outcome(UUID auditId, CoreReply reply) {
        public Outcome {
            Objects.requireNonNull(auditId, "auditId");
            Objects.requireNonNull(reply, "reply");
        }
    }
}
