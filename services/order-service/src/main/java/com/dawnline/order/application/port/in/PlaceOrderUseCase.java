package com.dawnline.order.application.port.in;

/**
 * 주문 접수 (DESIGN.md §5.1 {@code POST /api/v1/orders}).
 *
 * <p>인바운드 포트다. 구현({@code application.PlaceOrderService})은 멱등 처리와 트랜잭션 경계를
 * 담당하고, 웹 어댑터는 HTTP ↔ 명령 변환만 한다.
 */
@FunctionalInterface
public interface PlaceOrderUseCase {

    /**
     * 주문을 접수한다. 같은 멱등 키의 재요청이면 저장된 응답을 재생한다.
     *
     * @param command 접수 명령
     * @return 접수 결과 (신규 또는 재생)
     * @throws com.dawnline.common.error.ValidationException   요청 값이 유효하지 않을 때 (400)
     * @throws com.dawnline.common.error.DomainException       같은 키에 다른 요청(422), 처리 중인 키(409)
     */
    PlaceOrderResult place(PlaceOrderCommand command);
}
