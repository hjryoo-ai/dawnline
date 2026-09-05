package com.dawnline.messaging.jdbc;

import com.dawnline.messaging.outbox.RelayLeadership;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Objects;
import javax.sql.DataSource;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * {@link RelayLeadership} 의 PostgreSQL 구현 — {@code pg_try_advisory_lock(classid, objid)}
 * (DESIGN.md §4.4, [ADR-027] 후속 정정 2026-09-05).
 *
 * <h2>왜 DB 인가</h2>
 * 릴레이 리더 선출은 <strong>서비스 내부</strong> 조정이다. 조정에 참여하는 것은 한 서비스의 릴레이
 * 인스턴스들뿐이고, 그들은 정의상 같은 DB 를 공유한다(불변규칙 3). 그 DB 는 다른 아무 DB 가 아니라
 * <strong>자기가 비우고 있는 outbox 가 들어 있는 바로 그 DB</strong> 다. 그래서
 *
 * <ul>
 *   <li><strong>TTL 도 갱신도 없다.</strong> 락은 임차(lease)가 아니라 세션의 존재 그 자체이고,
 *       세션이 죽으면 서버가 즉시 푼다. 임차라면 필요했을 것들 — 갱신 주기, 갱신이 죽은 상태,
 *       TTL 과 배치 소요의 대소 관계, 두 시계가 같은 속도로 간다는 가정 — 이 전부 없다.</li>
 *   <li><strong>fail-open / fail-closed 딜레마가 없다.</strong> DB 가 죽으면 리더십을 판정할 수
 *       없지만, 그때는 <em>발행할 행도 읽을 수 없다.</em> 두 사건이 하나이므로 "멈출 것인가
 *       진행할 것인가" 라는 질문이 성립하지 않는다. 조정자를 서비스 밖(Redis)에 두었을 때는
 *       성립했다 — 조정자만 죽고 저장소는 살아 있는 상태가 존재했기 때문이다.</li>
 * </ul>
 *
 * <h2>전용 장수 세션</h2>
 * advisory lock 은 <strong>세션 수준</strong>이다. 커넥션을 풀에 반납하면 락은 <em>반납된
 * 커넥션에 남고</em>, 그 커넥션을 다음에 빌리는 트랜잭션이 락을 들고 다니며 아무도 풀지 않는다.
 * 그래서 이 클래스는 커넥션 하나를 열어 <strong>계속 들고 있는다.</strong> 릴레이를 켜는 서비스는
 * 커넥션 풀 크기에 +1 이 필요하다(Hikari 의 {@code maxLifetime} 은 사용 중인 커넥션을 회수하지
 * 않으므로 이 세션은 은퇴하지 않는다).
 *
 * <p>같은 이유로 세션을 <strong>버릴 때는 닫기 전에 푼다</strong>({@code pg_advisory_unlock_all}).
 * 커넥션이 살아 있는 채로 풀에 돌아가면 락도 함께 돌아가기 때문이다. 세션이 이미 죽었으면 그
 * 호출도 실패하지만, 그때는 서버가 이미 풀었다.
 *
 * <h2>리더 여부는 매 사이클 DB 에서 도출한다</h2>
 * 메모리 플래그를 두지 않는다 — 플래그와 실제 락 상태가 어긋나는 창이 곧 §4.5 가 깨지는 창이다.
 * 매 폴링마다 {@code pg_locks} 에 "이 백엔드가 그 키를 쥐고 있는가" 를 묻고, 아니면 획득을
 * 시도한다.
 *
 * <p><strong>순서가 중요하다 — 획득을 먼저 시도하면 안 된다.</strong>
 * {@code pg_try_advisory_lock} 은 재진입 가능이라 이미 쥔 세션이 다시 부르면 <em>참조 수가
 * 쌓이고</em> 같은 수만큼 unlock 해야 풀린다. 100ms 마다 도는 호출이므로 그 수는 금방
 * 수십만이 된다.
 */
public class AdvisoryLockRelayLeadership implements RelayLeadership, AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(AdvisoryLockRelayLeadership.class);

    /**
     * 이 저장소의 advisory lock 네임스페이스 — {@code pg_locks.classid} 에 그대로 보인다.
     *
     * <p>{@code 0x4441574E} = ASCII {@code "DAWN"}. 운영자가 {@code pg_locks} 를 눈으로 읽을 때
     * "이 락은 우리 것" 임을 알아보게 하려는 것이고, 값 자체에 의미는 없다.
     */
    public static final int NAMESPACE = 0x4441574E;

    /**
     * "이 백엔드가 그 키를 쥐고 있는가".
     *
     * <p>{@code objsubid = 2} 는 <strong>두 정수 키</strong>로 잡은 advisory lock 이라는 뜻이다
     * (bigint 한 개로 잡으면 1 이고 classid/objid 에 상하위 절반이 들어간다). 이 조건이 없으면
     * 우연히 같은 상하위 절반을 가진 bigint 락을 우리 것으로 오인할 수 있다.
     */
    private static final String HOLDS_LOCK = """
            SELECT count(*) FROM pg_locks
             WHERE locktype = 'advisory' AND granted AND pid = pg_backend_pid()
               AND classid = ? AND objid = ? AND objsubid = 2
            """;

    private static final String TRY_ACQUIRE = "SELECT pg_try_advisory_lock(?, ?)";

    /** 세션을 놓기 전에 비운다. 이 세션의 advisory lock 은 우리 것뿐이다. */
    private static final String RELEASE_ALL = "SELECT pg_advisory_unlock_all()";

    private final DataSource dataSource;
    private final String service;
    private final int key;

    /**
     * 리더십을 담는 전용 세션. {@code null} 이면 아직 열지 않았거나 직전에 버린 것이다.
     *
     * <p>{@code lead()} 는 스케줄러 스레드 하나가 부르고 {@code stepDown()} 은 종료 스레드가
     * 부른다. 두 경로가 이 필드를 만지므로 메서드를 {@code synchronized} 로 둔다 — 100ms 에
     * 한 번 도는 호출이라 경합이 없다.
     */
    @Nullable
    private Connection session;

    /**
     * @param dataSource 이 서비스의 데이터소스. <strong>outbox 가 들어 있는 그 DB</strong> 여야
     *                   한다 — 다른 DB 를 주면 락은 걸리지만 아무것도 조정하지 못한다
     * @param service    서비스 이름 (§9.1 의 {@code service} 태그와 같은 값). 락 키를 만든다
     */
    public AdvisoryLockRelayLeadership(DataSource dataSource, String service) {
        this.dataSource = Objects.requireNonNull(dataSource, "dataSource");
        this.service = Objects.requireNonNull(service, "service");
        // 서비스마다 DB 가 다르고(불변규칙 3) advisory lock 공간은 데이터베이스 단위라, 사실
        // 키가 고정 상수여도 충돌하지 않는다. 그래도 이름에서 유도하는 이유는 pg_locks 를 읽는
        // 사람이 "어느 서비스의 락인가" 를 물을 자리를 남기기 위해서다.
        this.key = service.hashCode() & 0x7FFF_FFFF;
    }

    @Override
    public synchronized State lead() {
        try {
            Connection current = session();
            return holdsLock(current) || acquire(current) ? State.LEADER : State.FOLLOWER;
        } catch (SQLException | RuntimeException e) {
            // 판정할 수 없다. FOLLOWER 로 접지 않는다 — 봐야 할 곳이 다르다(정상 대 DB 장애).
            log.warn("릴레이 리더십을 판정할 수 없습니다. 발행을 멈추고 다음 폴링에 다시 붙습니다. "
                    + "service={} key={}", service, key, e);
            discardSession();
            return State.UNKNOWN;
        }
    }

    @Override
    public synchronized void stepDown() {
        discardSession();
    }

    /**
     * 세션을 놓는다. 스프링이 빈 소멸 시 부른다(추론된 destroy 메서드).
     *
     * <p>{@link com.dawnline.messaging.outbox.OutboxRelay} 도 종료 시 {@link #stepDown()} 을 부르므로 두 번 불릴 수 있다.
     * {@link #discardSession()} 은 두 번째 호출에서 아무것도 하지 않는다.
     */
    @Override
    public synchronized void close() {
        discardSession();
    }

    /** 이 인스턴스가 쓰는 advisory lock 키. 테스트와 운영 조회용({@code pg_locks.objid}). */
    public int lockKey() {
        return key;
    }

    @Override
    public String toString() {
        return "AdvisoryLockRelayLeadership(service=%s, key=%d)".formatted(service, key);
    }

    /**
     * 살아 있는 전용 세션. 없거나 닫혔으면 새로 연다.
     *
     * <p>연 직후 {@code pg_advisory_unlock_all()} 을 부른다. 풀에서 온 커넥션이 <em>이전
     * 사용자의</em> advisory lock 을 들고 있으면 우리가 "이미 리더" 로 오판하기 때문이다. 그럴
     * 일이 없어야 하지만, 그럴 일이 없다는 것을 확인하는 비용이 왕복 한 번이다.
     */
    private Connection session() throws SQLException {
        Connection current = this.session;
        if (current != null && !current.isClosed()) {
            return current;
        }
        Connection fresh = dataSource.getConnection();
        try {
            fresh.setAutoCommit(true);
            try (Statement statement = fresh.createStatement()) {
                statement.execute(RELEASE_ALL);
            }
        } catch (SQLException e) {
            closeQuietly(fresh);
            throw e;
        }
        this.session = fresh;
        return fresh;
    }

    private boolean holdsLock(Connection current) throws SQLException {
        try (PreparedStatement statement = current.prepareStatement(HOLDS_LOCK)) {
            statement.setInt(1, NAMESPACE);
            statement.setInt(2, key);
            try (ResultSet rows = statement.executeQuery()) {
                return rows.next() && rows.getLong(1) > 0L;
            }
        }
    }

    private boolean acquire(Connection current) throws SQLException {
        try (PreparedStatement statement = current.prepareStatement(TRY_ACQUIRE)) {
            statement.setInt(1, NAMESPACE);
            statement.setInt(2, key);
            try (ResultSet rows = statement.executeQuery()) {
                return rows.next() && rows.getBoolean(1);
            }
        }
    }

    /** 락을 풀고 세션을 놓는다. 예외를 밖으로 내지 않는다 — 종료 경로에서도 불린다. */
    private void discardSession() {
        Connection current = this.session;
        this.session = null;
        if (current == null) {
            return;
        }
        try (Connection closing = current) {
            try (Statement statement = closing.createStatement()) {
                statement.execute(RELEASE_ALL);
            } catch (SQLException e) {
                // 세션이 이미 끊겼으면 서버가 락을 풀었다. 살아 있는데 실패한 경우만 문제인데,
                // 그때는 아래 close() 가 커넥션을 풀에서 폐기하도록 만든다.
                log.debug("advisory lock 해제에 실패했습니다. service={} key={}", service, key, e);
            }
        } catch (SQLException e) {
            log.debug("리더십 세션을 닫지 못했습니다. service={}", service, e);
        }
    }

    private static void closeQuietly(Connection connection) {
        try {
            connection.close();
        } catch (SQLException e) {
            log.debug("커넥션을 닫지 못했습니다.", e);
        }
    }
}
