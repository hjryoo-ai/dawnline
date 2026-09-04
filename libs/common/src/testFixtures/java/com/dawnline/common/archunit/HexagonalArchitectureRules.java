package com.dawnline.common.archunit;

import com.tngtech.archunit.base.DescribedPredicate;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.lang.ArchRule;
import com.tngtech.archunit.lang.conditions.ArchPredicates;
import com.tngtech.archunit.lang.syntax.ArchRuleDefinition;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 모든 서비스가 공유하는 헥사고날 아키텍처 ArchUnit 규칙 (DESIGN.md §13, CLAUDE.md 불변규칙 3·5·6).
 *
 * <p>각 서비스는 자기 모듈의 {@code ArchitectureTest} 에서 이 규칙들을 그대로 재사용한다.
 *
 * <pre>{@code
 * class OrderArchitectureTest {
 *     private static final JavaClasses CLASSES =
 *             new ClassFileImporter().importPackages(HexagonalArchitectureRules.packageOf("order"));
 *
 *     @Test
 *     void 헥사고날_규칙을_모두_지킨다() {
 *         HexagonalArchitectureRules.allRulesFor("order").forEach(rule -> rule.check(CLASSES));
 *     }
 * }
 * }</pre>
 *
 * <h2>빈 결과 허용({@code allowEmptyShould})</h2>
 * <p>모든 규칙에 {@code allowEmptyShould(true)} 를 준다. Phase 0 의 서비스 골격에는 아직
 * {@code @KafkaListener} 나 {@code @Transactional} 이 하나도 없는데, ArchUnit 1.x 의 기본값
 * ({@code archRule.failOnEmptyShould=true})은 "검사 대상이 0개"인 경우를 실패로 처리하기 때문이다.
 * 규칙의 어설션 자체는 전혀 약화되지 않는다 — 대상이 생기는 순간 그대로 검사된다.
 */
public final class HexagonalArchitectureRules {

    /** 모든 서비스 코드의 루트 패키지. */
    public static final String BASE_PACKAGE = "com.dawnline";

    /** DESIGN.md §3.2 의 코어 서비스 식별자. 패키지는 {@code com.dawnline.<service>} 다. */
    public static final List<String> SERVICES =
            List.of("order", "fulfillment", "dispatch", "tracking", "ops");

    private static final String SPRING_PACKAGE = "org.springframework..";
    private static final String JPA_PACKAGE = "jakarta.persistence..";
    private static final String SPRING_KAFKA_PACKAGE = "org.springframework.kafka..";

    /**
     * 규칙 7이 금지하는 호출 — 클래스 이름 → 메서드 이름들.
     *
     * <p>{@code Clock.system(ZoneId)} 도 포함한다. {@code systemDefaultZone()} 만 막고 이것을 두면
     * 같은 구멍이 한 칸 옆에 그대로 남는다.
     *
     * <p>이름만으로는 부족하다 — {@code now(Clock)} 오버로드는 <em>주입받은</em> 시계를 읽는
     * 올바른 형태이고, {@code now(ZoneId)} 는 시스템 시계를 읽는 위반이다. 구분은 인자 타입이며
     * 그 판정은 {@link #SYSTEM_CLOCK_CALL} 에 있다.
     */
    private static final String CLOCK = "java.time.Clock";

    private static final Map<String, List<String>> FORBIDDEN_CLOCK_CALLS = Map.of(
            "java.time.Instant", List.of("now"),
            CLOCK, List.of("systemUTC", "systemDefaultZone", "system"),
            "java.time.LocalDate", List.of("now"),
            "java.time.LocalDateTime", List.of("now"),
            "java.time.LocalTime", List.of("now"),
            "java.time.ZonedDateTime", List.of("now"),
            "java.time.OffsetDateTime", List.of("now"),
            "java.lang.System", List.of("currentTimeMillis"));

    /**
     * 규칙 7 의 조건. 공개하는 이유는 테스트가 <em>같은</em> 조건을 표본에 적용해야 하기 때문이다 —
     * 규칙의 {@code that} 절이 {@code com.dawnline.<service>..} 로 좁혀져 있어 표본 패키지에는
     * 닿지 않는다. 테스트가 조건을 따로 적으면 둘이 표류한다.
     */
    public static final DescribedPredicate<com.tngtech.archunit.core.domain.JavaMethodCall>
            SYSTEM_CLOCK_CALL = new DescribedPredicate<>(
                    "시스템 시계를 직접 읽는 호출(Instant.now·Clock.systemUTC 등)") {
                @Override
                public boolean test(com.tngtech.archunit.core.domain.JavaMethodCall call) {
                    List<String> methods =
                            FORBIDDEN_CLOCK_CALLS.get(call.getTargetOwner().getFullName());
                    if (methods == null || !methods.contains(call.getName())) {
                        return false;
                    }
                    // now(Clock) 는 정확히 우리가 원하는 형태다 — 주입받은 시계로 지금을 읽는 것.
                    // 이름만 보고 막으면 TierEligibility.nowInServiceZone() 같은 올바른 코드가 걸린다
                    // (실제로 이 규칙을 처음 켰을 때 그렇게 걸렸다).
                    // 반면 now(ZoneId) 는 시스템 시계를 읽으므로 막아야 한다. 구분은 인자 타입이다.
                    return call.getTarget().getRawParameterTypes().stream()
                            .noneMatch(parameter -> CLOCK.equals(parameter.getFullName()));
                }
            };

    private static final String KAFKA_LISTENER = "org.springframework.kafka.annotation.KafkaListener";
    private static final String SPRING_TRANSACTIONAL = "org.springframework.transaction.annotation.Transactional";
    private static final String JAKARTA_TRANSACTIONAL = "jakarta.transaction.Transactional";

    /**
     * 규칙 1 — {@code ..domain..} 은 Spring 과 JPA 에 의존하지 않는다.
     *
     * <p>CLAUDE.md 불변규칙 5. 특히 {@code dispatch-service} 의 {@code domain.optimizer} 는
     * 순수 Java 여야 벤치마크 도구에서 그대로 실행된다.
     */
    public static final ArchRule DOMAIN_IS_FRAMEWORK_FREE =
            ArchRuleDefinition.noClasses()
                    .that()
                    .resideInAPackage("..domain..")
                    .should()
                    .dependOnClassesThat()
                    .resideInAnyPackage(SPRING_PACKAGE, JPA_PACKAGE)
                    .because("domain 은 프레임워크에 의존하지 않는다 (CLAUDE.md 불변규칙 5)")
                    .allowEmptyShould(true);

    /**
     * 규칙 2 — {@code ..application..} 은 {@code ..adapter..} 에 의존하지 않는다.
     *
     * <p>의존 방향은 {@code adapter → application → domain} 한 방향뿐이다 (DESIGN.md §3.4).
     */
    public static final ArchRule APPLICATION_DOES_NOT_DEPEND_ON_ADAPTER =
            ArchRuleDefinition.noClasses()
                    .that()
                    .resideInAPackage("..application..")
                    .should()
                    .dependOnClassesThat()
                    .resideInAPackage("..adapter..")
                    .because("의존 방향은 adapter → application → domain 뿐이다 (DESIGN.md §3.4)")
                    .allowEmptyShould(true);

    /**
     * 규칙 6 — {@code ..domain..} · {@code ..application..} 은 Spring Kafka 에 의존하지 않는다.
     *
     * <p>CLAUDE.md 불변규칙 1(Outbox 필수). 유스케이스가 {@code KafkaTemplate} 을 직접 부르면
     * 도메인 변경과 이벤트 발행이 <strong>서로 다른 트랜잭션</strong>이 되어, 둘 중 하나만 성공하는
     * 상태가 만들어진다. 발행 경로는 {@code OutboxAppender} 하나뿐이어야 한다.
     *
     * <p>이 규칙이 필요한 이유는 구조적이다. {@code libs/messaging} 이
     * {@code api(spring-boot-starter-kafka)} 로 의존을 노출하므로 {@code KafkaTemplate} 은
     * <em>5개 서비스 전부의 컴파일 클래스패스에 있다</em>. 즉 "이벤트 하나만 빨리 쏘자" 는 코드가
     * 컴파일도 되고 테스트도 통과한다. 규칙 5({@code @Transactional} 위치)는 이것을 잡지 못한다 —
     * 어노테이션의 위치만 보기 때문이다.
     *
     * <p>{@code adapter.out.messaging} 은 제외된다. 그곳이 Kafka 를 아는 것이 어댑터의 책임이다.
     */
    public static final ArchRule PUBLISHING_GOES_THROUGH_OUTBOX_ONLY =
            ArchRuleDefinition.noClasses()
                    .that()
                    .resideInAnyPackage("..domain..", "..application..")
                    .should()
                    .dependOnClassesThat()
                    .resideInAPackage(SPRING_KAFKA_PACKAGE)
                    .because("이벤트 발행은 Outbox 를 거친다 — 유스케이스는 KafkaTemplate 을 직접 부르지 않는다 "
                            + "(CLAUDE.md 불변규칙 1, DESIGN.md §4.4)")
                    .allowEmptyShould(true);

    /**
     * 규칙 7 — 서비스 코드는 시스템 시계를 직접 읽지 않는다 (CLAUDE.md 불변규칙 12).
     *
     * <h2>이 규칙이 왜 생겼는가</h2>
     * Phase 1에서 실제로 물린 결함이다. {@code Clock.systemUTC()} 의 해상도는 플랫폼에 달려 있어서
     * macOS 는 마이크로초에서 끊기고 Linux 는 나노초까지 준다. PostgreSQL 의 {@code TIMESTAMPTZ} 는
     * 마이크로초까지만 담으므로, 나노초가 섞이면 <strong>API 가 DB 에 저장할 수 없는 값을 응답에
     * 싣는다</strong> — {@code POST} 응답의 시각과 {@code GET} 의 시각이 다르고, 멱등 재생이
     * "그때 준 답" 을 그대로 주지 못한다. 그리고 그 결함은 <em>개발 기계에서는 보이지 않고 CI 에서만
     * 터진다.</em>
     *
     * <p>고친 방법은 저장 정밀도로 자른 {@code Clock} 빈을 {@code libs/messaging} 한 곳에 두는
     * 것이었다. 이 규칙은 그 층을 우회하는 경로를 막는다 — 다음 서비스가 자기 시계를 직접 읽어
     * 같은 문제를 되살리지 못하게.
     *
     * <h2>무엇을 금지하지 않는가</h2>
     * {@code Clock.fixed}·{@code Clock.tick}·{@code Clock.offset} 은 금지하지 않는다. 그것들은
     * 주입할 시계를 <em>만드는</em> 팩토리이지 시스템 시계를 읽는 것이 아니다.
     * {@code System.nanoTime()} 도 금지하지 않는다 — 그것은 시각이 아니라 경과 시간 측정용이고
     * 벽시계와 무관하다. {@code instant()} 처럼 <em>주입된</em> {@code Clock} 을 읽는 호출도 대상이 아니다.
     *
     * @param service {@link #SERVICES} 중 하나
     */
    public static ArchRule clocksAreInjected(String service) {
        String owner = requireKnownService(service);
        return ArchRuleDefinition.noClasses()
                .that()
                .resideInAPackage(packageOf(owner) + "..")
                .should()
                .callMethodWhere(ArchPredicates.are(SYSTEM_CLOCK_CALL))
                .because("시간은 주입한다 — libs/messaging 이 저장 정밀도(마이크로초)로 자른 Clock 을 준다 "
                        + "(CLAUDE.md 불변규칙 12). 시스템 시계를 직접 읽으면 플랫폼마다 정밀도가 달라 "
                        + "TIMESTAMPTZ 왕복에서 값이 바뀌고, 그 결함은 CI 에서만 드러난다")
                .allowEmptyShould(true);
    }

    private HexagonalArchitectureRules() {
        throw new AssertionError("유틸리티 클래스는 생성하지 않는다");
    }

    /** {@code com.dawnline.<service>} — 해당 서비스의 루트 패키지. */
    public static String packageOf(String service) {
        return BASE_PACKAGE + "." + requireKnownService(service);
    }

    /**
     * 규칙 3 — 서비스는 다른 서비스의 패키지를 참조하지 않는다.
     *
     * <p>CLAUDE.md 불변규칙 3·4: 서비스 간 소스·DB 공유 금지, 코어 서비스 간 동기 호출 금지.
     * 필요한 데이터는 이벤트 페이로드 스냅샷이나 자기 DB 프로젝션으로 가진다.
     *
     * @param service {@link #SERVICES} 중 하나 (예: {@code "order"})
     */
    public static ArchRule noCrossServiceDependency(String service) {
        String owner = requireKnownService(service);
        String[] foreignPackages = SERVICES.stream()
                .filter(other -> !other.equals(owner))
                .map(other -> BASE_PACKAGE + "." + other + "..")
                .toArray(String[]::new);

        return ArchRuleDefinition.noClasses()
                .that()
                .resideInAPackage(packageOf(owner) + "..")
                .should()
                .dependOnClassesThat()
                .resideInAnyPackage(foreignPackages)
                .because(owner + " 서비스는 다른 서비스 패키지를 참조하지 않는다 (CLAUDE.md 불변규칙 3·4)")
                .allowEmptyShould(true);
    }

    /**
     * 규칙 4 — {@code @KafkaListener} 는 {@code adapter.in.messaging} 에만 존재한다.
     *
     * <p>클래스에 붙었든 메서드에 붙었든 모두 검사한다. Kafka 수신은 인바운드 어댑터의 책임이며,
     * 리스너는 멱등 처리 후 유스케이스를 호출하기만 한다 (CLAUDE.md 불변규칙 2).
     *
     * @param service {@link #SERVICES} 중 하나
     */
    public static ArchRule kafkaListenersOnlyInInboundMessagingAdapter(String service) {
        String owner = requireKnownService(service);
        return ArchRuleDefinition.classes()
                .that(annotatedItselfOrOnAnyMethodWith(KAFKA_LISTENER))
                .should()
                .resideInAPackage(packageOf(owner) + ".adapter.in.messaging..")
                .because("Kafka 수신은 인바운드 메시징 어댑터의 책임이다 (DESIGN.md §3.4)")
                .allowEmptyShould(true);
    }

    /**
     * 규칙 5 — {@code @Transactional} 은 {@code application} 계층에만 존재한다.
     *
     * <p>Spring 과 Jakarta 양쪽 어노테이션을 모두 검사한다. 트랜잭션 경계는 유스케이스가 정하며,
     * 도메인 상태 변경과 outbox 기록이 같은 트랜잭션에 묶이는 지점이다 (CLAUDE.md 불변규칙 1).
     *
     * @param service {@link #SERVICES} 중 하나
     */
    public static ArchRule transactionalOnlyInApplicationLayer(String service) {
        String owner = requireKnownService(service);
        return ArchRuleDefinition.classes()
                .that(
                        annotatedItselfOrOnAnyMethodWith(SPRING_TRANSACTIONAL)
                                .or(annotatedItselfOrOnAnyMethodWith(JAKARTA_TRANSACTIONAL)))
                .should()
                .resideInAPackage(packageOf(owner) + ".application..")
                .because("트랜잭션 경계는 application 계층이 정한다 (DESIGN.md §3.4, CLAUDE.md 불변규칙 1)")
                .allowEmptyShould(true);
    }

    /** 한 서비스에 적용할 7개 규칙 전부. */
    public static List<ArchRule> allRulesFor(String service) {
        String owner = requireKnownService(service);
        List<ArchRule> rules = new ArrayList<>();
        rules.add(DOMAIN_IS_FRAMEWORK_FREE);
        rules.add(APPLICATION_DOES_NOT_DEPEND_ON_ADAPTER);
        rules.add(PUBLISHING_GOES_THROUGH_OUTBOX_ONLY);
        rules.add(noCrossServiceDependency(owner));
        rules.add(kafkaListenersOnlyInInboundMessagingAdapter(owner));
        rules.add(transactionalOnlyInApplicationLayer(owner));
        rules.add(clocksAreInjected(owner));
        return List.copyOf(rules);
    }

    private static DescribedPredicate<JavaClass> annotatedItselfOrOnAnyMethodWith(String annotationName) {
        return new DescribedPredicate<>("@%s 가 클래스나 메서드에 붙은".formatted(simpleName(annotationName))) {
            @Override
            public boolean test(JavaClass javaClass) {
                return javaClass.isAnnotatedWith(annotationName)
                        || javaClass.getMethods().stream()
                                .anyMatch(method -> method.isAnnotatedWith(annotationName));
            }
        };
    }

    private static String simpleName(String fullyQualifiedName) {
        return fullyQualifiedName.substring(fullyQualifiedName.lastIndexOf('.') + 1);
    }

    private static String requireKnownService(String service) {
        Objects.requireNonNull(service, "service");
        if (!SERVICES.contains(service)) {
            throw new IllegalArgumentException(
                    "알 수 없는 서비스입니다: '" + service + "'. 사용 가능: " + SERVICES);
        }
        return service;
    }
}
