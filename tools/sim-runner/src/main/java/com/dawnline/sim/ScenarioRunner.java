package com.dawnline.sim;

import com.dawnline.sim.config.SimProperties;
import com.dawnline.sim.order.OrderGenerator;
import com.dawnline.sim.order.ScenarioReport;
import com.dawnline.sim.order.SmokeScenario;
import java.util.Objects;
import java.util.random.RandomGenerator;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.ExitCodeGenerator;
import org.springframework.stereotype.Component;

/**
 * 시나리오 하나를 실행하고 종료 코드로 결과를 말한다.
 *
 * <p>종료 코드가 중요하다. {@code make demo} 같은 스크립트가 이 도구를 부르는데, 실패를
 * 0 으로 끝내면 <strong>스크립트가 성공했다고 말한 뒤 DB 는 비어 있는</strong> 상황이 된다.
 * Makefile 의 미구현 타깃들이 {@code exit 2} 로 끝나는 것과 같은 이유다.
 */
@Component
public class ScenarioRunner implements CommandLineRunner, ExitCodeGenerator {

    private static final Logger log = LoggerFactory.getLogger(ScenarioRunner.class);

    /** 시나리오가 실패했을 때의 종료 코드. */
    static final int FAILURE_EXIT_CODE = 1;

    private final SimProperties properties;
    private final SmokeScenario smoke;
    private final RandomGeneratorFactory randomFactory;
    private final RunIds runIds;

    private int exitCode;
    private @Nullable ScenarioReport lastReport;

    /**
     * @param properties    설정
     * @param smoke         smoke 시나리오
     * @param randomFactory seed → 난수원. 주입하는 이유는 불변규칙 12 그대로다
     * @param runIds        실행 식별자 생성기. 멱등 키 접두어가 되므로 실행마다 달라야 한다
     */
    public ScenarioRunner(SimProperties properties, SmokeScenario smoke,
            RandomGeneratorFactory randomFactory, RunIds runIds) {
        this.properties = Objects.requireNonNull(properties, "properties");
        this.smoke = Objects.requireNonNull(smoke, "smoke");
        this.randomFactory = Objects.requireNonNull(randomFactory, "randomFactory");
        this.runIds = Objects.requireNonNull(runIds, "runIds");
    }

    @Override
    public void run(String... args) throws InterruptedException {
        SimProperties.Scenario scenario = properties.selected();
        OrderGenerator generator = new OrderGenerator(scenario, randomFactory.create(scenario.seed()));

        ScenarioReport report =
                smoke.run(properties.scenario(), scenario, generator, runIds.next());
        this.lastReport = report;
        this.exitCode = report.isSuccess() ? 0 : FAILURE_EXIT_CODE;

        log.info("시나리오 '{}' 완료\n{}", properties.scenario(), report.toMarkdown());
        if (!report.isSuccess()) {
            log.error("주문 {}건 중 {}건만 접수되었다. 위 표의 code 별 건수를 보라.",
                    report.requested(), report.accepted());
        }
    }

    @Override
    public int getExitCode() {
        return exitCode;
    }

    /** 마지막 실행 결과. 테스트가 본다. */
    public @Nullable ScenarioReport lastReport() {
        return lastReport;
    }

    /** seed 로 난수원을 만든다. */
    @FunctionalInterface
    public interface RandomGeneratorFactory {

        /**
         * @param seed 난수 seed
         */
        RandomGenerator create(long seed);
    }

    /** 실행 식별자. 멱등 키 접두어가 된다. */
    @FunctionalInterface
    public interface RunIds {

        /** 이번 실행의 식별자. */
        String next();
    }
}
