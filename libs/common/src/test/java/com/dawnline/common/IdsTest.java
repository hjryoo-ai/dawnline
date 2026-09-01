package com.dawnline.common;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.stream.IntStream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("Ids — RFC 9562 UUIDv7")
class IdsTest {

    private static final Instant FIXED = Instant.parse("2026-08-29T00:00:00Z");

    private static Ids deterministicIds(Clock clock) {
        return new Ids(clock, new Random(42L));
    }

    @Test
    void newUuid_생성하면_버전은_7이고_변이는_RFC_9562_다() {
        Ids ids = deterministicIds(Clock.fixed(FIXED, ZoneOffset.UTC));

        UUID uuid = ids.newUuid();

        assertThat(uuid.version()).isEqualTo(7);
        assertThat(uuid.variant()).isEqualTo(2); // 0b10
        // msb 의 48..51 비트가 0111 인지 직접 확인
        assertThat((uuid.getMostSignificantBits() >>> 12) & 0xFL).isEqualTo(7L);
        // lsb 의 최상위 2비트가 10 인지 직접 확인
        assertThat((uuid.getLeastSignificantBits() >>> 62) & 0x3L).isEqualTo(2L);
    }

    @Test
    void newUuid_상위_48비트는_주입된_Clock_의_밀리초다() {
        Ids ids = deterministicIds(Clock.fixed(FIXED, ZoneOffset.UTC));

        UUID uuid = ids.newUuid();

        assertThat(Ids.timestampOf(uuid)).isEqualTo(FIXED);
        assertThat(uuid.getMostSignificantBits() >>> 16).isEqualTo(FIXED.toEpochMilli());
    }

    @Test
    void newUuid_같은_밀리초에_1000개를_만들어도_중복이_없고_단조_증가한다() {
        Ids ids = deterministicIds(Clock.fixed(FIXED, ZoneOffset.UTC));

        List<UUID> generated = IntStream.range(0, 1000)
                .mapToObj(i -> ids.newUuid())
                .toList();

        assertThat(Set.copyOf(generated)).hasSize(1000);
        assertThat(generated).isSortedAccordingTo(UUID::compareTo);
        for (int i = 1; i < generated.size(); i++) {
            assertThat(generated.get(i).getMostSignificantBits())
                    .isGreaterThan(generated.get(i - 1).getMostSignificantBits());
        }
    }

    @Test
    void newUuid_카운터가_소진되면_1ms_를_빌려서라도_단조성을_유지한다() {
        // rand_a 는 12비트(최대 4096개). 한 밀리초에 그 이상을 요구해도 순서가 깨지지 않아야 한다.
        Ids ids = deterministicIds(Clock.fixed(FIXED, ZoneOffset.UTC));

        List<UUID> generated = IntStream.range(0, 10_000)
                .mapToObj(i -> ids.newUuid())
                .toList();

        assertThat(Set.copyOf(generated)).hasSize(10_000);
        assertThat(generated).isSortedAccordingTo(UUID::compareTo);
        // 카운터를 넘겼으므로 마지막 UUID 의 타임스탬프는 고정 시계보다 앞서 있어야 한다.
        assertThat(Ids.timestampOf(generated.get(generated.size() - 1))).isAfter(FIXED);
        assertThat(Ids.timestampOf(generated.get(0))).isEqualTo(FIXED);
    }

    @Test
    void newUuid_시간이_흐르면_타임스탬프와_정렬_순서가_함께_증가한다() {
        MutableClock clock = new MutableClock(FIXED);
        Ids ids = deterministicIds(clock);

        List<UUID> generated = new ArrayList<>();
        for (int i = 0; i < 50; i++) {
            generated.add(ids.newUuid());
            clock.advanceMillis(7L);
        }

        assertThat(generated).isSortedAccordingTo(UUID::compareTo);
        assertThat(Ids.timestampOf(generated.get(49)))
                .isEqualTo(FIXED.plusMillis(49 * 7L));
    }

    @Test
    void newUuid_시계가_뒤로_가도_단조성이_깨지지_않는다() {
        MutableClock clock = new MutableClock(FIXED);
        Ids ids = deterministicIds(clock);

        UUID before = ids.newUuid();
        clock.setInstant(FIXED.minusSeconds(60)); // NTP 보정으로 시계가 역행한 상황
        UUID after = ids.newUuid();

        assertThat(after).isGreaterThan(before);
        assertThat(Ids.timestampOf(after)).isEqualTo(FIXED);
    }

    @Test
    void newUuid_문자열_표현의_사전순이_시간순과_일치한다() {
        MutableClock clock = new MutableClock(FIXED);
        Ids ids = deterministicIds(clock);

        List<String> texts = new ArrayList<>();
        for (int i = 0; i < 100; i++) {
            texts.add(ids.newUuid().toString());
            clock.advanceMillis(3L);
        }

        assertThat(texts).isSorted();
    }

    @Test
    void newUuid_같은_seed_와_같은_Clock_이면_결과가_완전히_동일하다() {
        Ids first = deterministicIds(Clock.fixed(FIXED, ZoneOffset.UTC));
        Ids second = deterministicIds(Clock.fixed(FIXED, ZoneOffset.UTC));

        List<UUID> firstRun = IntStream.range(0, 200).mapToObj(i -> first.newUuid()).toList();
        List<UUID> secondRun = IntStream.range(0, 200).mapToObj(i -> second.newUuid()).toList();

        assertThat(secondRun).isEqualTo(firstRun);
    }

    @Test
    void newUuid_여러_스레드에서_동시에_불러도_중복이_없다() throws InterruptedException {
        Ids ids = deterministicIds(Clock.fixed(FIXED, ZoneOffset.UTC));
        int threads = 8;
        int perThread = 500;
        Set<UUID> collected = ConcurrentHashMap.newKeySet();
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(threads);

        for (int t = 0; t < threads; t++) {
            Thread.ofVirtual().start(() -> {
                try {
                    start.await();
                    for (int i = 0; i < perThread; i++) {
                        collected.add(ids.newUuid());
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    done.countDown();
                }
            });
        }
        start.countDown();

        assertThat(done.await(30, TimeUnit.SECONDS)).isTrue();
        assertThat(collected).hasSize(threads * perThread);
    }

    @Test
    void newId_시스템_시계로_UUIDv7_을_만든다() {
        Instant before = Instant.now();

        UUID uuid = Ids.newId();

        assertThat(uuid.version()).isEqualTo(7);
        assertThat(uuid.variant()).isEqualTo(2);
        assertThat(Ids.timestampOf(uuid)).isBetween(before.minusSeconds(5), Instant.now().plusSeconds(5));
        assertThat(Ids.newId()).isNotEqualTo(uuid);
    }

    @Test
    void timestampOf_UUIDv7_이_아니면_거부한다() {
        UUID v4 = UUID.fromString("f81d4fae-7dec-41d0-a765-00a0c91e6bf6");

        assertThatThrownBy(() -> Ids.timestampOf(v4))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("version=4");
    }

    @Test
    void 생성자_는_Clock_과_RandomGenerator_를_반드시_받는다() {
        assertThatThrownBy(() -> new Ids(null, new Random(1L)))
                .isInstanceOf(NullPointerException.class)
                .hasMessage("clock");
        assertThatThrownBy(() -> new Ids(Clock.systemUTC(), null))
                .isInstanceOf(NullPointerException.class)
                .hasMessage("random");
    }
}
