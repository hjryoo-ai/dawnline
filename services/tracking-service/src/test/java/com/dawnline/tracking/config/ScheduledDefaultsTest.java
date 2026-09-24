package com.dawnline.tracking.config;

import static org.assertj.core.api.Assertions.assertThat;

import com.dawnline.tracking.application.ShipmentEventPartitions;
import com.dawnline.tracking.application.TrackingRetentionCleaner;
import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;
import org.springframework.scheduling.annotation.Scheduled;

/**
 * {@code @Scheduled} 의 플레이스홀더 기본값과 {@link TrackingProperties} 의 기본값이 같은지 본다.
 *
 * <p>{@code libs/messaging} 의 같은 이름 테스트와 같은 형태다. 어노테이션 속성은 컴파일 타임
 * 상수여야 해서 설정 record 를 참조할 수 없고, 설정 record 는 IDE 자동완성·문서화에 필요하다.
 * 둘이 어긋나면 "설정을 바꿨는데 안 먹는" 종류의 버그가 되고, 그건 아주 늦게 발견된다.
 *
 * <p>표를 <strong>드는</strong> 방식으로 적되, 표에 없는 {@code @Scheduled} 메서드가 생기면
 * 마지막 테스트가 먼저 깨진다 — 새 스케줄이 조용히 검사 밖에 남지 않게 하는 장치다(CLAUDE.md).
 */
@DisplayName("@Scheduled 기본값 ↔ 설정 기본값 (tracking-service)")
class ScheduledDefaultsTest {

    /** {@code ${prop.name:default}} 에서 default 를 뽑는다. */
    private static final Pattern PLACEHOLDER = Pattern.compile("^\\$\\{[^:}]+:([^}]+)}$");

    /** {@code @Scheduled} 를 가진 클래스 전부. */
    private static final Class<?>[] OWNERS = {ShipmentEventPartitions.class, TrackingRetentionCleaner.class};

    private record Schedule(Class<?> owner, String method, Function<TrackingProperties, Long> interval,
            Function<TrackingProperties, Long> initialDelay) {

        @Override
        public String toString() {
            return owner.getSimpleName() + "." + method;
        }
    }

    private static final List<Schedule> SCHEDULES = List.of(
            new Schedule(ShipmentEventPartitions.class, "maintain",
                    p -> p.partitions().intervalMs(), p -> p.partitions().initialDelayMs()),
            new Schedule(TrackingRetentionCleaner.class, "cleanupExpired",
                    p -> p.retention().cleanupIntervalMs(), p -> p.retention().cleanupInitialDelayMs()));

    private static List<Schedule> schedules() {
        return SCHEDULES;
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("schedules")
    void 스케줄의_기본값이_설정_기본값과_같다(Schedule schedule) {
        Scheduled scheduled = scheduled(schedule);
        assertThat(defaultOf(scheduled.fixedDelayString(), schedule, "fixedDelayString"))
                .isEqualTo(schedule.interval().apply(defaults()));
        assertThat(defaultOf(scheduled.initialDelayString(), schedule, "initialDelayString"))
                .isEqualTo(schedule.initialDelay().apply(defaults()));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("schedules")
    void 스케줄이_상수가_아니라_속성_플레이스홀더를_쓴다(Schedule schedule) {
        // 상수를 직접 박아 두면 운영 중에 값을 못 바꾼다.
        Scheduled scheduled = scheduled(schedule);
        assertThat(scheduled.fixedDelayString()).startsWith("${");
        assertThat(scheduled.initialDelayString()).startsWith("${");
    }

    @Test
    void 표에_없는_스케줄_메서드가_없다() {
        List<String> declared = Arrays.stream(OWNERS)
                .flatMap(owner -> Arrays.stream(owner.getMethods())
                        .filter(method -> method.isAnnotationPresent(Scheduled.class))
                        .map(method -> owner.getSimpleName() + "." + method.getName()))
                .sorted()
                .toList();

        assertThat(declared)
                .as("새 @Scheduled 메서드가 생기면 SCHEDULES 표에 추가한다")
                .isEqualTo(SCHEDULES.stream().map(Schedule::toString).sorted().toList());
    }

    private static long defaultOf(String placeholder, Schedule schedule, String attribute) {
        Matcher matcher = PLACEHOLDER.matcher(placeholder);
        assertThat(matcher.matches()).as("%s 의 %s 에 기본값이 없습니다", schedule, attribute).isTrue();
        return Long.parseLong(matcher.group(1));
    }

    private static Scheduled scheduled(Schedule schedule) {
        try {
            Method method = schedule.owner().getMethod(schedule.method());
            Scheduled annotation = method.getAnnotation(Scheduled.class);
            assertThat(annotation).as("%s 에 @Scheduled 가 없습니다", schedule).isNotNull();
            return annotation;
        } catch (NoSuchMethodException e) {
            throw new AssertionError(schedule + " 메서드가 없습니다", e);
        }
    }

    private static TrackingProperties defaults() {
        return new Binder(new MapConfigurationPropertySource(Map.of()))
                .bindOrCreate("dawnline.tracking", TrackingProperties.class);
    }
}
