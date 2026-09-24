package com.dawnline.ops.application.port.out;

import com.dawnline.ops.domain.CoreService;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * 코어 조회 — 감사 행 없이 코어의 답을 옮긴다 (DESIGN.md §5.5 「커맨드 위임」 — 조회는 감사하지 않는다).
 *
 * <p>답의 갈래는 커맨드와 같은 {@link CoreReply} 다. 조회에는 「적용됐는가」가 없으므로 갈래가 감사 결과로 가지
 * 않고 응답의 모양만 정한다. 예외를 던지지 않는 것이 계약이다.
 */
public interface CoreQueries {

    /**
     * @param service 어느 코어의 목록인가
     * @param limit   최대 행 수. 없으면 코어의 기본(50). 범위(1–500)는 코어가 본다 — 두 곳의 규칙이 갈라지지 않게
     * @return {@link CoreReply.Applied}({@link CoreReply.QuarantinedOutbox}) 또는 거절·연결 실패·모름
     */
    CoreReply listQuarantined(CoreService service, @Nullable Integer limit);

    /**
     * dispatch 의 라우트 하나 — 지도의 stop 순서·좌표·상태.
     *
     * @param routeId 라우트
     * @return {@link CoreReply.Applied}({@link CoreReply.RouteDetail}) 또는 거절(없는 라우트는 코어의 404)·연결 실패·모름
     */
    CoreReply route(UUID routeId);
}
