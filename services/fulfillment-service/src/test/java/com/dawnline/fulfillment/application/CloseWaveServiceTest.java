package com.dawnline.fulfillment.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.dawnline.common.GeoPoint;
import com.dawnline.common.Ids;
import com.dawnline.common.error.DomainException;
import com.dawnline.common.error.NotFoundException;
import com.dawnline.common.error.ValidationException;
import com.dawnline.fulfillment.application.port.in.CloseWaveUseCase;
import com.dawnline.fulfillment.application.port.in.WaveView;
import com.dawnline.fulfillment.domain.Camp;
import com.dawnline.fulfillment.domain.ServiceTier;
import com.dawnline.fulfillment.domain.Wave;
import com.dawnline.fulfillment.domain.WaveCloseCause;
import com.dawnline.fulfillment.domain.WaveStatus;
import com.dawnline.observability.DawnlineMetrics;
import com.dawnline.observability.MdcKeys;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.DisplayNameGeneration;
import org.junit.jupiter.api.DisplayNameGenerator;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.TransactionSystemException;
import org.springframework.transaction.support.SimpleTransactionStatus;

/**
 * 운영자 조기 마감 (§5.2, ADR-054).
 *
 * <p>본문({@link WaveClosing})은 {@code WaveClosingTest} 가 본다. 여기서 보는 것은 그 위의 판단이다 — 이유가
 * 필수인가, 409 가 무엇을 말하는가, 게이지를 커밋 뒤에 올리는가.
 */
@DisplayNameGeneration(DisplayNameGenerator.ReplaceUnderscores.class)
@DisplayName("CloseWaveService — 운영자가 컷오프를 앞당긴다")
class CloseWaveServiceTest {

    private static final Instant CUTOFF = Instant.parse("2026-09-24T01:00:00Z");
    private static final Instant NOW = CUTOFF.minusSeconds(1800);
    private static final UUID CAMP_ID = Ids.newId();

    private final InMemoryFulfillmentRepositories repositories = new InMemoryFulfillmentRepositories();
    private final RecordingWaveEvents events = new RecordingWaveEvents();
    private final SimpleMeterRegistry registry = new SimpleMeterRegistry();
    private final Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
    private final WaveClosing closing = new WaveClosing(repositories.waveRepository(),
            repositories.orderRepository(), events, repositories.referenceData(), clock);

    @BeforeEach
    void 캠프를_등록한다() {
        repositories.addCamp(new Camp(CAMP_ID, "CAMP-TEST", Ids.newId(), GeoPoint.of(37.5663, 126.9779), true));
    }

    private CloseWaveUseCase service(PlatformTransactionManager transactions) {
        return new CloseWaveService(closing, transactions, new FulfillmentMetrics(registry));
    }

    private CloseWaveUseCase service() {
        return service(new Transactions(false));
    }

    private Wave openWave() {
        Wave wave = Wave.open(Ids.newId(), CAMP_ID, ServiceTier.DAWN, CUTOFF);
        repositories.waveRepository().insertIfAbsent(wave);
        return wave;
    }

    @Test
    void 컷오프_전에도_닫고_원인은_MANUAL_이다() {
        Wave wave = openWave();

        WaveView view = service().close(wave.id(), "물량 조기 소진 — 기사 출발 앞당김");

        assertThat(view.status()).isEqualTo(WaveStatus.CLOSED);
        assertThat(view.closeCause()).isEqualTo(WaveCloseCause.MANUAL);
        assertThat(view.closedAt()).as("컷오프보다 앞이다 — 이 커맨드의 뜻").isBefore(CUTOFF);
        assertThat(events.closed).extracting(Wave::id).containsExactly(wave.id());
    }

    @Test
    void 이유가_비었거나_너무_길면_아무것도_하지_않는다() {
        Wave wave = openWave();

        for (String reason : new String[] {"", "   ", "가".repeat(CloseWaveUseCase.MAX_REASON_LENGTH + 1)}) {
            assertThatThrownBy(() -> service().close(wave.id(), reason)).isInstanceOf(ValidationException.class);
        }
        assertThat(repositories.waveRepository().findById(wave.id()).orElseThrow().status())
                .isEqualTo(WaveStatus.OPEN);
        assertThat(events.closed).isEmpty();
    }

    @Test
    void 이유는_200자까지_받는다() {
        // 경계의 안쪽 — 위 테스트만 있으면 「전부 거절」도 통과한다.
        Wave wave = openWave();

        assertThat(service().close(wave.id(), "가".repeat(CloseWaveUseCase.MAX_REASON_LENGTH)).status())
                .isEqualTo(WaveStatus.CLOSED);
    }

    @Test
    void 없는_웨이브는_404_다() {
        assertThatThrownBy(() -> service().close(Ids.newId(), "r")).isInstanceOf(NotFoundException.class);
    }

    @Test
    void 다시_누르면_409_wave_not_open_이고_누가_닫았는지_말한다() {
        // 감사 UNKNOWN 을 닫는 근거다 — closeCause=MANUAL 이면 앞의 요청이 적용됐다(ADR-054 결정 6).
        Wave wave = openWave();
        service().close(wave.id(), "첫 요청");

        assertThatThrownBy(() -> service().close(wave.id(), "응답을 못 받아 다시 누름"))
                .isInstanceOfSatisfying(DomainException.class, e -> {
                    assertThat(e.code()).isEqualTo("wave-not-open");
                    assertThat(e.status()).isEqualTo(409);
                    assertThat(e.details()).containsEntry("currentState", "CLOSED")
                            .containsEntry("closeCause", "MANUAL")
                            .containsEntry("closedAt", NOW.toString());
                });
        assertThat(events.closed).as("두 번째는 적용되지 않는다").hasSize(1);
    }

    @Test
    void 스케줄러가_먼저_닫았으면_409_가_SCHEDULED_를_말한다() {
        Wave wave = openWave();
        closing.closeIfOpen(wave.id(), WaveCloseCause.SCHEDULED);

        assertThatThrownBy(() -> service().close(wave.id(), "r"))
                .isInstanceOfSatisfying(DomainException.class, e ->
                        assertThat(e.details()).containsEntry("closeCause", "SCHEDULED"));
    }

    @Test
    void 게이지는_커밋_뒤에_올린다() {
        Wave wave = openWave();

        service().close(wave.id(), "r");

        assertThat(registry.get(DawnlineMetrics.WAVE_ORDERS.meterName())
                .tag("camp", "CAMP-TEST").tag("tier", "DAWN").gauge().value()).isZero();
    }

    @Test
    void 커밋에_실패하면_게이지를_올리지_않는다() {
        // CLAUDE.md 「카운터는 커밋 뒤에 센다」 — 순서는 읽어서 보이지 않으므로 테스트가 본다.
        Wave wave = openWave();

        assertThatThrownBy(() -> service(new Transactions(true)).close(wave.id(), "r"))
                .isInstanceOf(TransactionSystemException.class);
        assertThat(registry.find(DawnlineMetrics.WAVE_ORDERS.meterName()).gauge()).isNull();
    }

    @Test
    void 마감하는_동안_MDC_에_waveId_가_있고_끝나면_지운다() {
        Wave wave = openWave();

        service().close(wave.id(), "r");

        assertThat(events.mdcWaveIds).containsExactly(wave.id().toString());
        assertThat(MDC.get(MdcKeys.WAVE_ID)).isNull();
    }

    /** 흐름만 본다. 커밋을 실패시킬 수 있다. */
    private record Transactions(boolean failCommit) implements PlatformTransactionManager {

        @Override
        public TransactionStatus getTransaction(TransactionDefinition definition) {
            return new SimpleTransactionStatus();
        }

        @Override
        public void commit(TransactionStatus status) {
            if (failCommit) {
                throw new TransactionSystemException("커밋 실패");
            }
        }

        @Override
        public void rollback(TransactionStatus status) {
        }
    }
}
