package com.dawnline.sim;

import com.dawnline.sim.config.SimProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

/**
 * 시나리오 CLI (DESIGN.md §5.6).
 *
 * <p>시나리오는 둘로 나뉜다. <strong>주문 생성기</strong>는 언제나 돌고(Phase 1),
 * <strong>기사 시뮬레이터</strong>는 시나리오에 {@code driver} 절이 있을 때만 돈다(Phase 5-2,
 * {@link com.dawnline.sim.driver}). 나누어 둔 이유는 {@code smoke} 가 브로커 없이 돌아야 하기
 * 때문이다 — 기사가 붙는 순간 Kafka 가 필수가 된다.
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
