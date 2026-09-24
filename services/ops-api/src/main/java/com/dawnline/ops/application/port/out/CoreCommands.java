package com.dawnline.ops.application.port.out;

import com.dawnline.ops.application.port.in.OpsCommand;
import java.util.UUID;

/**
 * 코어 서비스로의 커맨드 위임 (DESIGN.md §5.5, 불변규칙 4 — 동기 REST 는 ops-api → 코어 방향만).
 *
 * <p>구현은 커밋된 {@code contracts/openapi/*.yaml} 에서 생성한 클라이언트를 쓴다(ADR-052). 이 포트는
 * 예외를 던지지 않는다 — 코어가 어떻게 답했든(또는 답하지 않았든) 그것을 {@link CoreReply} 의 한 갈래로
 * 돌려준다. 감사 행은 그 갈래에서 결과를 얻는다.
 */
public interface CoreCommands {

    /**
     * @param auditId 감사 행 id — 코어 호출에 상관 헤더로 싣는다(§9.3 {@code auditId})
     * @param command 위임할 커맨드
     * @return 코어의 답
     */
    CoreReply delegate(UUID auditId, OpsCommand command);
}
