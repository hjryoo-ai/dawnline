/**
 * 코어 서비스로의 커맨드 위임 — 아웃바운드 어댑터 (DESIGN.md §5.5 「커맨드 위임」, ADR-052).
 *
 * <p>하위 패키지 {@code dispatch.*}·{@code order.*} 는 <strong>이 저장소에 없다</strong> — 커밋된
 * {@code contracts/openapi/*.yaml} 에서 빌드 때 생성되는 산출물이다({@code build/generated/core-clients}).
 * 여기 있는 것은 그 생성물을 부르고 답을 {@code CoreReply} 의 갈래로 옮기는 코드뿐이다.
 */
@NullMarked
package com.dawnline.ops.adapter.out.core;

import org.jspecify.annotations.NullMarked;
