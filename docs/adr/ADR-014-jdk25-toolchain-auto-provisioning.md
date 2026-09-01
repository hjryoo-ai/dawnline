# ADR-014 — JDK 25 툴체인 자동 프로비저닝 (Gradle foojay-resolver)

| 항목 | 내용 |
|---|---|
| 상태 | Accepted |
| 결정일 | 2026-08-29 |
| 관련 문서 | `docs/DESIGN.md` §11(기술 스택), §14(CI/CD) · `CLAUDE.md` 기술 스택 |
| 구현 위치 | `settings.gradle.kts`, `gradle.properties`, `buildSrc/src/main/kotlin/dawnline.java-conventions.gradle.kts` |

---

## 맥락

설계서 §11은 Java **25 LTS(Eclipse Temurin)** 를 요구한다. 가상 스레드·`record`·`sealed`·패턴 매칭을
정식 기능으로 쓰는 것이 전제다.

그런데 이 저장소를 처음 빌드하려 한 개발 머신에는 **JDK 21만 설치되어 있었다.** CI는 `setup-java` 로 원하는 버전을
설치할 수 있지만, 로컬은 사람이 직접 설치해야 한다. 그리고 이 저장소의 1차 독자는 채용 리뷰어이며,
"클론 → `./gradlew build`" 사이에 수동 설치 단계가 끼면 그 자체가 마이너스다.

또한 로컬과 CI가 **다른 벤더·다른 패치 버전**의 JDK를 쓰면, 재현되지 않는 컴파일·테스트 차이가 생긴다.
이 저장소는 `-Werror` 로 빌드하므로 JDK 마이너 차이에 따른 새 경고 하나가 빌드를 깨뜨릴 수 있다.

## 결정

**Gradle이 JDK 25를 자동으로 내려받아 사용하게 한다.**

- `settings.gradle.kts`: `id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"`
- `gradle.properties`: `org.gradle.java.installations.auto-download=true`
- `dawnline.java-conventions`: `toolchain { languageVersion = 25(카탈로그 값); vendor = ADOPTIUM }`

Gradle wrapper(9.3.0)는 개발자의 `JAVA_HOME` 이 무엇이든, 로컬에 설치된 JDK 목록에 25가 없으면
foojay API로 Temurin 25를 조회해 `~/.gradle/jdks` 에 프로비저닝한 뒤 그 JDK로 컴파일·테스트한다.
JDK 버전의 진실 원천은 `gradle/libs.versions.toml` 의 `java = "25"` 하나이며, ADR-013의 `BP_JVM_VERSION` 도 이 값을 읽는다.

## 근거

이것은 추측이 아니라 이 저장소에서 실제로 일어난 일이다(2026-08-29 실측).

**1) Gradle이 Temurin 25를 프로비저닝했다.**

```
~/.gradle/jdks/eclipse_adoptium-25-aarch64-os_x.2/jdk-25.0.4.1+1/Contents/Home/release
  IMPLEMENTOR="Eclipse Adoptium"
  IMPLEMENTOR_VERSION="Temurin-25.0.4.1+1"
  JAVA_VERSION="25.0.4.1"

$ .../bin/java -version
openjdk version "25.0.4.1" 2026-08-18 LTS
OpenJDK Runtime Environment Temurin-25.0.4.1+1 (build 25.0.4.1+1-LTS)
```

**2) 그 JDK의 컴파일 산출물이 실제로 Java 25 바이트코드다.**

```
$ .../bin/javap -v Probe.class | head
  minor version: 0
  major version: 69          # 69 = Java 25
```

class file major version 69는 Java 25다(21은 65). 즉 "Java 25로 빌드된다"는 문장이 **검증된 사실**이며,
설계서 §11의 요구가 문서상 선언이 아니라 빌드 산출물로 확인된다.

**3) 로컬과 CI가 같은 JDK를 쓴다.** 툴체인 명세(버전 25 + 벤더 Adoptium)가 양쪽에서 동일하게 해석되므로,
CI 러너의 기본 JDK나 개발자의 `JAVA_HOME` 이 무엇이든 컴파일러는 같다. `-Werror` 빌드에서 이 성질이 특히 중요하다.

**4) 온보딩 단계가 사라진다.** 전제조건이 "Git + Docker"로 줄어든다. JDK 설치·버전 전환 도구(sdkman/jenv) 안내가 필요 없다.

## 고려한 대안과 기각 이유

| 대안 | 장점 | 기각 이유 |
|---|---|---|
| README 전제조건에 "JDK 25 설치" 명시(수동) | 빌드 부트스트랩이 외부 서비스에 의존하지 않는다 | 온보딩에 수동 단계가 생기고, 설치된 벤더·패치가 사람마다 달라진다. 리뷰어가 5분 안에 빌드하지 못하면 포트폴리오로서 실패다 |
| CI만 `setup-java`, 로컬은 개발자 재량 | 설정이 가장 적다 | 로컬/CI 불일치를 방치한다. "내 노트북에서는 빌드된다"가 곧바로 발생한다 |
| 컨테이너 안에서만 빌드(빌드용 이미지) | 완전한 재현성 | Gradle 빌드 캐시·설정 캐시·IDE 통합을 잃고 반복 빌드가 느려진다. 개발 경험 손실이 크다 |
| Java 21로 낮춘다 | 어느 머신에나 있다 | §11 결정을 번복하는 것이고, 실제로 25로 문제없이 빌드된다(위 실측). 낮출 이유가 없다 |
| 툴체인 버전만 지정하고 자동 다운로드는 끈다 | 외부 의존 없음 | 로컬에 25가 없으면 빌드가 **실패**한다. 실패 메시지가 친절하지도 않다 |

## 결과

**장점**

- 클론 후 한 명령으로 빌드된다. 전제조건이 Git과 Docker(테스트·이미지용)뿐이다.
- 로컬·CI가 동일한 컴파일러·런타임을 쓴다.
- JDK 버전 상승이 `libs.versions.toml` 한 줄이고, 컨테이너 런타임(ADR-013)도 함께 따라온다.

**비용**

- **최초 빌드에서 JDK를 1회 내려받는다**(약 200MB). `~/.gradle/jdks` 에 캐시되므로 이후 빌드에는 영향이 없지만,
  첫인상이 되는 빌드가 느려진다는 점은 실질적 단점이다.
- **오프라인/폐쇄망에서는 실패한다.** 우회 경로를 README에 남긴다:
  사전 설치된 JDK 25 경로를 `org.gradle.java.installations.paths` 로 지정하면 다운로드 없이 그 JDK를 쓴다.
- **빌드 부트스트랩이 외부 서비스(foojay API)에 의존한다.** 최초 1회, JDK가 이미 있으면 호출하지 않는다.
  네트워크 정책이 엄격한 환경에서는 위 우회 경로가 필요하다.
- **IDE가 툴체인을 자동 인식하지 못할 수 있다.** Gradle 임포트 후 프로젝트 SDK를 프로비저닝된 JDK로 지정해야 할 수 있다.
- Gradle 자체는 여전히 실행용 JVM이 필요하다(wrapper를 띄우는 JDK 21로 충분하며, 컴파일은 툴체인 25로 분리 실행된다).

**되돌리는 방법**

`settings.gradle.kts` 의 foojay 플러그인을 제거하고 `auto-download=false` 로 두면 된다.
툴체인 선언(`languageVersion = 25`) 자체는 그대로 유효하며, 그 경우 "로컬에 JDK 25 설치"가 전제조건이 된다.
즉 이 결정은 **편의 계층**이고, 빌드의 정확성은 툴체인 선언이 담보한다.

## 참조

- `docs/DESIGN.md` §11(기술 스택 — Java 25 LTS), §14(CI/CD)
- `settings.gradle.kts`, `gradle.properties`, `gradle/libs.versions.toml`(`java = "25"`)
- `buildSrc/src/main/kotlin/dawnline.java-conventions.gradle.kts`
- 관련 ADR: ADR-001(컨벤션 플러그인·버전 카탈로그), ADR-013(`BP_JVM_VERSION` 이 같은 값을 사용)
