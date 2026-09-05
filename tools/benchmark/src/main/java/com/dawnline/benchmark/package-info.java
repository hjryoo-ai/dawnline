/**
 * 전략 비교 벤치마크 CLI (DESIGN.md §6.9).
 *
 * <h2>이 모듈이 증명하는 것</h2>
 * {@code dispatch-service} 의 {@code domain.optimizer} 를 <strong>서비스를 띄우지 않고 그대로</strong>
 * 실행한다. 그것이 불변규칙 5 가 존재하는 이유이고, 여기에 Spring 이 들어오는 순간 그 주장이
 * 증명되지 않는다. 그래서 이 모듈에는 Spring 의존이 없다.
 *
 * <p>수치는 환경과 함께 남긴다 — 환경 없는 수치는 나중에 비교 대상이 되지 못한다.
 */
package com.dawnline.benchmark;
