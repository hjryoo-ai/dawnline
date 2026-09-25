package com.dawnline.ops.config;

import static org.assertj.core.api.Assertions.assertThat;

import com.dawnline.ops.application.KpiGauges;
import com.dawnline.ops.application.ReadModelRetentionCleaner;
import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;
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
 * {@code @Scheduled} 의 플레이스홀더 기본값과 설정 레코드의 기본값이 같은지 본다 (다른 서비스의 같은 이름 테스트와
 * 같은 형태).
 *
 * <p>어노테이션 속성은 컴파일 타임 상수여야 해서 설정 레코드를 참조할 수 없고, 설정 레코드는 IDE 자동완성·문서화에
 * 필요하다. 둘이 어긋나면 「설정을 바꿨는데 안 먹는」 종류의 버그가 된다.
 *
 * <p>대상은 이 서비스의 {@code @Scheduled} 전부에서 <strong>뺀다</strong> — 레코드가 없는 스케줄은
 * {@link #WITHOUT_RECORD} 에 이유와 함께 둔다(CLAUDE.md 「집합을 도는 검사는 빼는 방식」).
 */
@DisplayName("@Scheduled 기본값 ↔ 설정 기본값 (ops-api)")
class ScheduledDefaultsTest {

    /** {@code ${prop.name:default}} 에서 default 를 뽑는다. */
    private static final Pattern PLACEHOLDER = Pattern.compile("^\\$\\{[^:}]+:([^}]+)}$");

    /** {@code @Scheduled} 를 가진 클래스 전부. */
    private static final Class<?>[] OWNERS = {ReadModelRetentionCleaner.class, KpiGauges.class};

    /** 설정 레코드가 없는 스케줄과 그 이유. */
    private static final Map<String, String> WITHOUT_RECORD = Map.of(
            "KpiGauges.refresh",
            "KPI 갱신 주기는 레코드 없이 플레이스홀더만 있다(Phase 6) — 대조할 둘째 자리가 없으니 어긋날 수도 없다");

    private record Schedule(Class<?> owner, String method, Function<OpsRetentionProperties, Long> interval,
            Function<OpsRetentionProperties, Long> initialDelay) {

        @Override
        public String toString() {
            return owner.getSimpleName() + "." + method;
        }
    }

    private static final List<Schedule> SCHEDULES = List.of(
            new Schedule(ReadModelRetentionCleaner.class, "cleanupExpired",
                    OpsRetentionProperties::cleanupIntervalMs, OpsRetentionProperties::cleanupInitialDelayMs));

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

    @Test
    void 표에_없는_스케줄_메서드는_레코드가_없다는_이유를_갖는다() {
        TreeSet<String> declared = new TreeSet<>();
        Arrays.stream(OWNERS).forEach(owner -> Arrays.stream(owner.getMethods())
                .filter(method -> method.isAnnotationPresent(Scheduled.class))
                .forEach(method -> declared.add(owner.getSimpleName() + "." + method.getName())));
        SCHEDULES.forEach(schedule -> declared.remove(schedule.toString()));

        assertThat(declared)
                .as("새 @Scheduled 메서드가 생기면 SCHEDULES 에 넣거나 WITHOUT_RECORD 에 이유를 적는다")
                .containsExactlyInAnyOrderElementsOf(WITHOUT_RECORD.keySet());
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

    private static OpsRetentionProperties defaults() {
        return new Binder(new MapConfigurationPropertySource(Map.of()))
                .bindOrCreate("dawnline.ops.retention", OpsRetentionProperties.class);
    }
}
