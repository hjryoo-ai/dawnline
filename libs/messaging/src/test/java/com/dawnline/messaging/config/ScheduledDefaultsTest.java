package com.dawnline.messaging.config;

import static org.assertj.core.api.Assertions.assertThat;

import com.dawnline.messaging.idempotency.ProcessedEventCleaner;
import com.dawnline.messaging.outbox.OutboxRelay;
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
 * {@code @Scheduled} 의 플레이스홀더 기본값과 {@link DawnlineMessagingProperties} 의 기본값이 같은지 본다.
 *
 * <p>같은 숫자가 두 곳에 있다. 어노테이션 속성은 컴파일 타임 상수여야 해서 설정 record 를 참조할 수 없고,
 * 설정 record 는 IDE 자동완성·문서화를 위해 필요하다. 둘이 어긋나면 "설정을 바꿨는데 안 먹는" 종류의
 * 버그가 되는데, 그건 아주 늦게 발견된다. 이 테스트가 그 어긋남을 컴파일 직후에 잡는다.
 *
 * <p>스케줄러가 늘어나면 {@link #SCHEDULES} 에 줄을 더한다. 빠뜨리는 것을 막기 위해
 * {@link #표에_없는_스케줄_메서드가_없다()} 가 두 클래스의 {@code @Scheduled} 메서드를 전부 훑어
 * 표와 대조한다 — 표를 갱신하지 않으면 그 테스트가 먼저 깨진다.
 */
@DisplayName("@Scheduled 기본값 ↔ 설정 기본값 (libs/messaging 전체)")
class ScheduledDefaultsTest {

    /** {@code ${prop.name:default}} 에서 default 를 뽑는다. */
    private static final Pattern PLACEHOLDER = Pattern.compile("^\\$\\{[^:}]+:([^}]+)}$");

    /**
     * 검사 대상 스케줄 하나.
     *
     * @param owner    스케줄 메서드를 가진 클래스
     * @param method   메서드 이름
     * @param expected 설정 기본값에서 같은 값을 꺼내는 함수
     */
    private record Schedule(Class<?> owner, String method, Function<DawnlineMessagingProperties, Long> expected) {

        @Override
        public String toString() {
            return owner.getSimpleName() + "." + method;
        }
    }

    private static final List<Schedule> SCHEDULES = List.of(
            new Schedule(OutboxRelay.class, "poll", p -> p.outbox().pollIntervalMs()),
            new Schedule(OutboxRelay.class, "refreshMetrics", p -> p.outbox().metricsIntervalMs()),
            new Schedule(OutboxRelay.class, "cleanupPublished", p -> p.outbox().cleanupIntervalMs()),
            new Schedule(ProcessedEventCleaner.class, "cleanupExpired",
                    p -> p.processedEvents().cleanupIntervalMs()));

    private static List<Schedule> schedules() {
        return SCHEDULES;
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("schedules")
    void 스케줄의_기본_간격이_설정_기본값과_같다(Schedule schedule) {
        assertThat(fixedDelayDefault(schedule)).isEqualTo(schedule.expected().apply(defaults()));
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
        List<String> declared = Arrays.stream(new Class<?>[] {OutboxRelay.class, ProcessedEventCleaner.class})
                .flatMap(owner -> Arrays.stream(owner.getMethods())
                        .filter(method -> method.isAnnotationPresent(Scheduled.class))
                        .map(method -> owner.getSimpleName() + "." + method.getName()))
                .sorted()
                .toList();
        List<String> covered = SCHEDULES.stream().map(Schedule::toString).sorted().toList();

        assertThat(declared)
                .as("새 @Scheduled 메서드가 생기면 SCHEDULES 표에 추가한다")
                .isEqualTo(covered);
    }

    private static long fixedDelayDefault(Schedule schedule) {
        Matcher matcher = PLACEHOLDER.matcher(scheduled(schedule).fixedDelayString());
        assertThat(matcher.matches()).as("%s 의 fixedDelayString 에 기본값이 없습니다", schedule).isTrue();
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

    private static DawnlineMessagingProperties defaults() {
        return new Binder(new MapConfigurationPropertySource(Map.of()))
                .bindOrCreate("dawnline.messaging", DawnlineMessagingProperties.class);
    }
}
