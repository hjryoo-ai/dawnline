package com.dawnline.ops.adapter.in.web;

import com.dawnline.observability.MdcKeys;
import com.dawnline.ops.application.port.in.RunOpsCommandUseCase.Outcome;
import com.dawnline.ops.application.port.out.CoreReply;
import com.dawnline.web.ProblemDetailsAdviceSupport;
import java.net.URI;
import org.jspecify.annotations.Nullable;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;

/**
 * 코어의 답을 운영자에게 돌려줄 응답으로 옮긴다 (DESIGN.md §5.5 「커맨드 위임」).
 *
 * <table>
 *   <caption>답 → 응답</caption>
 *   <tr><th>답</th><th>감사</th><th>응답</th></tr>
 *   <tr><td>2xx</td><td>{@code SUCCEEDED}</td><td>200, 코어의 본문을 옮긴 것</td></tr>
 *   <tr><td>4xx</td><td>{@code REJECTED}</td><td>코어의 상태와 Problem Details 본문, 바이트 그대로</td></tr>
 *   <tr><td>연결 안 됨</td><td>{@code FAILED}</td><td>502 {@code core-unreachable}</td></tr>
 *   <tr><td>타임아웃</td><td>{@code UNKNOWN}</td><td>504 {@code core-timeout}</td></tr>
 *   <tr><td>5xx·끊김</td><td>{@code UNKNOWN}</td><td>502 {@code core-error}</td></tr>
 * </table>
 *
 * <p>어느 경우든 {@value MdcKeys#AUDIT_ID_HEADER} 헤더에 감사 행 id 가 온다 — 운영자가 결과를 모를 때
 * 가리킬 것이 그 id 다. 우리가 만드는 문제 본문에도 {@code auditId} 로 싣는다.
 */
final class CommandResponses {

    static final String CORE_UNREACHABLE = "core-unreachable";
    static final String CORE_TIMEOUT = "core-timeout";
    static final String CORE_ERROR = "core-error";

    private CommandResponses() {
    }

    static ResponseEntity<?> of(Outcome outcome, String path) {
        HttpHeaders headers = new HttpHeaders();
        headers.set(MdcKeys.AUDIT_ID_HEADER, outcome.auditId().toString());
        return switch (outcome.reply()) {
            case CoreReply.Applied applied -> new ResponseEntity<>(applied.body(), headers, HttpStatus.OK);
            case CoreReply.Rejected rejected -> {
                headers.setContentType(rejected.contentType() == null
                        ? MediaType.APPLICATION_PROBLEM_JSON : MediaType.parseMediaType(rejected.contentType()));
                yield new ResponseEntity<>(rejected.problem(), headers, HttpStatusCode.valueOf(rejected.status()));
            }
            case CoreReply.Unreachable unreachable -> problem(headers, HttpStatus.BAD_GATEWAY, CORE_UNREACHABLE,
                    "코어에 연결하지 못했다 — 적용되지 않았다", outcome, path);
            case CoreReply.Unknown unknown when unknown.timedOut() -> problem(headers, HttpStatus.GATEWAY_TIMEOUT,
                    CORE_TIMEOUT, "코어가 제시간에 답하지 않았다 — 적용됐는지 모른다. 감사 id 로 확인한다", outcome, path);
            case CoreReply.Unknown unknown -> problem(headers, HttpStatus.BAD_GATEWAY, CORE_ERROR,
                    "코어가 오류로 답했거나 응답이 끊겼다 — 적용됐는지 모른다. 감사 id 로 확인한다", outcome, path);
        };
    }

    /**
     * 조회의 답 — 감사 행이 없으므로 감사 id 헤더도 없고, 「적용됐는지 모른다」도 없다(읽기는 아무것도 바꾸지 않는다).
     * 갈래와 상태 코드는 커맨드와 같다.
     */
    static ResponseEntity<?> ofQuery(CoreReply reply, String path) {
        HttpHeaders headers = new HttpHeaders();
        return switch (reply) {
            case CoreReply.Applied applied -> new ResponseEntity<>(applied.body(), headers, HttpStatus.OK);
            case CoreReply.Rejected rejected -> {
                headers.setContentType(rejected.contentType() == null
                        ? MediaType.APPLICATION_PROBLEM_JSON : MediaType.parseMediaType(rejected.contentType()));
                yield new ResponseEntity<>(rejected.problem(), headers, HttpStatusCode.valueOf(rejected.status()));
            }
            case CoreReply.Unreachable unreachable -> problem(headers, HttpStatus.BAD_GATEWAY, CORE_UNREACHABLE,
                    "코어에 연결하지 못했다", null, path);
            case CoreReply.Unknown unknown when unknown.timedOut() -> problem(headers, HttpStatus.GATEWAY_TIMEOUT,
                    CORE_TIMEOUT, "코어가 제시간에 답하지 않았다", null, path);
            case CoreReply.Unknown unknown -> problem(headers, HttpStatus.BAD_GATEWAY, CORE_ERROR,
                    "코어가 오류로 답했거나 응답이 끊겼다", null, path);
        };
    }

    private static ResponseEntity<ProblemDetail> problem(HttpHeaders headers, HttpStatus status, String code,
            String detail, @Nullable Outcome outcome, String path) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, detail);
        problem.setType(URI.create(ProblemDetailsAdviceSupport.PROBLEM_TYPE_PREFIX + code));
        problem.setInstance(URI.create(path));
        problem.setProperty("code", code);
        if (outcome != null) {
            problem.setProperty(MdcKeys.AUDIT_ID, outcome.auditId().toString());
        }
        headers.setContentType(MediaType.APPLICATION_PROBLEM_JSON);
        return new ResponseEntity<>(problem, headers, status);
    }
}
