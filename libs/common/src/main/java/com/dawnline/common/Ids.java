package com.dawnline.common;

import java.time.Clock;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import java.util.random.RandomGenerator;

/**
 * RFC 9562 UUIDv7 생성기.
 *
 * <p>ID 를 애플리케이션에서 만드는 이유(DESIGN.md §7.1): outbox 레코드와 이벤트 페이로드에
 * 같은 ID 를 써야 하므로 DB 왕복 전에 값을 알아야 한다. PostgreSQL 18 의 {@code uuidv7()} 은 쓰지 않는다.
 *
 * <h2>비트 레이아웃 (RFC 9562 §5.7)</h2>
 * <pre>
 *   0                   1                   2                   3
 *  |     unix_ts_ms (48비트)                     | ver(4) | rand_a(12) |
 *  | var(2) |                 rand_b (62비트)                          |
 * </pre>
 *
 * <h2>같은 밀리초 안에서의 단조 증가</h2>
 * <p>RFC 9562 §6.2 "Method 1 — Fixed-Length Dedicated Counter Bits" 를 채택했다.
 * {@code rand_a} 12비트를 <strong>카운터</strong>로 쓴다.
 * <ul>
 *   <li>시계가 앞으로 간 밀리초: 카운터를 난수로 재시드하되 상위 1비트를 0으로 둔다
 *       (초기값 0..2047). RFC 권고대로 롤오버 여유를 최소 2048회 확보하기 위함이다.</li>
 *   <li>같은 밀리초(또는 시계 역행): 카운터를 +1 한다. 따라서 상위 64비트(msb)가 반드시
 *       증가하므로 생성 순서 = 정렬 순서가 성립한다.</li>
 *   <li>카운터가 4095에서 넘칠 때: 타임스탬프를 1ms 빌려서 증가시키고 카운터를 재시드한다.
 *       (RFC 9562 §6.2 의 "counter rollover guard" — 시간을 뒤로 되돌리지 않는다.)</li>
 *   <li>시계 역행(NTP 조정 등): 마지막 타임스탬프를 유지(freeze)하고 카운터만 증가시켜
 *       단조성을 깨뜨리지 않는다.</li>
 * </ul>
 *
 * <p>{@link UUID#compareTo(UUID)} 는 msb 를 부호 있는 long 으로 비교하는데, unix_ts_ms 의
 * 최상위 비트는 서기 10889년까지 0이므로 msb 는 항상 양수다. 따라서 생성 순서대로 정렬된다.
 * 문자열 표현({@link UUID#toString()}) 의 사전순도 같은 이유로 시간순과 일치한다.
 *
 * <p>CLAUDE.md 불변규칙 12에 따라 {@link Clock} 과 {@link RandomGenerator} 를 주입받는
 * 인스턴스 API 를 제공한다. 같은 seed·같은 Clock 이면 결과가 동일하다(결정론).
 * 편의용 {@link #newId()} 는 시스템 UTC 시계와 {@link ThreadLocalRandom} 을 쓴다.
 *
 * <p>스레드 안전하다. 카운터 갱신과 난수 소비를 같은 락 안에서 수행한다.
 */
public final class Ids {

    /** unix_ts_ms 48비트 마스크. */
    private static final long TIMESTAMP_MASK = 0xFFFF_FFFF_FFFFL;

    /** rand_a 를 카운터로 사용한다 (12비트). */
    private static final int COUNTER_MASK = 0x0FFF;

    /** 카운터 재시드 범위: 0..2047. 상위 1비트를 비워 최소 2048회 증가 여유를 남긴다. */
    private static final int COUNTER_SEED_BOUND = 1 << 11;

    /** version = 7 을 msb 의 48..51 비트에 놓는다. */
    private static final long VERSION_7 = 0x7L << 12;

    /** variant = 0b10 (RFC 9562). lsb 최상위 2비트. */
    private static final long VARIANT_RFC_9562 = 0x8000_0000_0000_0000L;

    /** rand_b 62비트 마스크. */
    private static final long RAND_B_MASK = 0x3FFF_FFFF_FFFF_FFFFL;

    private static final Ids SYSTEM = new Ids(Clock.systemUTC(), ThreadLocalRandomGenerator.INSTANCE);

    private final Clock clock;
    private final RandomGenerator random;
    private final Object lock = new Object();

    private long lastTimestamp = -1L;
    private int counter;

    /**
     * @param clock  타임스탬프 출처 (테스트에서는 고정 Clock 주입)
     * @param random 난수 출처 (테스트에서는 고정 seed 주입 → 결정론)
     */
    public Ids(Clock clock, RandomGenerator random) {
        this.clock = Objects.requireNonNull(clock, "clock");
        this.random = Objects.requireNonNull(random, "random");
    }

    /** 시스템 시계 기반 UUIDv7 (CLAUDE.md 불변규칙 10). */
    public static UUID newId() {
        return SYSTEM.newUuid();
    }

    /** 주입된 Clock·RandomGenerator 로 UUIDv7 을 만든다. */
    public UUID newUuid() {
        long timestamp;
        int sequence;
        long randB;
        synchronized (lock) {
            long now = clock.millis() & TIMESTAMP_MASK;
            if (now > lastTimestamp) {
                lastTimestamp = now;
                counter = random.nextInt(COUNTER_SEED_BOUND);
            } else if (counter >= COUNTER_MASK) {
                // 카운터 소진: 시간을 1ms 빌려 단조성을 유지한다(뒤로 가지 않는다).
                lastTimestamp = (lastTimestamp + 1) & TIMESTAMP_MASK;
                counter = random.nextInt(COUNTER_SEED_BOUND);
            } else {
                counter++;
            }
            timestamp = lastTimestamp;
            sequence = counter;
            randB = random.nextLong();
        }
        long msb = (timestamp << 16) | VERSION_7 | (sequence & COUNTER_MASK);
        long lsb = VARIANT_RFC_9562 | (randB & RAND_B_MASK);
        return new UUID(msb, lsb);
    }

    /**
     * UUIDv7 에서 생성 시각을 되돌린다.
     *
     * @throws IllegalArgumentException UUID 버전이 7이 아닐 때
     */
    public static Instant timestampOf(UUID uuid) {
        Objects.requireNonNull(uuid, "uuid");
        if (uuid.version() != 7) {
            throw new IllegalArgumentException("UUIDv7 이 아닙니다: version=" + uuid.version());
        }
        return Instant.ofEpochMilli(uuid.getMostSignificantBits() >>> 16);
    }

    /**
     * {@link ThreadLocalRandom} 위임 어댑터.
     *
     * <p>{@code ThreadLocalRandom.current()} 를 필드에 보관하면 최초 호출 스레드 밖에서
     * 프로브가 초기화되지 않을 수 있으므로, 매 호출마다 {@code current()} 를 다시 얻는다.
     */
    private enum ThreadLocalRandomGenerator implements RandomGenerator {
        INSTANCE;

        @Override
        public long nextLong() {
            return ThreadLocalRandom.current().nextLong();
        }

        @Override
        public int nextInt(int bound) {
            return ThreadLocalRandom.current().nextInt(bound);
        }
    }
}
