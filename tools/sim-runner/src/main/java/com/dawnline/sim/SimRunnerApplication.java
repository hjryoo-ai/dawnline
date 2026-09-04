package com.dawnline.sim;

import com.dawnline.sim.config.SimProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

/**
 * 시나리오 CLI (DESIGN.md §5.6).
 *
 * <p>Phase 1 에서는 <strong>주문 생성기만</strong> 있다. 기사 시뮬레이터는 라우트가 생기는
 * Phase 5 의 일이다(IMPLEMENTATION_PLAN Phase 5-2).
 *
 * <p>서버를 띄우지 않는다. 시나리오를 실행하고 종료 코드로 결과를 말한다 — 주문이 하나라도
 * 접수되지 않으면 0 이 아닌 코드로 끝나서, 스크립트가 성공으로 오인하지 않게 한다.
 */
@SpringBootApplication
@EnableConfigurationProperties(SimProperties.class)
public class SimRunnerApplication {

    /**
     * @param args 스프링 인자. 예: {@code --dawnline.sim.scenario=smoke}
     */
    public static void main(String[] args) {
        System.exit(SpringApplication.exit(SpringApplication.run(SimRunnerApplication.class, args)));
    }
}
