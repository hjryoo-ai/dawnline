/**
 * §6.3 룰 카탈로그의 평가기들.
 *
 * <p>룰 <em>정의</em>는 데이터({@code dispatch_rules} 또는 {@code contracts/seed/dispatch-rules.json})
 * 이고 여기에는 타입별 <em>평가기</em>만 있다. 그래서 새 룰을 켜는 데 배포가 필요 없다.
 *
 * <p>이 패키지도 프레임워크 비의존이다(불변규칙 5). JSON 을 읽는 일은 여기서 하지 않는다 —
 * {@link com.dawnline.dispatch.domain.optimizer.rule.RuleDefinition} 은 이미 파싱된
 * {@code Map<String, Object>} 를 받고, 파싱은 어댑터와 벤치마크 하네스가 각자 한 줄로 한다.
 */
package com.dawnline.dispatch.domain.optimizer.rule;
