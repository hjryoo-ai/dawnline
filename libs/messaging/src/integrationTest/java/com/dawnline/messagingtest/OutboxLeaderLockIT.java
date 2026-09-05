package com.dawnline.messagingtest;

import static org.assertj.core.api.Assertions.assertThat;

import com.dawnline.messaging.jdbc.AdvisoryLockRelayLeadership;
import com.dawnline.messaging.outbox.RelayLeadership;
import com.dawnline.messaging.outbox.RelayLeadership.State;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayNameGeneration;
import org.junit.jupiter.api.DisplayNameGenerator;
import org.junit.jupiter.api.Test;
import org.postgresql.ds.PGSimpleDataSource;

/**
 * 릴레이 리더 락 — 실제 PostgreSQL 로 (§4.4, ADR-027 후속 정정 2026-09-05).
 *
 * <p>단위 테스트는 <em>판정 규칙</em>을 본다(이미 쥐고 있으면 다시 획득하지 않는다 등). 여기서
 * 보는 것은 그 아래다: advisory lock 이 정말로 두 세션 중 하나만 통과시키는가, <strong>세션이
 * 죽으면 서버가 락을 푸는가</strong>, 남의 세션은 그것을 건드릴 수 없는가. 셋 다 목으로는 하나도
 * 검증되지 않는다.
 *
 * <p>세 번째 항목이 이 정정의 핵심이다. Redis 락에서는 같은 성질을 <em>TTL 30초</em>가 만들었고,
 * 테스트도 "TTL 이 실제로 걸린다" 를 보는 것이 최선이었다 — 리더가 죽은 뒤 실제로 30초 안에
 * 교체되는지는 30초를 기다리지 않으면 볼 수 없었다. advisory lock 은 세션 종료가 곧 해제라서
 * <strong>기다리지 않고 그대로 확인한다.</strong>
 *
 * <p>스프링 컨텍스트를 띄우지 않는다 — 검증 대상이 배선이 아니라 DB 와의 상호작용이다. 배선은
 * 이 모듈의 다른 IT 들이 이미 증명한다(리더가 아니면 아무것도 발행되지 않으므로,
 * {@code OutboxRelayIT} 가 통과한다는 것 자체가 리더 락이 붙어 있고 동작한다는 뜻이다).
 */
@DisplayNameGeneration(DisplayNameGenerator.ReplaceUnderscores.class)
class OutboxLeaderLockIT extends MessagingIntegrationTestBase {

    private static final String SERVICE = "leader-lock-it";

    private final List<AdvisoryLockRelayLeadership> opened = new ArrayList<>();

    @AfterEach
    void tearDown() {
        opened.forEach(AdvisoryLockRelayLeadership::close);
        opened.clear();
    }

    @Test
    void 두_인스턴스_중_하나만_리더가_된다() {
        // 이 락이 존재하는 이유다. 둘 다 리더면 같은 partition_key 의 행이 두 곳에서 나가고
        // §4.5 의 키 단위 순서가 깨진다 — SKIP LOCKED 로는 막지 못하는 종류의 파손이다.
        RelayLeadership first = leadership();
        RelayLeadership second = leadership();

        assertThat(first.lead()).isEqualTo(State.LEADER);
        assertThat(second.lead()).isEqualTo(State.FOLLOWER);
    }

    @Test
    void 리더는_반복_호출에도_계속_리더다() {
        // 폴링이 100ms 마다 부른다. 여기서 값이 흔들리면 발행이 100ms 마다 켜졌다 꺼진다.
        RelayLeadership leader = leadership();

        assertThat(leader.lead()).isEqualTo(State.LEADER);
        assertThat(leader.lead()).isEqualTo(State.LEADER);
        assertThat(leader.lead()).isEqualTo(State.LEADER);
    }

    @Test
    void 반복_호출이_락_참조_수를_쌓지_않는다() throws SQLException {
        // pg_try_advisory_lock 은 재진입 가능이라 이미 쥔 세션이 다시 부르면 참조 수가 쌓이고,
        // 같은 수만큼 unlock 해야 풀린다. 그러면 stepDown 한 번으로는 안 풀려서 다음 인스턴스가
        // 영원히 이어받지 못한다 — 폴링 주기를 생각하면 조용히 치명적이다.
        RelayLeadership leader = leadership();
        for (int i = 0; i < 20; i++) {
            assertThat(leader.lead()).isEqualTo(State.LEADER);
        }

        assertThat(lockRowCount())
                .as("advisory lock 은 세션당 한 행이어야 한다 — 참조 수가 쌓이면 여러 번 잡은 것이다")
                .isEqualTo(1);
    }

    @Test
    void 리더가_내려오면_다음_인스턴스가_이어받는다() {
        RelayLeadership leaving = leadership();
        RelayLeadership waiting = leadership();
        assertThat(leaving.lead()).isEqualTo(State.LEADER);
        assertThat(waiting.lead()).isEqualTo(State.FOLLOWER);

        leaving.stepDown();

        assertThat(waiting.lead()).isEqualTo(State.LEADER);
    }

    @Test
    void 리더의_세션이_죽으면_서버가_락을_푼다() throws SQLException {
        // 이 정정이 TTL 을 없앤 근거다. Redis 락에서는 리더가 죽으면 TTL(30초)만큼 발행이
        // 멈췄다. 여기서는 세션이 사라지는 순간 락이 풀린다 — 기다림이 아니라 성질이다.
        RelayLeadership dying = leadership();
        RelayLeadership waiting = leadership();
        assertThat(dying.lead()).isEqualTo(State.LEADER);
        assertThat(waiting.lead()).as("전제: 지금은 팔로워여야 한다").isEqualTo(State.FOLLOWER);

        int killed = terminateLockHolders();
        assertThat(killed).as("죽일 백엔드를 못 찾으면 아래 어설션은 아무것도 검사하지 않는다").isEqualTo(1);

        assertThat(waiting.lead())
                .as("TTL 을 기다리지 않고 바로 이어받아야 한다")
                .isEqualTo(State.LEADER);
    }

    @Test
    void 팔로워는_리더의_락을_풀_수_없다() {
        // 팔로워의 stepDown 은 자기 세션만 비운다. 남의 세션 락은 애초에 보이지 않는다 —
        // Redis 구현에서 비교 후 삭제(Lua)로 만들어야 했던 성질이 여기서는 공짜다.
        RelayLeadership leader = leadership();
        RelayLeadership follower = leadership();
        assertThat(leader.lead()).isEqualTo(State.LEADER);
        assertThat(follower.lead()).isEqualTo(State.FOLLOWER);

        follower.stepDown();

        assertThat(leader.lead())
                .as("팔로워의 stepDown 이 리더를 끌어내리면 안 된다")
                .isEqualTo(State.LEADER);
    }

    @Test
    void DB_에_닿지_못하면_팔로워가_아니라_판정불가다() {
        // 발행을 멈추는 결정은 팔로워와 같지만 봐야 할 곳이 정반대다(정상 대 DB 장애).
        // 이 상태에서는 발행할 outbox 행도 읽지 못한다 — 그래서 딜레마가 없다.
        PGSimpleDataSource dead = new PGSimpleDataSource();
        dead.setUrl("jdbc:postgresql://127.0.0.1:1/dawnline_messaging");
        dead.setUser(username());
        dead.setPassword(password());

        try (AdvisoryLockRelayLeadership leadership =
                new AdvisoryLockRelayLeadership(dead, SERVICE)) {
            assertThat(leadership.lead()).isEqualTo(State.UNKNOWN);
        }
    }

    private RelayLeadership leadership() {
        AdvisoryLockRelayLeadership leadership =
                new AdvisoryLockRelayLeadership(dataSource(), SERVICE);
        opened.add(leadership);
        return leadership;
    }

    /**
     * 인스턴스마다 <strong>따로</strong> 만든다 — 두 인스턴스가 같은 커넥션을 쓰면 advisory lock 은
     * 언제나 같은 세션에 걸려 "둘 중 하나만" 을 검사하지 못한다.
     */
    private static PGSimpleDataSource dataSource() {
        PGSimpleDataSource dataSource = new PGSimpleDataSource();
        dataSource.setUrl(jdbcUrl());
        dataSource.setUser(username());
        dataSource.setPassword(password());
        return dataSource;
    }

    /** 이 서비스 키로 잡힌 advisory lock 행 수. 참조 수가 쌓이면 1보다 커진다. */
    private int lockRowCount() throws SQLException {
        try (Connection connection = admin();
                PreparedStatement statement = connection.prepareStatement("""
                        SELECT count(*) FROM pg_locks
                         WHERE locktype = 'advisory' AND classid = ? AND objid = ? AND objsubid = 2
                        """)) {
            statement.setInt(1, AdvisoryLockRelayLeadership.NAMESPACE);
            statement.setInt(2, lockKey());
            try (ResultSet rows = statement.executeQuery()) {
                return rows.next() ? rows.getInt(1) : 0;
            }
        }
    }

    /** 이 키의 락을 쥔 백엔드를 죽인다. 프로세스가 사라지는 것을 흉내 낸다. */
    private int terminateLockHolders() throws SQLException {
        try (Connection connection = admin();
                PreparedStatement statement = connection.prepareStatement("""
                        SELECT pg_terminate_backend(pid) FROM pg_locks
                         WHERE locktype = 'advisory' AND granted
                           AND classid = ? AND objid = ? AND objsubid = 2
                        """)) {
            statement.setInt(1, AdvisoryLockRelayLeadership.NAMESPACE);
            statement.setInt(2, lockKey());
            int killed = 0;
            try (ResultSet rows = statement.executeQuery()) {
                while (rows.next()) {
                    killed++;
                }
            }
            return killed;
        }
    }

    private static int lockKey() {
        return SERVICE.hashCode() & 0x7FFF_FFFF;
    }

    private static Connection admin() throws SQLException {
        return DriverManager.getConnection(jdbcUrl(), username(), password());
    }
}
