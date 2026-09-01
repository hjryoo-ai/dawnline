package com.dawnline.common.error;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.stream.Collectors;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("에러 모델 — DomainException / ErrorCode (프레임워크 비의존)")
class ErrorModelTest {

    @Test
    void CommonErrorCode_의_코드는_유일하고_HTTP_상태와_title_을_모두_가진다() {
        assertThat(Arrays.stream(CommonErrorCode.values()).map(ErrorCode::code).collect(Collectors.toSet()))
                .hasSize(CommonErrorCode.values().length);

        for (CommonErrorCode code : CommonErrorCode.values()) {
            assertThat(code.code()).isNotBlank().matches("[a-z][a-z-]*[a-z]");
            assertThat(code.status()).isBetween(400, 599);
            assertThat(code.title()).isNotBlank();
        }
    }

    @Test
    void CommonErrorCode_의_HTTP_상태는_설계서와_일치한다() {
        assertThat(CommonErrorCode.VALIDATION_FAILED.status()).isEqualTo(400);
        assertThat(CommonErrorCode.NOT_FOUND.status()).isEqualTo(404);
        assertThat(CommonErrorCode.CONFLICT.status()).isEqualTo(409);
        assertThat(CommonErrorCode.ILLEGAL_STATE_TRANSITION.status()).isEqualTo(409);
        assertThat(CommonErrorCode.UNPROCESSABLE_REQUEST.status()).isEqualTo(422);
        assertThat(CommonErrorCode.UNAVAILABLE.status()).isEqualTo(503);
        assertThat(CommonErrorCode.VALIDATION_FAILED.code()).isEqualTo("validation-failed");
    }

    @Test
    void DomainException_은_코드_상태_상세를_함께_들고_다닌다() {
        DomainException exception = new DomainException(
                CommonErrorCode.UNPROCESSABLE_REQUEST,
                "같은 멱등 키에 다른 본문입니다",
                Map.of("idempotencyKey", "abc"));

        assertThat(exception).isInstanceOf(RuntimeException.class);
        assertThat(exception.getMessage()).isEqualTo("같은 멱등 키에 다른 본문입니다");
        assertThat(exception.errorCode()).isEqualTo(CommonErrorCode.UNPROCESSABLE_REQUEST);
        assertThat(exception.code()).isEqualTo("unprocessable-request");
        assertThat(exception.status()).isEqualTo(422);
        assertThat(exception.details()).containsExactly(Map.entry("idempotencyKey", "abc"));
        assertThat(exception.getCause()).isNull();
    }

    @Test
    void DomainException_의_상세는_삽입_순서를_유지하고_변경할_수_없다() {
        Map<String, Object> source = new LinkedHashMap<>();
        source.put("first", 1);
        source.put("second", 2);
        source.put("third", 3);

        DomainException exception = new DomainException(CommonErrorCode.CONFLICT, "충돌", source);

        assertThat(exception.details().keySet()).containsExactly("first", "second", "third");
        assertThatThrownBy(() -> exception.details().put("fourth", 4))
                .isInstanceOf(UnsupportedOperationException.class);

        // 원본 맵을 나중에 바꿔도 예외의 상세는 영향을 받지 않는다(방어적 복사)
        source.put("fourth", 4);
        assertThat(exception.details()).hasSize(3);
    }

    @Test
    void DomainException_은_원인_예외를_보존한다() {
        IllegalStateException cause = new IllegalStateException("원인");

        DomainException exception = new DomainException(
                CommonErrorCode.UNAVAILABLE, "Redis 연결 실패", Map.of(), cause);

        assertThat(exception).hasCause(cause);
        assertThat(exception.details()).isEmpty();
    }

    @Test
    void DomainException_은_null_인자를_거부한다() {
        assertThatThrownBy(() -> new DomainException(null, "메시지"))
                .isInstanceOf(NullPointerException.class)
                .hasMessage("errorCode");
        assertThatThrownBy(() -> new DomainException(CommonErrorCode.CONFLICT, null))
                .isInstanceOf(NullPointerException.class)
                .hasMessage("message");
        assertThatThrownBy(() -> new DomainException(CommonErrorCode.CONFLICT, "메시지", null))
                .isInstanceOf(NullPointerException.class)
                .hasMessage("details");
    }

    @Test
    void ValidationException_은_400_이고_field_팩토리로_필드_상세를_담는다() {
        ValidationException plain = new ValidationException("값이 잘못되었습니다");
        ValidationException withDetails =
                new ValidationException("값이 잘못되었습니다", Map.of("k", "v"));
        ValidationException field = ValidationException.field("lat", 120.0d, "위도 범위를 벗어났습니다");

        assertThat(plain.status()).isEqualTo(400);
        assertThat(plain.details()).isEmpty();
        assertThat(withDetails.details()).containsEntry("k", "v");
        assertThat(field.getMessage()).isEqualTo("lat: 위도 범위를 벗어났습니다");
        assertThat(field.details())
                .containsEntry("field", "lat")
                .containsEntry("value", "120.0")
                .containsEntry("reason", "위도 범위를 벗어났습니다");
    }

    @Test
    void NotFoundException_은_404_이고_리소스와_식별자를_담는다() {
        NotFoundException byFactory = NotFoundException.of("Order", "0198f1b2-0000-7000-8000-000000000001");
        NotFoundException plain = new NotFoundException("없습니다");
        NotFoundException withDetails = new NotFoundException("없습니다", Map.of("k", "v"));

        assertThat(byFactory.status()).isEqualTo(404);
        assertThat(byFactory.code()).isEqualTo("not-found");
        assertThat(byFactory.getMessage()).startsWith("Order 를 찾을 수 없습니다: ");
        assertThat(byFactory.details())
                .containsEntry("resource", "Order")
                .containsEntry("id", "0198f1b2-0000-7000-8000-000000000001");
        assertThat(plain.details()).isEmpty();
        assertThat(withDetails.details()).containsEntry("k", "v");
    }

    @Test
    void ConflictException_은_409_다() {
        ConflictException plain = new ConflictException("이미 처리 중입니다");
        ConflictException withDetails =
                new ConflictException("이미 처리 중입니다", Map.of("state", "IN_PROGRESS"));

        assertThat(plain.status()).isEqualTo(409);
        assertThat(plain.code()).isEqualTo("conflict");
        assertThat(withDetails.details()).containsEntry("state", "IN_PROGRESS");
    }

    @Test
    void IllegalStateTransitionException_은_전이_정보를_기계가_읽을_수_있게_담는다() {
        IllegalStateTransitionException exception =
                new IllegalStateTransitionException("Order", "DISPATCHED", "CANCELLED");

        assertThat(exception.status()).isEqualTo(409);
        assertThat(exception.code()).isEqualTo("illegal-state-transition");
        assertThat(exception.getMessage()).isEqualTo("Order: DISPATCHED → CANCELLED 전이는 허용되지 않습니다");
        assertThat(exception.details())
                .containsEntry("aggregate", "Order")
                .containsEntry("currentState", "DISPATCHED")
                .containsEntry("attempted", "CANCELLED");
    }

    @Test
    void 서비스는_자기_전용_ErrorCode_를_추가로_정의할_수_있다() {
        // libs/common 은 인터페이스만 강제하고, 서비스별 코드는 각자 enum 으로 확장한다.
        ErrorCode serviceSpecific = new ErrorCode() {
            @Override
            public String code() {
                return "order-already-dispatched";
            }

            @Override
            public int status() {
                return 409;
            }

            @Override
            public String title() {
                return "이미 배차된 주문입니다";
            }
        };

        DomainException exception = new DomainException(serviceSpecific, "이미 배차됨");

        assertThat(exception.code()).isEqualTo("order-already-dispatched");
        assertThat(exception.status()).isEqualTo(409);
        assertThat(exception.errorCode().title()).isEqualTo("이미 배차된 주문입니다");
    }

    @Test
    void DomainException_은_프레임워크_타입이_아니라_RuntimeException_을_상속한다() {
        // CLAUDE.md 불변규칙 5 — HTTP 매핑은 각 서비스의 @ControllerAdvice 몫이다.
        assertThat(DomainException.class.getSuperclass()).isEqualTo(RuntimeException.class);
        assertThat(ValidationException.class.getSuperclass()).isEqualTo(DomainException.class);
        assertThat(NotFoundException.class.getSuperclass()).isEqualTo(DomainException.class);
        assertThat(ConflictException.class.getSuperclass()).isEqualTo(DomainException.class);
        assertThat(IllegalStateTransitionException.class.getSuperclass())
                .isEqualTo(DomainException.class);
    }
}
