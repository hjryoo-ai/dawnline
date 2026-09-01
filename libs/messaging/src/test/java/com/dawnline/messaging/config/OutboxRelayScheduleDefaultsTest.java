package com.dawnline.messaging.config;

import static org.assertj.core.api.Assertions.assertThat;

import com.dawnline.messaging.outbox.OutboxRelay;
import java.lang.reflect.Method;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;
import org.springframework.scheduling.annotation.Scheduled;

/**
 * {@code @Scheduled} 의 플레이스홀더 기본값과 {@link DawnlineMessagingProperties} 의 기본값이 같은지 본다.
 *
 * <p>같은 숫자가 두 곳에 있다. 어노테이션 속성은 컴파일 타임 상수여야 해서 설정 record 를 참조할 수 없고,
 * 설정 record 는 IDE 자동완성·문서화를 위해 필요하다. 둘이 어긋나면 "설정을 바꿨는데 안 먹는" 종류의
 * 버그가 되는데, 그건 아주 늦게 발견된다. 이 테스트가 그 어긋남을 컴파일 직후에 잡는다.
 */
class OutboxRelayScheduleDefaultsTest {

    /** {@code ${prop.name:default}} 에서 default 를 뽑는다. */
    private static final Pattern PLACEHOLDER = Pattern.compile("^\\$\\{[^:}]+:([^}]+)}$");

    @Test
    void poll_기본_폴링간격이_설정_기본값과_같다() {
        assertThat(fixedDelayDefault("poll")).isEqualTo(defaults().outbox().pollIntervalMs());
    }

    @Test
    void refreshMetrics_기본_간격이_설정_기본값과_같다() {
        assertThat(fixedDelayDefault("refreshMetrics")).isEqualTo(defaults().outbox().metricsIntervalMs());
    }

    @Test
    void cleanupPublished_기본_간격이_설정_기본값과_같다() {
        assertThat(fixedDelayDefault("cleanupPublished")).isEqualTo(defaults().outbox().cleanupIntervalMs());
    }

    @Test
    void 모든_스케줄_메서드가_속성_플레이스홀더를_쓴다() {
        // 상수를 직접 박아 두면 운영 중에 값을 못 바꾼다.
        for (String method : new String[] {"poll", "refreshMetrics", "cleanupPublished"}) {
            Scheduled scheduled = scheduled(method);
            assertThat(scheduled.fixedDelayString()).as(method).startsWith("${");
            assertThat(scheduled.initialDelayString()).as(method).startsWith("${");
        }
    }

    private static long fixedDelayDefault(String methodName) {
        Matcher matcher = PLACEHOLDER.matcher(scheduled(methodName).fixedDelayString());
        assertThat(matcher.matches()).as("%s 의 fixedDelayString 에 기본값이 없습니다", methodName).isTrue();
        return Long.parseLong(matcher.group(1));
    }

    private static Scheduled scheduled(String methodName) {
        try {
            Method method = OutboxRelay.class.getMethod(methodName);
            Scheduled annotation = method.getAnnotation(Scheduled.class);
            assertThat(annotation).as("%s 에 @Scheduled 가 없습니다", methodName).isNotNull();
            return annotation;
        } catch (NoSuchMethodException e) {
            throw new AssertionError("OutboxRelay 에 " + methodName + " 메서드가 없습니다", e);
        }
    }

    private static DawnlineMessagingProperties defaults() {
        return new Binder(new MapConfigurationPropertySource(Map.of()))
                .bindOrCreate("dawnline.messaging", DawnlineMessagingProperties.class);
    }
}
