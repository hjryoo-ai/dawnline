package com.dawnline.tracking.application;

import com.dawnline.tracking.application.port.in.RecordScanUseCase;
import com.dawnline.tracking.domain.TrackingErrorCode;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.dao.PessimisticLockingFailureException;

/**
 * 스캔이 다른 쓰기와 겹치면 <strong>새 트랜잭션으로</strong> 다시 한다 — 세 번 뒤에는 409 {@code shipment-contended} (DESIGN.md §5.4).
 *
 * <h2>왜 재시도가 안전한가</h2>
 * 스캔의 멱등은 상태 머신이 만든다({@link RecordScanUseCase} — 같은 스캔이 다시 오면 {@code STALE}). 실패한 시도는 트랜잭션째
 * 되돌아갔으므로 다시 하면 <em>지금의</em> 행 위에서 처음부터 판정한다 — 개정이 옮긴 자리, 다른 스캔이 민 ETA 를 다시 읽는다.
 *
 * <h2>왜 유스케이스 밖인가</h2>
 * 재시도는 트랜잭션의 <strong>바깥</strong>에서만 뜻이 있다. {@link RecordScanService} 의 {@code @Transactional} 안에서 다시 하면 이미
 * 실패로 표시된 같은 트랜잭션을 다시 쓰는 것이다. 그래서 이 클래스는 트랜잭션을 열지 않고, 부를 때마다 위임이 새 트랜잭션을 연다.
 *
 * <h2>무엇을 다시 하는가</h2>
 * 둘이다. {@link OptimisticLockingFailureException} — JPA 의 낙관적 락 예외는 어댑터({@code @Repository} 번역)와 커밋
 * ({@code JpaTransactionManager})에서 이 타입으로 모인다. 그리고 {@link PessimisticLockingFailureException} — 교착의 패자(PostgreSQL
 * 40P01 은 {@code CannotAcquireLockException} 으로 온다, 관측)와 잠금 실패다. PostgreSQL 이 「다시 하라」고 말하는 부류이고 스캔은
 * 멱등이다. 다만 뒤의 것은 <strong>안전망</strong>이다 — 교착을 없애는 것은 쓰기 계층(라우트 행 → {@code shipments},
 * [ADR-070](docs/adr/ADR-070-tracking-writes-lock-the-route-first.md))이고, 교착이 다시 보이면 그 순서를 어긴 쓰기 경로를 찾는다.
 * 그 밖의 예외는 다시 해도 같은 답이라 그대로 던진다.
 */
public final class ContendedScanRetry implements RecordScanUseCase {

    /** 시도 횟수 — 첫 시도 포함. 첫 {@code peak-day} 의 겹침은 2건이었고, 세 번을 연달아 지는 것은 겹침이 아니라 경합의 신호다. */
    public static final int ATTEMPTS = 3;

    private static final Logger log = LoggerFactory.getLogger(ContendedScanRetry.class);

    private final RecordScanUseCase delegate;

    /**
     * @param delegate 트랜잭션을 여는 스캔 유스케이스
     */
    public ContendedScanRetry(RecordScanUseCase delegate) {
        this.delegate = Objects.requireNonNull(delegate, "delegate");
    }

    @Override
    public ScanResult record(ScanCommand command) {
        Objects.requireNonNull(command, "command");
        for (int attempt = 1; ; attempt++) {
            try {
                return delegate.record(command);
            } catch (OptimisticLockingFailureException | PessimisticLockingFailureException contended) {
                if (attempt >= ATTEMPTS) {
                    log.warn("스캔이 다른 쓰기와 {}번 겹쳐 409 로 답한다. routeId={}, stopSeq={}, type={}",
                            attempt, command.routeId(), command.stopSeq(), command.type());
                    throw TrackingErrorCode.shipmentContended(command.routeId(), command.orderIds(), attempt,
                            contended);
                }
                log.debug("스캔이 다른 쓰기와 겹쳤다 — 다시 한다({}/{}). routeId={}, stopSeq={}",
                        attempt, ATTEMPTS, command.routeId(), command.stopSeq());
            }
        }
    }
}
