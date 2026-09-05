package com.dawnline.messaging.jdbc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.dawnline.messaging.outbox.RelayLeadership.State;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayNameGeneration;
import org.junit.jupiter.api.DisplayNameGenerator;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;

/**
 * 리더십 판정의 <em>규칙</em>을 본다 — 실제 락의 동작은 {@code OutboxLeaderLockIT} 가 본다.
 *
 * <p>여기서만 볼 수 있는 것이 하나 있다: <strong>이미 락을 쥐고 있으면 획득을 다시 시도하지
 * 않는다.</strong> {@code pg_try_advisory_lock} 은 재진입 가능이라 같은 세션이 다시 부르면 참조
 * 수가 쌓이고 같은 수만큼 unlock 해야 풀린다. 100ms 마다 도는 호출이라 그 수는 하루면 86만이 된다.
 * 진짜 DB 로는 이 실수가 <em>보이지 않는다</em> — 리더는 계속 리더이고, 내려올 때에야 안 풀린다.
 */
@DisplayNameGeneration(DisplayNameGenerator.ReplaceUnderscores.class)
class AdvisoryLockRelayLeadershipTest {

    private final DataSource dataSource = mock(DataSource.class);
    private final Connection connection = mock(Connection.class);
    private final Statement plainStatement = mock(Statement.class);
    private final PreparedStatement holdsStatement = mock(PreparedStatement.class);
    private final PreparedStatement acquireStatement = mock(PreparedStatement.class);
    private final ResultSet holdsRows = mock(ResultSet.class);
    private final ResultSet acquireRows = mock(ResultSet.class);

    private AdvisoryLockRelayLeadership leadership;

    @BeforeEach
    void setUp() throws SQLException {
        when(dataSource.getConnection()).thenReturn(connection);
        when(connection.isClosed()).thenReturn(false);
        when(connection.createStatement()).thenReturn(plainStatement);
        when(connection.prepareStatement(contains("pg_locks"))).thenReturn(holdsStatement);
        when(connection.prepareStatement(contains("pg_try_advisory_lock"))).thenReturn(acquireStatement);
        when(holdsStatement.executeQuery()).thenReturn(holdsRows);
        when(acquireStatement.executeQuery()).thenReturn(acquireRows);
        when(holdsRows.next()).thenReturn(true);
        when(acquireRows.next()).thenReturn(true);
        leadership = new AdvisoryLockRelayLeadership(dataSource, "order-service");
    }

    @Test
    void 이미_쥐고_있으면_획득을_다시_시도하지_않는다() throws SQLException {
        when(holdsRows.getLong(1)).thenReturn(1L);

        assertThat(leadership.lead()).isEqualTo(State.LEADER);
        assertThat(leadership.lead()).isEqualTo(State.LEADER);
        assertThat(leadership.lead()).isEqualTo(State.LEADER);

        verify(connection, never()).prepareStatement(contains("pg_try_advisory_lock"));
    }

    @Test
    void 쥐고_있지_않으면_획득을_시도하고_성공하면_리더다() throws SQLException {
        when(holdsRows.getLong(1)).thenReturn(0L);
        when(acquireRows.getBoolean(1)).thenReturn(true);

        assertThat(leadership.lead()).isEqualTo(State.LEADER);

        // 키는 (네임스페이스, 서비스명 해시) 두 정수다. 두 인스턴스가 같은 키를 써야 조정이 된다.
        verify(acquireStatement).setInt(1, AdvisoryLockRelayLeadership.NAMESPACE);
        verify(acquireStatement).setInt(2, leadership.lockKey());
    }

    @Test
    void 획득에_실패하면_팔로워다() throws SQLException {
        when(holdsRows.getLong(1)).thenReturn(0L);
        when(acquireRows.getBoolean(1)).thenReturn(false);

        assertThat(leadership.lead()).isEqualTo(State.FOLLOWER);
    }

    @Test
    void 판정이_실패하면_팔로워가_아니라_판정불가다() throws SQLException {
        // FOLLOWER 로 접으면 대시보드에서 "다른 인스턴스가 일하는 중" 과 "DB 가 안 보인다" 가
        // 같은 값이 된다. 발행을 멈추는 결정은 같아도 봐야 할 곳이 정반대다.
        when(holdsStatement.executeQuery()).thenThrow(new SQLException("연결이 끊겼습니다"));

        assertThat(leadership.lead()).isEqualTo(State.UNKNOWN);
    }

    @Test
    void 판정이_실패하면_세션을_버리고_다음_사이클에_새로_연다() throws SQLException {
        Connection second = mock(Connection.class);
        when(second.isClosed()).thenReturn(false);
        when(second.createStatement()).thenReturn(mock(Statement.class));
        when(second.prepareStatement(contains("pg_locks"))).thenReturn(holdsStatement);
        when(dataSource.getConnection()).thenReturn(connection, second);
        when(holdsStatement.executeQuery())
                .thenThrow(new SQLException("연결이 끊겼습니다"))
                .thenReturn(holdsRows);
        when(holdsRows.getLong(1)).thenReturn(1L);

        assertThat(leadership.lead()).isEqualTo(State.UNKNOWN);
        assertThat(leadership.lead()).as("재연결하지 않으면 영원히 UNKNOWN 이다").isEqualTo(State.LEADER);

        verify(connection).close();
        verify(dataSource, times(2)).getConnection();
    }

    @Test
    void 세션을_놓기_전에_락을_푼다() throws SQLException {
        // 커넥션이 살아 있는 채로 풀에 돌아가면 락도 함께 돌아간다 — 그 커넥션을 다음에 빌리는
        // 트랜잭션이 락을 들고 다니고 아무도 풀지 않는다. 이 저장소에서 advisory lock 이 만드는
        // 유일한 함정이다.
        when(holdsRows.getLong(1)).thenReturn(1L);
        leadership.lead();

        leadership.stepDown();

        // 순서가 곧 성질이다: 닫기 전에 풀어야 의미가 있다.
        InOrder order = inOrder(plainStatement, connection);
        order.verify(plainStatement).execute(contains("pg_advisory_unlock_all")); // 열 때 비우기
        order.verify(plainStatement).execute(contains("pg_advisory_unlock_all")); // 놓기 전 해제
        order.verify(connection).close();
    }

    @Test
    void 열_때도_비운다() throws SQLException {
        // 풀에서 온 커넥션이 이전 사용자의 advisory lock 을 들고 있으면 "이미 리더" 로 오판한다.
        when(holdsRows.getLong(1)).thenReturn(1L);

        leadership.lead();

        verify(plainStatement).execute(contains("pg_advisory_unlock_all"));
    }

    @Test
    void 서비스가_다르면_키가_다르다() {
        assertThat(new AdvisoryLockRelayLeadership(dataSource, "order-service").lockKey())
                .isNotEqualTo(new AdvisoryLockRelayLeadership(dataSource, "dispatch-service").lockKey());
    }

    @Test
    void 키는_양수다() {
        // pg_locks.objid 는 oid(부호 없는 32비트)다. 음수를 넣으면 조회 술어와 획득 인자가
        // 서로 다른 값으로 보일 여지가 생긴다.
        assertThat(leadership.lockKey()).isNotNegative();
    }
}
