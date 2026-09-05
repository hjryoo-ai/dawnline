package com.dawnline.dispatch.application.port.out;

import com.dawnline.dispatch.domain.optimizer.RuleSet;
import java.util.UUID;

/** 캠프에 적용할 룰 묶음 (DESIGN.md §6.3). */
public interface RuleCatalog {

    /**
     * 전역 룰에 캠프 오버라이드를 병합한 묶음.
     *
     * <p>병합이 여기 있는 이유는 순수 함수가 "누가 이겼는지" 가 아니라 "무엇이 적용되는지" 만
     * 알아야 하기 때문이다({@code RuleSet} 주석). 계획이 시작되면 이 묶음은 고정이다 —
     * 진행 중 계획은 시작 시점 스냅샷을 쓴다(§6.3).
     *
     * @param campId 캠프 id
     */
    RuleSet forCamp(UUID campId);
}
