package com.dawnline.tracking.adapter.in.web;

import static org.assertj.core.api.Assertions.assertThat;

import com.dawnline.common.error.CommonErrorCode;
import com.dawnline.common.error.ConflictException;
import com.dawnline.common.error.DomainException;
import com.dawnline.common.error.IllegalStateTransitionException;
import com.dawnline.common.error.NotFoundException;
import com.dawnline.common.error.ValidationException;
import com.dawnline.tracking.domain.ShipmentStatus;
import jakarta.servlet.http.HttpServletRequest;
import java.net.URI;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.DisplayNameGeneration;
import org.junit.jupiter.api.DisplayNameGenerator;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.context.request.ServletWebRequest;

/**
 * 오류 응답의 모양 (RFC 9457, CLAUDE.md 「코딩 컨벤션」).
 *
 * <p>{@code ScanApiIT} 가 실물 요청으로 보는 것과 별개로, 어드바이스 자체는 서블릿 컨테이너 없이
 * 검증할 수 있다. 특히 {@code type} 이 {@code null} 인 프레임워크 ProblemDetail 을 다루는 경로는
 * 여기서 훨씬 싸게 잡힌다 — order-service 에서 그 널을 빠뜨려 「본문 없는 400」이 나갔었다.
 */
@DisplayNameGeneration(DisplayNameGenerator.ReplaceUnderscores.class)
@DisplayName("ProblemDetailsAdvice — RFC 9457 오류 응답")
class ProblemDetailsAdviceTest {

    private static final String TYPE_PREFIX = "https://dawnline.internal/problems/";

    private static final String SCAN_URI = "/api/v1/routes/r/stops/1/events";

    private final ProblemDetailsAdvice advice = new ProblemDetailsAdvice();

    @Test
    void 도메인_예외는_type_과_code_를_채운다() {
        // type 이 about:blank 면 단말이 status 와 사람이 읽는 문장으로 분기해야 하고,
        // 문장은 언제든 바뀐다.
        ResponseEntity<ProblemDetail> response =
                advice.handleDomain(NotFoundException.of("Stop", "r/1"), request());

        ProblemDetail problem = response.getBody();
        assertThat(response.getStatusCode().value()).isEqualTo(404);
        assertThat(problem).isNotNull();
        assertThat(problem.getType()).isEqualTo(URI.create(TYPE_PREFIX + "not-found"));
        assertThat(problem.getProperties()).containsEntry("code", "not-found");
        assertThat(problem.getInstance()).isEqualTo(URI.create(SCAN_URI));
    }

    @Test
    void 예외의_상세가_확장_멤버로_나간다() {
        DomainException exception = new DomainException(CommonErrorCode.UNPROCESSABLE_REQUEST,
                "안 됩니다", Map.of("field", "stopSeq"));

        ProblemDetail problem = advice.handleDomain(exception, request()).getBody();

        assertThat(problem).isNotNull();
        assertThat(problem.getProperties()).containsEntry("field", "stopSeq");
    }

    @Test
    void 검증_예외는_400_이고_어긴_필드를_싣는다() {
        ProblemDetail problem = advice
                .handleDomain(ValidationException.field("stopSeq", 0, "1 이상이어야 합니다"), request())
                .getBody();

        assertThat(problem).isNotNull();
        assertThat(problem.getStatus()).isEqualTo(400);
        assertThat(problem.getProperties()).containsEntry("field", "stopSeq");
    }

    @Test
    void 잘못된_상태_전이는_409_다() {
        // 여기까지 오는 것은 상류의 결함이다 — 역행 스캔은 200 + STALE 이다 (§5.4 축 규칙).
        ProblemDetail problem = advice.handleDomain(
                new IllegalStateTransitionException("Shipment",
                        ShipmentStatus.COMPLETED, ShipmentStatus.ARRIVED), request()).getBody();

        assertThat(problem).isNotNull();
        assertThat(problem.getStatus()).isEqualTo(409);
        assertThat(problem.getType()).isEqualTo(URI.create(TYPE_PREFIX + "illegal-state-transition"));
    }

    @Test
    void 이_서비스의_오류에는_Retry_After_가_붙지_않는다() {
        // 표가 비어 있는 것이 「빠뜨렸다」가 아니라 「그런 오류가 없다」임을 여기서 말한다.
        // 404 는 재시도가 통하지만 <언제>인지를 우리가 모른다(브로커 랙에 달려 있다) —
        // 모르는 값을 적어 두면 그 숫자가 계약이 된다.
        assertThat(advice.handleDomain(NotFoundException.of("Stop", "r/1"), request())
                .getHeaders().getFirst(HttpHeaders.RETRY_AFTER)).isNull();
        assertThat(advice.handleDomain(new ConflictException("충돌"), request())
                .getHeaders().getFirst(HttpHeaders.RETRY_AFTER)).isNull();
    }

    @Test
    void 예외가_실어_온_대기_시간은_그대로_쓴다() {
        DomainException exception = new DomainException(CommonErrorCode.UNAVAILABLE, "잠시 후",
                Map.of(ProblemDetailsAdvice.RETRY_AFTER_DETAIL, 5));

        assertThat(advice.handleDomain(exception, request())
                .getHeaders().getFirst(HttpHeaders.RETRY_AFTER)).isEqualTo("5");
    }

    @Test
    void 프레임워크가_만든_type_이_null_인_ProblemDetail_도_분류한다() {
        // Spring 7 의 ProblemDetail 은 type 이 about:blank 가 아니라 null 이다. 널 비교를
        // 빠뜨리면 예외 처리기 안에서 NPE 가 나고 응답이 본문 없는 400 으로 조용히 나간다.
        ProblemDetail raw = ProblemDetail.forStatusAndDetail(
                HttpStatusCode.valueOf(400), "본문을 읽을 수 없습니다");
        assertThat(raw.getType()).as("전제: 프레임워크는 type 을 비워 둔다").isNull();

        ResponseEntity<Object> response = advice.createResponseEntity(raw, new HttpHeaders(),
                HttpStatusCode.valueOf(400), webRequest());

        ProblemDetail problem = (ProblemDetail) response.getBody();
        assertThat(problem).isNotNull();
        assertThat(problem.getType()).isEqualTo(URI.create(TYPE_PREFIX + "validation-failed"));
        assertThat(problem.getProperties()).containsEntry("code", "validation-failed");
        assertThat(problem.getInstance()).isEqualTo(URI.create(SCAN_URI));
    }

    @Test
    void 상태_코드에_맞는_코드를_고른다() {
        assertThat(classify(404).getProperties()).containsEntry("code", "not-found");
        assertThat(classify(409).getProperties()).containsEntry("code", "conflict");
        assertThat(classify(415).getProperties()).containsEntry("code", "validation-failed");
        assertThat(classify(503).getProperties()).containsEntry("code", "unavailable");
    }

    @Test
    void 이미_분류된_ProblemDetail_은_덮어쓰지_않는다() {
        ProblemDetail ours = ProblemDetail.forStatusAndDetail(HttpStatusCode.valueOf(422), "이미 우리 것");
        ours.setType(URI.create(TYPE_PREFIX + "unprocessable-request"));
        ours.setProperty("code", "unprocessable-request");

        advice.createResponseEntity(ours, new HttpHeaders(), HttpStatusCode.valueOf(422), webRequest());

        assertThat(ours.getType()).isEqualTo(URI.create(TYPE_PREFIX + "unprocessable-request"));
    }

    @Test
    void 예상하지_못한_예외는_내부_정보를_흘리지_않는다() {
        // 파티션 밖 시각의 INSERT 가 여기로 온다. 메시지에 테이블 이름과 SQL 이 들어 있다 —
        // 그것은 로그가 받을 것이지 단말이 받을 것이 아니다 (§9.3).
        ResponseEntity<ProblemDetail> response = advice.handleUnexpected(
                new IllegalStateException(
                        "no partition of relation \"shipment_events\" found for row"), request());

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
        ProblemDetail problem = response.getBody();
        assertThat(problem).isNotNull();
        assertThat(problem.getDetail()).isEqualTo("요청을 처리하지 못했습니다");
        assertThat(problem.toString()).doesNotContain("shipment_events").doesNotContain("partition");
    }

    private ProblemDetail classify(int status) {
        ProblemDetail raw = ProblemDetail.forStatusAndDetail(HttpStatusCode.valueOf(status), "무엇인가");
        advice.createResponseEntity(raw, new HttpHeaders(), HttpStatusCode.valueOf(status), webRequest());
        return raw;
    }

    private static HttpServletRequest request() {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", SCAN_URI);
        request.setRequestURI(SCAN_URI);
        return request;
    }

    private static ServletWebRequest webRequest() {
        return new ServletWebRequest(new MockHttpServletRequest("POST", SCAN_URI));
    }
}
