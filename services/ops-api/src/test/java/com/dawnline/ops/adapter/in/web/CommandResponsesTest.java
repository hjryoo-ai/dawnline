package com.dawnline.ops.adapter.in.web;

import static org.assertj.core.api.Assertions.assertThat;

import com.dawnline.observability.MdcKeys;
import com.dawnline.ops.application.port.in.RunOpsCommandUseCase.Outcome;
import com.dawnline.ops.application.port.out.CoreReply;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.DisplayNameGeneration;
import org.junit.jupiter.api.DisplayNameGenerator;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;

/** 답 → 응답의 표 전체 (DESIGN.md §5.5 「커맨드 위임」 — 응답). 어느 갈래든 감사 id 헤더가 온다. */
@DisplayNameGeneration(DisplayNameGenerator.ReplaceUnderscores.class)
@DisplayName("CommandResponses — 코어의 답을 운영자의 응답으로")
class CommandResponsesTest {

    private static final UUID AUDIT = UUID.fromString("0199a000-0000-7000-8000-00000000a0d1");
    private static final UUID WAVE = UUID.fromString("0199a000-0000-7000-8000-0000000000a1");
    private static final String PATH = "/api/v1/plans/" + WAVE + "/run";

    @Test
    void 적용은_200_과_옮긴_본문이다() {
        CoreReply.PlanRun body = new CoreReply.PlanRun(WAVE, "PLANNED");

        ResponseEntity<?> response = respond(new CoreReply.Applied(body));

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        assertThat(response.getBody()).isEqualTo(body);
        assertAuditHeader(response);
    }

    @Test
    void 거절은_코어의_상태와_본문과_미디어_타입이_그대로다() {
        String problem = "{\"code\":\"hard-rule-violated\",\"status\":409}";

        ResponseEntity<?> response = respond(new CoreReply.Rejected(409, problem, "application/problem+json"));
        ResponseEntity<?> untyped = respond(new CoreReply.Rejected(404, problem, null));

        assertThat(response.getStatusCode().value()).isEqualTo(409);
        assertThat(response.getBody()).isEqualTo(problem);
        assertThat(response.getHeaders().getContentType()).isEqualTo(MediaType.APPLICATION_PROBLEM_JSON);
        assertThat(untyped.getHeaders().getContentType()).as("코어가 타입을 주지 않으면 Problem Details 로 본다")
                .isEqualTo(MediaType.APPLICATION_PROBLEM_JSON);
        assertAuditHeader(response);
    }

    @Test
    void 닿지_않았으면_502_core_unreachable() {
        assertProblem(respond(new CoreReply.Unreachable("refused")), 502, CommandResponses.CORE_UNREACHABLE);
    }

    @Test
    void 시간이_다_됐으면_504_core_timeout() {
        assertProblem(respond(new CoreReply.Unknown(true, null, "timeout")), 504, CommandResponses.CORE_TIMEOUT);
    }

    @Test
    void 코어의_5xx_나_끊김은_502_core_error() {
        assertProblem(respond(new CoreReply.Unknown(false, 500, "boom")), 502, CommandResponses.CORE_ERROR);
        assertProblem(respond(new CoreReply.Unknown(false, null, "reset")), 502, CommandResponses.CORE_ERROR);
    }

    @Test
    void 조회의_답은_같은_갈래이고_감사_id_가_없다() {
        CoreReply.QuarantinedOutbox body = new CoreReply.QuarantinedOutbox(0, java.util.List.of());
        String problem = "{\"code\":\"validation-failed\",\"status\":400}";

        ResponseEntity<?> listed = CommandResponses.ofQuery(new CoreReply.Applied(body), PATH);
        ResponseEntity<?> rejected = CommandResponses.ofQuery(new CoreReply.Rejected(400, problem, null), PATH);
        ResponseEntity<?> unreachable = CommandResponses.ofQuery(new CoreReply.Unreachable("refused"), PATH);
        ResponseEntity<?> timedOut = CommandResponses.ofQuery(new CoreReply.Unknown(true, null, "timeout"), PATH);

        assertThat(listed.getStatusCode().value()).isEqualTo(200);
        assertThat(listed.getBody()).isEqualTo(body);
        assertThat(rejected.getStatusCode().value()).isEqualTo(400);
        assertThat(rejected.getBody()).isEqualTo(problem);
        assertThat(unreachable.getStatusCode().value()).isEqualTo(502);
        assertThat(timedOut.getStatusCode().value()).isEqualTo(504);
        for (ResponseEntity<?> response : java.util.List.of(listed, rejected, unreachable, timedOut)) {
            assertThat(response.getHeaders().getFirst(MdcKeys.AUDIT_ID_HEADER)).as("조회는 감사 행이 없다").isNull();
        }
        assertThat(timedOut.getBody()).isInstanceOfSatisfying(ProblemDetail.class, detail -> assertThat(
                detail.getProperties()).containsEntry("code", CommandResponses.CORE_TIMEOUT)
                .doesNotContainKey(MdcKeys.AUDIT_ID));
    }

    private static ResponseEntity<?> respond(CoreReply reply) {
        return CommandResponses.of(new Outcome(AUDIT, reply), PATH);
    }

    private static void assertProblem(ResponseEntity<?> response, int status, String code) {
        assertThat(response.getStatusCode().value()).isEqualTo(status);
        assertThat(response.getBody()).isInstanceOfSatisfying(ProblemDetail.class, problem -> {
            assertThat(problem.getType().toString()).endsWith("/problems/" + code);
            assertThat(problem.getProperties()).containsEntry("code", code)
                    .as("본문도 감사 id 를 말한다 — 헤더를 잃는 프록시가 있어도").containsEntry(MdcKeys.AUDIT_ID, AUDIT.toString());
            assertThat(problem.getInstance()).hasToString(PATH);
        });
        assertAuditHeader(response);
    }

    private static void assertAuditHeader(ResponseEntity<?> response) {
        assertThat(response.getHeaders().getFirst(MdcKeys.AUDIT_ID_HEADER)).isEqualTo(AUDIT.toString());
    }
}
