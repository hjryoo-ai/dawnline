/**
 * 경로 최적화 엔진 (DESIGN.md §6). Phase 3~4 산출물.
 *
 * <p><b>순수 Java 여야 한다.</b> {@code tools/benchmark} 가 Spring 컨텍스트 없이 이 패키지를
 * 그대로 실행하기 때문이다(CLAUDE.md 불변규칙 5). 시간과 난수는 {@code Clock}·
 * {@code RandomGenerator} 로 주입받고, seed 가 같으면 결과가 같아야 한다(불변규칙 12).
 */
@NullMarked
package com.dawnline.dispatch.domain.optimizer;

import org.jspecify.annotations.NullMarked;
