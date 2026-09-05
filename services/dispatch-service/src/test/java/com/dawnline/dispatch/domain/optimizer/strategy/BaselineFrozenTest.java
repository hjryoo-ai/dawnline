package com.dawnline.dispatch.domain.optimizer.strategy;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.DisplayNameGeneration;
import org.junit.jupiter.api.DisplayNameGenerator;
import org.junit.jupiter.api.Test;

/**
 * {@code baseline-nn} 은 <strong>동결</strong>이다 (IMPLEMENTATION_PLAN Phase 3-4 게이트 규칙 1).
 *
 * <h2>왜 수치가 아니라 클래스를 동결하는가</h2>
 * 회귀 게이트는 "기본 전략이 베이스라인보다 비싸면 실패" 다. 그 부등식의 <em>오른쪽</em>이 움직이면
 * 게이트는 아무것도 재지 않는다 — 베이스라인을 조금 나쁘게 만들면 어떤 전략도 통과한다. 그리고
 * 그렇게 통과한 뒤에는 <strong>그때 무엇과 비교했는지가 사라진다.</strong>
 *
 * <p>비용 수치를 고정하는 방법도 있지만 그것은 데이터셋마다 다시 고정해야 하고, 데이터셋이 하나만
 * 늘어도 빈 칸이 생긴다. 동결의 대상은 결과가 아니라 <strong>결과를 만드는 코드</strong>다.
 *
 * <h2>이 테스트가 빨개졌다면</h2>
 * 둘 중 하나만 한다.
 * <ol>
 *   <li>바꾸지 않는다. 개선은 <em>새 전략</em>으로 등록한다 — 그것이 §6.6 의 전략 레지스트리가
 *       있는 이유다.</li>
 *   <li>정말 바꿔야 하면 {@code docs/benchmarks/} 에 <strong>재기준(re-baseline) 기록</strong>을
 *       남기고 — 무엇을 왜 바꿨는지, 바꾸기 전후의 수치 — 세 데이터셋의 표를 다시 낸 뒤 아래
 *       해시를 갱신한다. 해시만 갱신하는 커밋은 이 규칙을 지우는 커밋이다.</li>
 * </ol>
 *
 * <h2>주석까지 센다</h2>
 * 정규화는 줄바꿈뿐이다(CRLF → LF). 공백·주석을 무시하도록 만들면 "동작은 같다" 는 판단이
 * 테스트 안으로 들어오고, 그 판단은 사람이 PR 에서 해야 한다.
 */
@DisplayNameGeneration(DisplayNameGenerator.ReplaceUnderscores.class)
@DisplayName("BaselineFrozenTest — 게이트의 기준선은 코드 수준으로 동결이다")
class BaselineFrozenTest {

    /** 게이트가 켜진 날(2026-09-05, medium) 의 {@code BaselineNearestNeighbor.java} SHA-256. */
    private static final String FROZEN_SHA256 =
            "1a11caec43a487a5d33e4f9b7afa56d0be7d20cc656f405de0974126e91b9080";

    private static final String SOURCE =
            "src/main/java/com/dawnline/dispatch/domain/optimizer/strategy/"
                    + "BaselineNearestNeighbor.java";

    @Test
    void 베이스라인_전략의_소스가_바뀌지_않았다() {
        Path source = locate();
        // 전제를 첫 어설션으로 말한다 (CLAUDE.md 「코딩 컨벤션」). 파일을 못 찾으면 이 테스트는
        // 아무것도 검사하지 않으면서 조용히 통과할 수 있다 — 동결이 그렇게 풀리면 아무도 모른다.
        assertThat(source)
                .as("작업 디렉터리 %s 에서 %s 를 찾지 못했다", Path.of("").toAbsolutePath(), SOURCE)
                .exists();

        assertThat(sha256(source))
                .as("""
                        baseline-nn 은 게이트의 기준선이라 동결이다 (Phase 3-4 게이트 규칙 1).
                        개선은 새 전략으로 등록하고, 정말 기준을 바꿔야 한다면 먼저
                        docs/benchmarks/ 에 재기준 기록을 남긴 뒤 이 해시를 갱신한다.""")
                .isEqualTo(FROZEN_SHA256);
    }

    /** Gradle 은 모듈 디렉터리에서 테스트를 돌리지만, 저장소 루트에서 돌 때도 찾아 준다. */
    private static Path locate() {
        Path fromModule = Path.of(SOURCE);
        return Files.exists(fromModule) ? fromModule
                : Path.of("services", "dispatch-service").resolve(SOURCE);
    }

    private static String sha256(Path file) {
        try {
            byte[] normalized = Files.readString(file, StandardCharsets.UTF_8)
                    .replace("\r\n", "\n").getBytes(StandardCharsets.UTF_8);
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(normalized));
        } catch (IOException e) {
            throw new UncheckedIOException("베이스라인 소스를 읽을 수 없습니다: " + file.toAbsolutePath(), e);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 이 없습니다", e);
        }
    }
}
