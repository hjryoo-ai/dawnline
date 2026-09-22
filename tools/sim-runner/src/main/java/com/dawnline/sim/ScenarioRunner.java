package com.dawnline.sim;

import com.dawnline.sim.config.SimProperties;
import com.dawnline.sim.driver.DriverReport;
import com.dawnline.sim.driver.DriverScenario;
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
    private final DriverScenario driverScenario;
    private final RandomGeneratorFactory randomFactory;
    private final RunIds runIds;

    private int exitCode;
    private @Nullable ScenarioReport lastReport;
    private @Nullable DriverReport lastDriverReport;

    /**
     * @param properties     설정
     * @param smoke          smoke 시나리오
     * @param driverScenario 기사 시뮬레이션. 기사 설정이 없는 시나리오에서는 아무 일도 하지 않는다
     * @param randomFactory  seed → 난수원. 주입하는 이유는 불변규칙 12 그대로다
     * @param runIds         실행 식별자 생성기. 멱등 키 접두어가 되므로 실행마다 달라야 한다
     */
    public ScenarioRunner(SimProperties properties, SmokeScenario smoke, DriverScenario driverScenario,
            RandomGeneratorFactory randomFactory, RunIds runIds) {
        this.properties = Objects.requireNonNull(properties, "properties");
        this.smoke = Objects.requireNonNull(smoke, "smoke");
        this.driverScenario = Objects.requireNonNull(driverScenario, "driverScenario");
        this.randomFactory = Objects.requireNonNull(randomFactory, "randomFactory");
        this.runIds = Objects.requireNonNull(runIds, "runIds");
    }

    @Override
    public void run(String... args) throws InterruptedException {
        SimProperties.Scenario scenario = properties.selected();
        boolean withDriver = scenario.driver() != null;

        // 수신을 주문보다 먼저 켠다. 뒤면 그 사이에 확정된 라우트를 놓치고,
        // 놓친 것은 "라우트가 안 왔다" 로 보여서 원인이 도구인지 스택인지 구별되지 않는다.
        if (withDriver) {
            driverScenario.open();
        }

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

        if (!withDriver) {
            return;
        }
        DriverReport driverReport = driverScenario.awaitAndReport(properties.scenario());
        this.lastDriverReport = driverReport;
        log.info("기사 시뮬레이션 완료\n{}", driverReport.toMarkdown());
        if (!driverReport.isSuccess()) {
            // 주문이 전부 접수되어도 기사가 못 돌았으면 실패다 — 스크립트가 "성공" 이라고 말한 뒤
            // 배송이 하나도 진행되지 않은 상황을 만들지 않는다.
            this.exitCode = FAILURE_EXIT_CODE;
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

    /** 마지막 기사 시뮬레이션 결과. 기사를 쓰지 않는 시나리오면 {@code null}. */
    public @Nullable DriverReport lastDriverReport() {
        return lastDriverReport;
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
