package com.dawnline.ops.adapter.out.persistence;

import com.dawnline.ops.application.port.out.Patch;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.StringJoiner;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * 패치 하나를 {@code UPDATE} 한 문장으로 (ADR-051 결정 2).
 *
 * <p>{@code SET} 에 적히는 칸은 패치에 있는 칸뿐이다. 칸 이름은 칸 enum 의 이름을 소문자로 바꾼
 * 것이고, 사용자 입력이 아니라 enum 상수에서만 온다.
 *
 * <h2>0 행 갱신은 조용히 넘어가지 않는다</h2>
 * 호출자는 잠근 행에만 쓴다 — 그러므로 갱신이 0 행이면 그것은 순서가 아니라 코드의 결함이고,
 * 예외로 알린다. ADR-051 이 막으려던 것이 정확히 「0 행 갱신이 예외 없이 성공하는」 모양이다.
 */
final class PatchStatements {

    private PatchStatements() {
    }

    /**
     * @param jdbc     같은 트랜잭션에 참여하는 템플릿
     * @param table    표
     * @param keyName  키 칸
     * @param key      키
     * @param patch    적을 칸
     * @param extraSet 패치 밖에서 늘 적는 칸 ({@code updated_at} 같은 프로젝션의 기록). 없으면 빈 맵
     * @param <C>      칸 enum
     */
    static <C extends Enum<C>> void apply(JdbcTemplate jdbc, String table, String keyName, UUID key,
            Patch<C> patch, Map<String, Object> extraSet) {
        if (patch.isEmpty()) {
            return;
        }
        StringJoiner set = new StringJoiner(", ");
        List<Object> args = new ArrayList<>();
        for (Map.Entry<C, Patch.Write> write : patch.writes().entrySet()) {
            String column = write.getKey().name().toLowerCase(Locale.ROOT);
            set.add(write.getValue().ifAbsent()
                    ? column + " = COALESCE(" + column + ", ?)"
                    : column + " = ?");
            args.add(bindable(write.getValue().value()));
        }
        for (Map.Entry<String, Object> extra : extraSet.entrySet()) {
            set.add(extra.getKey() + " = ?");
            args.add(bindable(extra.getValue()));
        }
        args.add(key);
        int updated = jdbc.update("UPDATE " + table + " SET " + set + " WHERE " + keyName + " = ?",
                args.toArray());
        if (updated != 1) {
            throw new IllegalStateException("%s 의 %s=%s 를 갱신하지 못했다(%d 행) — 잠그지 않은 행에 썼다"
                    .formatted(table, keyName, key, updated));
        }
    }

    /** TIMESTAMPTZ 에는 OffsetDateTime 으로 넘긴다 — 드라이버가 Instant 를 직접 받지 않는다. */
    static Object bindable(Object value) {
        return value instanceof Instant instant ? instant.atOffset(ZoneOffset.UTC) : value;
    }
}
