package com.dawnline.messaging.web;

import com.dawnline.messaging.outbox.OutboxQuarantine;
import com.dawnline.messaging.outbox.QuarantinedOutboxEvents;
import com.dawnline.messaging.outbox.RequeuedOutboxEvent;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import java.util.Objects;
import java.util.UUID;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * outbox 격리 조회·재큐 (DESIGN.md §4.6 「격리 조회·재큐 엔드포인트」, ADR-015 후속 정정, RB-05).
 *
 * <h2>네 코어에 같은 코드로 붙는다</h2>
 * 서비스가 선언하지 않는다 — {@code OutboxAdminAutoConfiguration} 이 {@code OutboxRepository} 빈이 있는 서블릿 웹
 * 앱에 등록한다. 서비스가 켜는 방식이면 새 서비스가 한 줄을 잊었을 때 조용히 빠진다.
 *
 * <h2>운영자용이다</h2>
 * 고객 API 가 아니다. ops-api 가 감사 행을 남기고 위임한다(§5.5). 오류는 서비스의 단일 어드바이스
 * ({@code ProblemDetailsAdviceSupport} 하위)가 RFC 9457 로 바꾼다 — 여기서는 예외를 던질 뿐이다.
 *
 * <h2>버전</h2>
 * 매핑에 {@code v1} 을 박지 않는다(ADR-009 결정 2). 그래서 이 컨트롤러는 <strong>API 버저닝을 설정한 앱</strong>
 * 에서만 뜬다 — 설정이 없으면 Spring 이 기동에서 거절한다. 조용히 빠지는 것보다 그쪽이 낫고, 이 저장소의 서비스는
 * 다섯 다 {@code WebConfig} 로 설정한다.
 */
@RestController
@RequestMapping(path = "/api/{version}/admin/outbox", version = "1")
public class OutboxAdminController {

    private final OutboxQuarantine quarantine;

    /**
     * @param quarantine 조회·재큐
     */
    public OutboxAdminController(OutboxQuarantine quarantine) {
        this.quarantine = Objects.requireNonNull(quarantine, "quarantine");
    }

    /**
     * 격리된 행을 격리 시각 순으로. {@code payload}·{@code headers} 는 싣지 않는다(§9.3).
     *
     * @param limit 최대 행 수 (1–500)
     */
    @GetMapping("/quarantined")
    @ApiResponses({
            @ApiResponse(responseCode = "200",
                    description = "격리 시각 순. `total` 이 `events` 보다 크면 `limit` 에 잘렸다. `payload`·`headers` 는 "
                            + "싣지 않는다 — 원인을 고치려고 행 전체를 봐야 하면 DB 에서 본다(RB-05 1.2)"),
            @ApiResponse(responseCode = "400", description = "`limit` 이 1–500 밖이다",
                    content = @Content(schema = @Schema(implementation = ProblemDetail.class)))})
    public QuarantinedOutboxEvents listQuarantined(
            @RequestParam(defaultValue = "" + OutboxQuarantine.DEFAULT_LIMIT) int limit) {
        return quarantine.list(limit);
    }

    /**
     * 격리를 푼다 — <strong>원인을 고치지 않는다.</strong>
     *
     * @param id 행 id ({@code eventId})
     */
    @PostMapping("/{id}/requeue")
    @ApiResponses({
            @ApiResponse(responseCode = "200",
                    description = "격리를 풀었다 — 릴레이가 다음 폴링에 집는다. **재큐는 원인을 고치지 않는다**: 원인이 "
                            + "남아 있으면 다시 격리되고 `publishAttempts` 가 1 부터 다시 오른다(RB-05). 원인 수정이 먼저다"),
            // 오류 본문의 스키마를 명시한다. 적지 않으면 springdoc 이 메서드 반환 타입을 모든 응답에 붙인다 (§11).
            @ApiResponse(responseCode = "400", description = "`id` 가 UUID 형식이 아니다, 또는 지원하지 않는 API 버전",
                    content = @Content(schema = @Schema(implementation = ProblemDetail.class))),
            @ApiResponse(responseCode = "404", description = "그 id 의 outbox 행이 이 서비스에 없다",
                    content = @Content(schema = @Schema(implementation = ProblemDetail.class))),
            @ApiResponse(responseCode = "409",
                    description = "`not-quarantined` — 격리된 행이 아니다. 확장 멤버 `currentState`(`PENDING`·`PUBLISHED`)와 "
                            + "`publishedAt` 이 지금 위치를 말한다. 응답을 못 받은 재큐를 다시 눌러 이것을 받았으면 앞의 "
                            + "요청이 적용된 것이다",
                    content = @Content(schema = @Schema(implementation = ProblemDetail.class)))})
    public RequeuedOutboxEvent requeue(@PathVariable UUID id) {
        return quarantine.requeue(id);
    }
}
