package com.dawnline.common.archunit;

import com.tngtech.archunit.base.DescribedPredicate;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.lang.ArchRule;
import com.tngtech.archunit.lang.syntax.ArchRuleDefinition;
import java.util.ArrayList;
import java.util.List;
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

    /** 한 서비스에 적용할 6개 규칙 전부. */
    public static List<ArchRule> allRulesFor(String service) {
        String owner = requireKnownService(service);
        List<ArchRule> rules = new ArrayList<>();
        rules.add(DOMAIN_IS_FRAMEWORK_FREE);
        rules.add(APPLICATION_DOES_NOT_DEPEND_ON_ADAPTER);
        rules.add(PUBLISHING_GOES_THROUGH_OUTBOX_ONLY);
        rules.add(noCrossServiceDependency(owner));
        rules.add(kafkaListenersOnlyInInboundMessagingAdapter(owner));
        rules.add(transactionalOnlyInApplicationLayer(owner));
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
