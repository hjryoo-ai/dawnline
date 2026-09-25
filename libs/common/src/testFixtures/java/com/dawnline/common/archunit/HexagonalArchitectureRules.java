package com.dawnline.common.archunit;

import com.tngtech.archunit.base.DescribedPredicate;
import com.tngtech.archunit.core.domain.JavaAnnotation;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.lang.ArchCondition;
import com.tngtech.archunit.lang.ArchRule;
import com.tngtech.archunit.lang.ConditionEvents;
import com.tngtech.archunit.lang.SimpleConditionEvent;
import com.tngtech.archunit.lang.conditions.ArchPredicates;
import com.tngtech.archunit.lang.syntax.ArchRuleDefinition;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;

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
    private static final String CONTROLLER_ADVICE = "org.springframework.web.bind.annotation.ControllerAdvice";

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

    private static final String SPRING_WEB_ANNOTATIONS = "org.springframework.web.bind.annotation.";

    /** {@code /api/v1/orders} 의 {@code v1} 처럼 <em>버전이 박힌</em> 경로 세그먼트. */
    private static final Pattern LITERAL_VERSION_SEGMENT = Pattern.compile("v\\d+", Pattern.CASE_INSENSITIVE);

    /**
     * 규칙 8 의 조건. 공개하는 이유는 규칙 7 과 같다 — 규칙의 {@code that} 절이
     * {@code com.dawnline.<service>.adapter.in.web..} 로 좁혀져 있어 표본 패키지에 닿지 않고,
     * 테스트가 조건을 따로 적으면 둘이 표류한다.
     */
    public static final ArchCondition<JavaClass> HARDCODE_API_VERSION_IN_MAPPING =
            new ArchCondition<>("매핑 경로에 리터럴 버전 세그먼트(v1)를 박은") {
                @Override
                public void check(JavaClass item, ConditionEvents events) {
                    for (String path : mappingPathsOf(item)) {
                        for (String segment : path.split("/")) {
                            if (LITERAL_VERSION_SEGMENT.matcher(segment).matches()) {
                                events.add(SimpleConditionEvent.satisfied(item,
                                        "%s 의 매핑 경로 \"%s\" 에 리터럴 버전 세그먼트 \"%s\" 가 있다"
                                                .formatted(item.getName(), path, segment)));
                            }
                        }
                    }
                }
            };

    /**
     * 규칙 8 — REST 매핑 경로에 버전을 <strong>박지 않는다</strong>
     * ([ADR-009](docs/adr/ADR-009-url-path-api-versioning.md) 결정 2).
     *
     * <p>주소는 {@code /api/v1/orders} 그대로다. 달라지는 것은 <em>매핑</em>이다 —
     * {@code @RequestMapping(path = "/api/{version}/orders", version = "1")} 으로 두면
     * {@code /api/v2/orders} 가 경로 매칭을 통과해 <strong>버전 조건</strong>까지 도달하고,
     * 지원하지 않는 버전이라는 400 으로 답한다. 리터럴 {@code v1} 이면 경로에서 먼저 떨어져
     * <strong>404</strong> 가 되고, 그러면 클라이언트는 오타와 버전 불일치를 구분할 수 없다.
     *
     * <p>이 규칙이 생긴 이유는 실제 표류다 (2026-09-19). ADR-009 는 order-service 의 첫 컨트롤러를
     * 만들며 쓰였고 그 서비스는 지켰지만, dispatch-service 의 컨트롤러 셋은 리터럴
     * {@code /api/v1} 로 들어왔다 — 「운영자 API 라서 다르다」는 ADR 에 없는 예외이고, ADR 을
     * 읽지 않은 사람이 하나 더 만들 때마다 같은 일이 반복된다. 결정이 한 서비스에만 적용되고
     * 있었다는 사실은 <em>아무 테스트도 보지 않고 있었다.</em>
     *
     * <p>검사 대상은 {@code adapter.in.web} 의 클래스와 메서드에 붙은 Spring 의 모든
     * {@code *Mapping} 어노테이션이다 — 이름을 열거하지 않고 패키지와 접미사로 고른다
     * (CLAUDE.md 「집합을 도는 검사는 열거하지 않고 전체에서 뺀다」). {@code @GetMapping} 이
     * 새로 생겨도 대상이다.
     *
     * @param service {@link #SERVICES} 중 하나
     */
    public static ArchRule apiVersionIsNotHardcodedInMappings(String service) {
        String owner = requireKnownService(service);
        return ArchRuleDefinition.noClasses()
                .that()
                .resideInAPackage(packageOf(owner) + ".adapter.in.web..")
                .should(HARDCODE_API_VERSION_IN_MAPPING)
                .because("API 버전은 {version} 자리표시자 + ApiVersionConfigurer 로 해석한다 (ADR-009 결정 2). "
                        + "리터럴 v1 은 지원하지 않는 버전을 400 이 아니라 404 로 만들어, 클라이언트가 "
                        + "오타와 버전 불일치를 구분할 수 없게 한다")
                .allowEmptyShould(true);
    }

    /** 클래스와 그 메서드들에 붙은 Spring {@code *Mapping} 어노테이션의 경로 전부. */
    private static List<String> mappingPathsOf(JavaClass javaClass) {
        List<String> paths = new ArrayList<>();
        collectMappingPaths(javaClass.getAnnotations(), paths);
        javaClass.getMethods().forEach(method -> collectMappingPaths(method.getAnnotations(), paths));
        return paths;
    }

    private static void collectMappingPaths(Set<? extends JavaAnnotation<?>> annotations, List<String> into) {
        for (JavaAnnotation<?> annotation : annotations) {
            String type = annotation.getRawType().getName();
            if (!type.startsWith(SPRING_WEB_ANNOTATIONS) || !type.endsWith("Mapping")) {
                continue;
            }
            // value 와 path 는 서로의 별칭이다. 둘 다 읽는다 — 어느 쪽으로 적었든 같은 경로다.
            addStrings(annotation, "value", into);
            addStrings(annotation, "path", into);
        }
    }

    private static void addStrings(JavaAnnotation<?> annotation, String attribute, List<String> into) {
        annotation.get(attribute).ifPresent(value -> {
            if (value instanceof Object[] array) {
                for (Object element : array) {
                    into.add(String.valueOf(element));
                }
            } else {
                into.add(String.valueOf(value));
            }
        });
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

    /**
     * 규칙 9 가 가리키는 기반 클래스의 이름. <strong>문자열</strong>인 이유는 방향이다 —
     * {@code libs/web} 이 {@code libs/common} 에 의존하므로 그 반대는 성립하지 않는다.
     *
     * <p>문자열 링크는 <strong>끊어져도 조용하다</strong>: 클래스 이름이 바뀌면 규칙은 아무것도
     * 매치하지 않으면서 통과한다. 그래서 {@code libs/web} 의
     * {@code ProblemDetailsAdviceRuleTest} 가 이 값과 실제 클래스 이름을 <em>대조한다</em>
     * (DESIGN.md §13 규칙 3).
     */
    public static final String ERROR_ADVICE_BASE = "com.dawnline.web.ProblemDetailsAdviceSupport";

    /**
     * 규칙 9 — {@code @ControllerAdvice} 계열 클래스는 {@code libs/web} 의 기반을 쓴다.
     *
     * <p>[ADR-049] 결정 4. RFC 9457 오류 본문의 모양은 클라이언트가 계약으로 삼는 것이므로
     * 서비스마다 갈라지면 안 된다. <strong>열거가 아니라 조건</strong>으로 적는다 — 서비스
     * 이름을 드는 대신 어노테이션이 붙은 클래스 전부에 묻는다. 새 서비스(ops-api)는 스스로
     * 대상이 된다. 열거였던 규칙이 새 구성원을 놓친 일이 이 저장소에 있었고(ADR-009 의 버전
     * 규칙, 2026-09-19), 그것이 규칙 8 이 생긴 이유다.
     *
     * <p>{@code @RestControllerAdvice} 는 {@code @ControllerAdvice} 를 메타 어노테이션으로
     * 달고 있으므로 둘 다 걸린다.
     */
    public static final ArchRule ERROR_SHAPE_COMES_FROM_ONE_PLACE =
            ArchRuleDefinition.classes()
                    .that(isControllerAdvice())
                    .should()
                    .beAssignableTo(ERROR_ADVICE_BASE)
                    .because("오류 응답의 모양은 libs/web 의 " + ERROR_ADVICE_BASE
                            + " 한 곳에서 정한다 (ADR-049)")
                    .allowEmptyShould(true);

    /**
     * 규칙 10 — {@code libs/common} 의 {@code main} 은 Spring 과 JPA 에 의존하지 않는다.
     *
     * <p>[ADR-049] 결정 2. {@code libs/common/build.gradle.kts} 의 첫 줄 선언은
     * <strong>문장이지 강제가 아니다</strong> — Spring 의존을 한 줄 추가하면 그 주석은 그대로
     * 있고 빌드는 통과한다. 규칙 1 이 {@code ..domain..} 을 지키듯 이 규칙이 그 모듈을 지킨다.
     *
     * <p>분석 대상을 {@code main} 출력으로 좁히는 것은 <strong>호출하는 쪽</strong>의 몫이다 —
     * 이 모듈의 {@code test} 소스셋에는 Spring 을 일부러 참조하는 위반 표본들이 산다
     * ({@code archunit/samples/bad}). 좁히기가 실패하면 규칙은 0 개를 검사하고 통과하므로,
     * {@code LibsCommonIsFrameworkFreeTest} 가 <em>읽은 클래스가 있다</em>를 첫 어설션으로 말한다.
     */
    public static final ArchRule LIBS_COMMON_MAIN_IS_FRAMEWORK_FREE =
            ArchRuleDefinition.noClasses()
                    .should()
                    .dependOnClassesThat()
                    .resideInAnyPackage(SPRING_PACKAGE, JPA_PACKAGE)
                    .because("libs/common 은 프레임워크 비의존 순수 Java 다 "
                            + "(CLAUDE.md 불변규칙 5, ADR-049 결정 2) — "
                            + "Spring 을 아는 공유 코드는 libs/web 에 산다")
                    .allowEmptyShould(true);

    /** {@code @ControllerAdvice} 가 직접 또는 메타로 붙은 클래스. */
    private static DescribedPredicate<JavaClass> isControllerAdvice() {
        return new DescribedPredicate<>("@ControllerAdvice 계열이 붙은") {
            @Override
            public boolean test(JavaClass javaClass) {
                return javaClass.isAnnotatedWith(CONTROLLER_ADVICE)
                        || javaClass.isMetaAnnotatedWith(CONTROLLER_ADVICE);
            }
        };
    }

    /**
     * 규칙 11 이 허용하는 유일한 자리 — 등록 헬퍼({@code DawnlineMeters})의 패키지.
     *
     * <p><strong>문자열</strong>인 이유는 규칙 9 와 같다 — {@code libs/observability} 가 {@code libs/common} 에 의존하므로
     * 반대 방향의 타입 참조는 성립하지 않는다. 문자열 링크는 끊어져도 조용하므로 {@code libs/observability} 의
     * {@code MeterRegistrationRuleTest} 가 이 값을 헬퍼의 실제 패키지와 대조한다.
     */
    public static final String METER_HELPER_PACKAGE = "com.dawnline.observability";

    /** Micrometer 의 미터 빌더 — {@code builder(...)} 를 부르는 것이 곧 등록이다. */
    private static final Set<String> METER_BUILDERS = Set.of(
            "io.micrometer.core.instrument.Counter",
            "io.micrometer.core.instrument.Gauge",
            "io.micrometer.core.instrument.Timer",
            "io.micrometer.core.instrument.DistributionSummary",
            "io.micrometer.core.instrument.LongTaskTimer",
            "io.micrometer.core.instrument.FunctionCounter",
            "io.micrometer.core.instrument.FunctionTimer",
            "io.micrometer.core.instrument.TimeGauge",
            "io.micrometer.core.instrument.MultiGauge");

    private static final String METER_REGISTRY = "io.micrometer.core.instrument.MeterRegistry";

    /** 전역 레지스트리의 정적 등록 — 같은 부류다. */
    private static final String GLOBAL_METRICS = "io.micrometer.core.instrument.Metrics";

    /** 레지스트리가 이름을 받아 미터를 만드는 메서드. {@code get}·{@code find} 는 읽기라 허용한다. */
    private static final Set<String> REGISTRY_REGISTRATIONS =
            Set.of("counter", "timer", "gauge", "summary", "gaugeCollectionSize", "gaugeMapSize", "more");

    /** 규칙 11 이 금지하는 호출 — 헬퍼를 지나지 않고 미터를 만드는 것. */
    public static final DescribedPredicate<com.tngtech.archunit.core.domain.JavaMethodCall>
            METER_REGISTRATION_CALL = new DescribedPredicate<>(
                    "Micrometer 로 미터를 직접 등록하는 호출(Counter.builder · registry.gauge 등)") {
                @Override
                public boolean test(com.tngtech.archunit.core.domain.JavaMethodCall call) {
                    JavaClass owner = call.getTargetOwner();
                    String name = call.getName();
                    if (METER_BUILDERS.contains(owner.getFullName())) {
                        return name.equals("builder");
                    }
                    if (GLOBAL_METRICS.equals(owner.getFullName())) {
                        return REGISTRY_REGISTRATIONS.contains(name);
                    }
                    return REGISTRY_REGISTRATIONS.contains(name) && owner.isAssignableTo(METER_REGISTRY);
                }
            };

    /**
     * 규칙 11 — 미터는 {@code DawnlineMeters} 로만 등록한다([ADR-060] 결정 2).
     *
     * <p>헬퍼가 등록 때 §9.1 의 카탈로그와 타입 · 라벨 키 · 닫힌 값을 대조하고, {@code histogram} 에 버킷을 켜고, 게이지를
     * 강한 참조로 잡는다. 헬퍼를 지나지 않은 등록은 그 셋이 전부 풀린다 — 이름이 표의 대조 밖에 생기고, 게이지는 약한
     * 참조로 돌아가 대상이 GC 되면 조용히 {@code NaN} 을 낸다(§13 축 10 의 변종).
     *
     * <p>서비스에는 {@link #allRulesFor(String)} 가 건다. {@code libs/messaging} · {@code libs/web} 은 서비스가 아니라서
     * 각자의 테스트가 자기 패키지에 이 규칙을 건다.
     *
     * @param rootPackage 대상 루트 패키지({@code com.dawnline.dispatch} · {@code com.dawnline.messaging} …)
     * @return 규칙
     */
    public static ArchRule metersRegisterThroughCatalogue(String rootPackage) {
        return ArchRuleDefinition.noClasses()
                .that()
                .resideInAPackage(rootPackage + "..")
                .and()
                .resideOutsideOfPackage(METER_HELPER_PACKAGE + "..")
                .should()
                .callMethodWhere(ArchPredicates.are(METER_REGISTRATION_CALL))
                .because("미터는 §9.1 의 카탈로그 항목으로, DawnlineMeters 한 곳에서 등록한다 (ADR-060 결정 2) — "
                        + "헬퍼를 지나지 않으면 이름 · 라벨의 대조와 게이지의 강한 참조가 풀린다")
                .allowEmptyShould(true);
    }

    /** 규칙 12 가 보는 HTTP 클라이언트의 자리 — Spring 의 세 클라이언트(동기 · 반응형 · HTTP 인터페이스)와 JDK 의 둘. */
    private static final String[] HTTP_CLIENT_PACKAGES = {
            "org.springframework.web.client..",
            "org.springframework.web.reactive.function.client..",
            "org.springframework.web.service.invoker..",
            "java.net.http.."};

    /**
     * 규칙 12 에서 <strong>빠지는</strong> 서비스와 그 이유 — 빼는 방식이다(CLAUDE.md 「집합을 도는 검사는 열거하지 않고 전체에서
     * 뺀다」). 새 서비스는 이 표에 이유와 함께 적히기 전에는 규칙 안에 있다.
     */
    public static final Map<String, String> HTTP_CLIENT_OWNERS = Map.of(
            "ops", "ops-api 의 위임이 이 규칙이 막는 바로 그 일이다 — 코어를 부르는 클라이언트가 커밋된 계약에서 생성된다"
                    + "(ADR-052). 동기 호출은 ops-api → 코어 방향만이다(불변규칙 4)");

    /**
     * 규칙 12 — 코어는 HTTP 클라이언트에 의존하지 않는다(불변규칙 4, ADR-015 후속 정정 2026-09-25).
     *
     * <p>두 문장을 지킨다. 불변규칙 4 의 「코어 서비스 간 동기 호출 금지」 — 규칙 3 은 모노레포 안의 패키지 참조만 잡고 HTTP 로 부르는
     * 것은 못 잡았다. 그리고 소비 측 경계표의 「HTTP — 해당 없음」 — 코어의 리스너가 외부 호출을 하지 않아서 그 행이 비어 있다. 이
     * 규칙이 깨지는 날 그 칸이 실제 행이 되어야 한다.
     *
     * @param rootPackage 대상 루트 패키지({@code com.dawnline.dispatch} …)
     * @return 규칙
     */
    public static ArchRule noOutboundHttp(String rootPackage) {
        return ArchRuleDefinition.noClasses()
                .that()
                .resideInAPackage(rootPackage + "..")
                .should()
                .dependOnClassesThat()
                .resideInAnyPackage(HTTP_CLIENT_PACKAGES)
                .orShould()
                .dependOnClassesThat()
                .haveFullyQualifiedName("java.net.HttpURLConnection")
                .because("코어의 동기 호출은 ops-api → 코어 방향뿐이다(불변규칙 4) — 그리고 소비 측 경계표의 HTTP 행이 "
                        + "「해당 없음」인 근거가 이것이다(ADR-015 후속 정정)")
                .allowEmptyShould(true);
    }

    /**
     * 한 서비스에 적용할 규칙 전부 — 코어는 11개, {@link #HTTP_CLIENT_OWNERS} 의 서비스는 규칙 12 를 뺀 10개.
     * 규칙 10 은 서비스가 아니라 libs/common 에 건다.
     */
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
        rules.add(apiVersionIsNotHardcodedInMappings(owner));
        rules.add(ERROR_SHAPE_COMES_FROM_ONE_PLACE);
        rules.add(metersRegisterThroughCatalogue(packageOf(owner)));
        if (!HTTP_CLIENT_OWNERS.containsKey(owner)) {
            rules.add(noOutboundHttp(packageOf(owner)));
        }
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
