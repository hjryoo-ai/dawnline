package com.dawnline.messaging.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 시각의 저장 정밀도 (CLAUDE.md 불변규칙 9·12).
 *
 * <p>PostgreSQL {@code TIMESTAMPTZ} 는 마이크로초까지만 담는다. 그보다 정밀한 값을 도메인이 들고
 * 있으면 <strong>저장 전과 후가 다른 값</strong>이 되고, 그 차이는 응답과 재조회, 멱등 재생 사이에서
 * 드러난다.
 *
 * <p>이 테스트가 있는 이유: {@code Clock.systemUTC()} 의 해상도는 플랫폼마다 다르다. macOS 는
 * 마이크로초에서 끊기고 Linux 는 나노초까지 준다. 그래서 이 결함은 <em>개발 기계에서는 보이지 않고
 * CI 에서만 터진다</em> — 실제로 그렇게 발견됐다. 여기서는 플랫폼과 무관하게 검사한다.
 */
@DisplayName("dawnlineClock — 저장 정밀도(마이크로초)로 잘린 시계")
class MessagingClockTest {

    private final Clock clock = new MessagingAutoConfiguration().dawnlineClock();

    @Test
    void 나노초를_주는_시계도_마이크로초로_잘린다() {
        // CI 에서 실제로 깨졌던 값이다. TIMESTAMPTZ 왕복 후에는 ...754Z 로 돌아온다.
        Clock nanoPrecision = Clock.fixed(Instant.parse("2026-09-03T10:25:07.576754234Z"), ZoneOffset.UTC);

        Instant truncated = Clock.tick(nanoPrecision, Duration.ofNanos(1_000)).instant();

        assertThat(truncated).isEqualTo(Instant.parse("2026-09-03T10:25:07.576754Z"));
    }

    @Test
    void 시계가_주는_값에는_마이크로초_아래가_없다() {
        for (int i = 0; i < 10_000; i++) {
            assertThat(clock.instant().getNano() % 1_000)
                    .as("마이크로초 아래 자리가 남아 있으면 TIMESTAMPTZ 왕복에서 잘린다")
                    .isZero();
        }
    }

    @Test
    void 잘라도_시계는_계속_흐른다() {
        // 상수 시계로 만들어 버리면 접수 시각이 전부 같아진다.
        Instant first = clock.instant();
        Instant later = Clock.offset(clock, Duration.ofMillis(1)).instant();

        assertThat(later).isAfter(first);
        assertThat(clock.getZone()).isEqualTo(ZoneOffset.UTC);
    }

    @Test
    void 폴백_시계도_같은_정밀도다() {
        // ObjectProvider 폴백이 systemUTC 면 빈이 없는 구성에서 나노초가 다시 들어온다.
        assertThat(MessagingAutoConfiguration.storagePrecisionClock().instant().getNano() % 1_000).isZero();
    }
}
