/**
 * DLQ 재처리 — ops-api 가 브로커에 직접 보내는 유일한 자리 (DESIGN.md §4.6 「DLQ 재처리」, ADR-053).
 *
 * <p>불변규칙 1(Outbox 필수)의 예외다. 성립하는 이유는 「상태가 없어서」가 아니라 <strong>「상태가 다른 곳에
 * 있어서」</strong>다 — 이 발행의 상태는 {@code audit_logs} 의 행이고, 부르는 쪽이 {@code PENDING} 을 커밋한 뒤에만
 * 보낸다. 도메인 상태를 바꾸는 발행은 이 패키지에 올 수 없다.
 */
@NullMarked
package com.dawnline.ops.adapter.out.messaging;

import org.jspecify.annotations.NullMarked;
