package com.dawnline.benchmark;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;

/**
 * 리포트가 어느 소스에서 나왔는가 (DESIGN.md §6.9 「동결이 보장하는 것과 보장하지 않는 것」).
 *
 * <h2>왜 리포트에 커밋이 필요한가</h2>
 * {@code BaselineFrozenTest} 가 얼리는 것은 {@code BaselineNearestNeighbor} <strong>클래스</strong>다.
 * 그래서 동결은 <em>같은 실행 안의 비교가 공정하다</em>는 것만 보장한다 — 두 전략이 같은
 * {@code StopMerger}·{@code CostModel}·거리 함수를 쓰기 때문이다. <strong>절대 수치는 보장하지
 * 않는다.</strong> 공용 부품이 바뀌면 같은 전략이 다른 수를 낸다. 그래서 리포트끼리 절대 수치를
 * 비교하려면 <strong>커밋이 같은지 먼저 봐야 하고</strong>, 보려면 헤더에 적혀 있어야 한다.
 *
 * <h2>더러운 작업 트리를 따로 적는 이유</h2>
 * 커밋만 적으면 "그 커밋에서 나온 수치" 로 읽힌다. 수정된 트리에서 낸 수치는 <strong>어떤 커밋에도
 * 귀속되지 않는다</strong> — 다시 낼 방법이 없다는 뜻이라, 비교 대상으로 쓰기 전에 보여야 한다.
 *
 * @param commit 짧은 커밋 해시. 알 수 없으면 {@code null}
 * @param clean  작업 트리가 깨끗한가. {@code commit} 이 {@code null} 이면 의미 없다
 */
public record SourceVersion(String commit, boolean clean) {

    /** git 을 쓸 수 없을 때. 부재를 값으로 적는다 — 헤더에서 빠지지 않는다. */
    public static final SourceVersion UNKNOWN = new SourceVersion(null, false);

    /**
     * 현재 작업 디렉터리의 git 상태를 읽는다.
     *
     * <p>실패하면 {@link #UNKNOWN} 이다. 벤치마크가 git 없이도 돌아야 하기 때문이고(컨테이너·
     * 소스 아카이브), 리포트는 그 사실을 그대로 적는다.
     */
    public static SourceVersion detect() {
        String commit = run("git", "rev-parse", "--short", "HEAD");
        if (commit == null || commit.isBlank()) {
            return UNKNOWN;
        }
        String status = run("git", "status", "--porcelain");
        // status 가 null 이면 커밋은 읽혔는데 상태는 못 읽은 것이다. 깨끗하다고 단정하지 않는다.
        return new SourceVersion(commit.trim(), status != null && status.isBlank());
    }

    /** 리포트 헤더에 넣을 표현. */
    public String describe() {
        if (commit == null) {
            return "커밋 `unknown`(git 없음)";
        }
        return clean ? "커밋 `" + commit + "`"
                : "커밋 `" + commit + "` **+ 커밋되지 않은 수정**(이 수치는 재현할 수 없다)";
    }

    private static String run(String... command) {
        try {
            Process process = new ProcessBuilder(command).redirectError(ProcessBuilder.Redirect.DISCARD).start();
            String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            if (!process.waitFor(5, TimeUnit.SECONDS) || process.exitValue() != 0) {
                process.destroy();
                return null;
            }
            return output;
        } catch (IOException e) {
            return null;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return null;
        }
    }
}
