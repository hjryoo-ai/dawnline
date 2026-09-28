package com.dawnline.sim;

import com.dawnline.sim.config.SimProperties;
import com.dawnline.sim.driver.DepartureGate;
import com.dawnline.sim.driver.DriverReport;
import com.dawnline.sim.driver.DriverScenario;
import com.dawnline.sim.fleet.FleetFailure;
import com.dawnline.sim.fleet.FleetReport;
import com.dawnline.sim.fleet.PeakFleet;
import com.dawnline.sim.order.CancelReport;
import com.dawnline.sim.order.OrderCancellations;
import com.dawnline.sim.order.OrderGenerator;
import com.dawnline.sim.order.ScenarioReport;
import com.dawnline.sim.order.SmokeScenario;
import com.dawnline.sim.order.WindowStart;
import java.time.Instant;
import java.time.LocalTime;
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
    private final WindowStart windowStart;
    private final PeakFleet peakFleet;
    private final CancellationsFactory cancellationsFactory;

    private int exitCode;
    private @Nullable ScenarioReport lastReport;
    private @Nullable DriverReport lastDriverReport;
    private @Nullable FleetReport lastFleetReport;
    private @Nullable CancelReport lastCancelReport;

    /**
     * @param properties     설정
     * @param smoke          smoke 시나리오
     * @param driverScenario 기사 시뮬레이션. 기사 설정이 없는 시나리오에서는 아무 일도 하지 않는다
     * @param randomFactory  seed → 난수원. 주입하는 이유는 불변규칙 12 그대로다
     * @param runIds         실행 식별자 생성기. 멱등 키 접두어가 되므로 실행마다 달라야 한다
     * @param windowStart    창의 시작까지 기다림 — 시나리오에 {@code start-at} 이 있을 때만 쓴다
     * @param peakFleet      함대 단계 — 시나리오에 {@code fleet} 이 있을 때만 쓴다(ADR-067)
     * @param cancellationsFactory 취소 — 시나리오에 {@code cancel} 이 있을 때만 쓴다(7-4 turbulent)
     */
    public ScenarioRunner(SimProperties properties, SmokeScenario smoke, DriverScenario driverScenario,
            RandomGeneratorFactory randomFactory, RunIds runIds, WindowStart windowStart, PeakFleet peakFleet,
            CancellationsFactory cancellationsFactory) {
        this.properties = Objects.requireNonNull(properties, "properties");
        this.smoke = Objects.requireNonNull(smoke, "smoke");
        this.driverScenario = Objects.requireNonNull(driverScenario, "driverScenario");
        this.randomFactory = Objects.requireNonNull(randomFactory, "randomFactory");
        this.runIds = Objects.requireNonNull(runIds, "runIds");
        this.windowStart = Objects.requireNonNull(windowStart, "windowStart");
        this.peakFleet = Objects.requireNonNull(peakFleet, "peakFleet");
        this.cancellationsFactory = Objects.requireNonNull(cancellationsFactory, "cancellationsFactory");
    }

    @Override
    public void run(String... args) throws InterruptedException {
        SimProperties.Scenario scenario = properties.selected();
        boolean withDriver = scenario.driver() != null;

        // 함대 단계의 전제를 창을 기다리기 전에 본다 — 앞 실행이 남긴 peak-sim 차량이 있으면 한 시간을 보내고 나서 알 이유가 없다.
        PeakFleet.@Nullable Session fleet = null;
        if (scenario.fleet() != null) {
            if (properties.ops().token().isBlank()) {
                log.error("시나리오 '{}' 는 함대 단계가 있다 — ops-api 운영자 토큰(DAWNLINE_SIM_OPS_TOKEN, make token ROLE=OPS_OPERATOR)"
                        + "이 없어 전제를 볼 수 없다", properties.scenario());
                this.exitCode = FAILURE_EXIT_CODE;
                return;
            }
            fleet = peakFleet.open(scenario.fleet());
            try {
                fleet.requireNoLeftovers();
            } catch (FleetFailure e) {
                log.error("시나리오 '{}' 를 시작하지 않았다 — {}", properties.scenario(), e.getMessage());
                this.lastFleetReport = fleet.report();
                this.exitCode = FAILURE_EXIT_CODE;
                return;
            }
        }

        // 창이 있으면 그 시작까지 유효 시각으로 기다린다(부록 A). 창을 이미 지났으면 보내지 않고 실패한다 —
        // 늦게 시작한 한 시간은 컷오프를 넘고, 넘은 주문은 다음 날 웨이브로 가서 창의 수를 조용히 바꾼다.
        LocalTime start = scenario.windowStart();
        @Nullable Instant opened = null;
        if (start != null) {
            try {
                opened = windowStart.await(start);
            } catch (IllegalStateException e) {
                log.error("시나리오 '{}' 를 시작하지 않았다 — {}", properties.scenario(), e.getMessage());
                this.exitCode = FAILURE_EXIT_CODE;
                return;
            }
        }

        // 수신을 주문보다 먼저 켠다. 뒤면 그 사이에 확정된 라우트를 놓치고,
        // 놓친 것은 "라우트가 안 왔다" 로 보여서 원인이 도구인지 스택인지 구별되지 않는다.
        // 창 시나리오는 출발을 그 앞에서 붙잡는다 — 받는 것과 떠나는 것을 뗀다(ADR-067 후속, runFleet 에서 놓는다).
        if (withDriver) {
            if (fleet != null) {
                driverScenario.holdDepartures();
            }
            driverScenario.open();
        }

        OrderGenerator generator = new OrderGenerator(scenario, randomFactory.create(scenario.seed()));
        // 취소는 자기 난수원을 쓴다 — 주문 생성기와 나누면 취소를 켜는 것이 주문 내용을 바꾼다.
        @Nullable OrderCancellations cancellations = scenario.cancel() == null ? null
                : cancellationsFactory.create(scenario.cancel(), randomFactory.create(scenario.seed() ^ CANCEL_SALT));
        ScenarioReport report =
                smoke.run(properties.scenario(), scenario, generator, runIds.next(), cancellations);
        this.lastReport = report;
        this.exitCode = report.isSuccess() ? 0 : FAILURE_EXIT_CODE;

        log.info("시나리오 '{}' 완료\n{}", properties.scenario(), report.toMarkdown());
        if (opened != null) {
            log.info("창: 유효 시각 {} – {} (시작 {} KST)", opened, windowStart.now(), start);
        }
        if (!report.isSuccess()) {
            log.error("주문 {}건 중 {}건만 접수되었다. 위 표의 code 별 건수를 보라.",
                    report.requested(), report.accepted());
        }

        if (fleet != null) {
            runFleet(fleet, dawnCutoffAfter(Objects.requireNonNull(opened, "fleet 은 창이 있다")), withDriver,
                    cancellations);
            return;
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

    /**
     * 함대 단계 (ADR-067): 증차 · 조기 마감 → 계획 · 시간 예산 → 재배정 → 기사 → 비활성화 → 감사 행 대조 → 리포트 머리.
     *
     * <p>더한 차량이 있으면 <strong>비활성화는 실패 뒤에도 돈다</strong> — 측정을 이어 가는 것이 아니라 정리다. 실패는 리포트와
     * 종료 코드에 그대로 남는다. 기사는 계획이 끝났을 때만 돈다(기다릴 수를 계획이 낸다) — 그리고 tracking 이 창의 라우트를
     * 반영한 뒤에 출발한다: 반영과 스캔은 운영에서 겹치지 않는다(ADR-067 후속). 그 기다림이 상한을 넘으면 실행의 실패다.
     */
    private void runFleet(PeakFleet.Session fleet, Instant cutoff, boolean withDriver,
            @Nullable OrderCancellations cancellations) throws InterruptedException {
        @Nullable Thread afterPublish = null;
        try {
            fleet.provision(cutoff);
            fleet.awaitPlans();
            if (fleet.planned() && cancellations != null) {
                // 발행 뒤 취소는 계획이 끝난 직후부터 — order.dispatched 가 order-service 에 반영되기 전의 창을 두드린다. 재배정 · 출발과
                // 나란히 돈다: 창의 한 끝(409)부터 기사가 이미 닿은 끝(cancel_too_late)까지 흩어지게 한다(7-0 A18 · B5).
                afterPublish = Thread.ofVirtual().name("cancel-after-publish").start(() -> {
                    try {
                        cancellations.sendAfterPublish();
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                });
            }
            if (fleet.planned()) {
                fleet.reassign();
            }
        } catch (FleetFailure e) {
            log.error("함대 단계 실패 — {}", e.getMessage());
        }
        if (withDriver && fleet.planned()) {
            DepartureGate.Result departure = driverScenario.departAfterApplied(fleet.waveIds(), fleet.routes());
            DriverReport driverReport = driverScenario.awaitAndReport(properties.scenario(), fleet.waveIds(),
                    fleet.routes());
            this.lastDriverReport = driverReport;
            log.info("기사 시뮬레이션 완료\n{}{}", driverReport.toMarkdown(), departure.toMarkdown());
            if (!driverReport.isSuccess() || !departure.inTime()) {
                this.exitCode = FAILURE_EXIT_CODE;
            }
        }
        if (cancellations != null) {
            if (afterPublish != null) {
                afterPublish.join();
            }
            CancelReport cancelReport = cancellations.report();
            this.lastCancelReport = cancelReport;
            log.info("취소 — 때와 응답별 (7-4 turbulent)\n{}", cancelReport.toMarkdown());
        }
        if (fleet.addedCount() > 0) {
            try {
                fleet.release();
            } catch (FleetFailure e) {
                log.error("함대 정리 실패 — {}", e.getMessage());
            }
        }
        try {
            fleet.verifyAudit();
        } catch (FleetFailure e) {
            log.error("감사 행 대조 실패 — {}", e.getMessage());
        }
        FleetReport report = fleet.report();
        this.lastFleetReport = report;
        log.info("함대 — 계산값과 실측 (ADR-067 결정 8)\n{}", report.toMarkdown());
        if (!report.isSuccess()) {
            this.exitCode = FAILURE_EXIT_CODE;
        }
    }

    /** 창 시작 뒤의 첫 DAWN 컷오프(00:00 KST) — {@code TierSchedule} 의 상수이고 창은 그 앞의 한 시간이다(부록 A). */
    static Instant dawnCutoffAfter(Instant windowOpened) {
        return windowOpened.atZone(WindowStart.ZONE).toLocalDate().plusDays(1).atStartOfDay(WindowStart.ZONE)
                .toInstant();
    }

    @Override
    public int getExitCode() {
        return exitCode;
    }

    /** 마지막 실행 결과. 테스트가 본다. */
    public @Nullable ScenarioReport lastReport() {
        return lastReport;
    }

    /** 마지막 함대 리포트. 함대 단계가 없는 시나리오면 {@code null}. */
    public @Nullable FleetReport lastFleetReport() {
        return lastFleetReport;
    }

    /** 마지막 취소 결과. 취소가 없는 시나리오면 {@code null}. */
    public @Nullable CancelReport lastCancelReport() {
        return lastCancelReport;
    }

    /** 마지막 기사 시뮬레이션 결과. 기사를 쓰지 않는 시나리오면 {@code null}. */
    public @Nullable DriverReport lastDriverReport() {
        return lastDriverReport;
    }

    /** 취소의 난수원을 주문 생성기의 것과 가르는 값 — 같은 seed 에서 다른 흐름을 뽑는다. */
    static final long CANCEL_SALT = 0x43414E43454CL;

    /** 취소를 만든다 — 클라이언트 · 페이싱은 배선이 준다. */
    @FunctionalInterface
    public interface CancellationsFactory {

        /**
         * @param cancel 비율 · 몫 · 속도
         * @param random 취소만의 난수원
         */
        OrderCancellations create(SimProperties.Scenario.Cancel cancel, RandomGenerator random);
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
