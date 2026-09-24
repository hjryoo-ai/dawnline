package com.dawnline.ops.application.port.in;

import com.dawnline.ops.application.port.out.CoreReply;
import com.dawnline.ops.application.port.out.ReadModelViews.CampSummary;
import com.dawnline.ops.application.port.out.ReadModelViews.CancelledButDelivered;
import com.dawnline.ops.application.port.out.ReadModelViews.Depot;
import com.dawnline.ops.application.port.out.ReadModelViews.RouteSummary;
import com.dawnline.ops.application.port.out.ReadModelViews.WaveSummary;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * 캠프 대시보드와 라우트 지도의 조회 (DESIGN.md §5.5 「조회」). 전부 {@code GET} 이라 {@code OPS_VIEWER} 에게 열리고
 * 감사하지 않는다.
 */
public interface QueryReadModelUseCase {

    /** 웨이브 목록의 최대 행 수. */
    int MAX_WAVES = 500;

    /** 컷오프 창의 최대 길이 — 넘으면 400. 한 화면이 읽을 만큼이다. */
    Duration MAX_WAVE_SPAN = Duration.ofDays(7);

    /** 예외 목록의 최대 행 수 — 넘치면 {@code truncated}. */
    int MAX_EXCEPTIONS = 200;

    /** @return 웨이브가 하나라도 있는 캠프들 */
    CampList camps();

    /**
     * @param campId 캠프
     * @param from   컷오프 창의 시작(포함), 없으면 지금 − 24시간
     * @param to     컷오프 창의 끝(제외), 없으면 지금 + 24시간
     * @return 컷오프 순. 창이 뒤집혔거나 {@link #MAX_WAVE_SPAN} 을 넘으면 400
     */
    WaveList waves(UUID campId, @Nullable Instant from, @Nullable Instant to);

    /**
     * 정시율 두 기준 — 게이지({@code dawnline_delivery_on_time_ratio})와 같은 뷰·같은 창·같은 식이다. 대시보드가 말하는
     * 수와 게이지가 말하는 수가 다를 수 없다.
     *
     * @return 캠프별 합과 모집단에서 빠진 수
     */
    DeliveryKpi deliveryKpi();

    /**
     * @param campId 캠프
     * @return 취소됐는데 배송된 주문 — KPI 와 같은 버킷 창
     */
    ExceptionList exceptions(UUID campId);

    /**
     * @param waveId 웨이브
     * @return 계획의 라우트들과 창고. 없는 웨이브면 404
     */
    WaveRoutes waveRoutes(UUID waveId);

    /**
     * dispatch 의 라우트 — 조회 위임(§3.3 이 허용한 유일한 동기 방향, ops → 코어). 예외를 던지지 않는다.
     *
     * @param routeId 라우트
     * @return 코어의 답
     */
    CoreReply route(UUID routeId);

    /** @param camps 캠프 id 순 */
    record CampList(List<CampSummary> camps) {
        public CampList {
            camps = List.copyOf(camps);
        }
    }

    /**
     * @param campId 캠프
     * @param from   창의 시작(포함)
     * @param to     창의 끝(제외)
     * @param waves  컷오프 순, 최대 {@link #MAX_WAVES}
     */
    record WaveList(UUID campId, Instant from, Instant to, List<WaveSummary> waves) {
        public WaveList {
            Objects.requireNonNull(campId, "campId");
            waves = List.copyOf(waves);
        }
    }

    /**
     * 배송 축 KPI — 현재 버킷 포함 UTC 정시 버킷 24개.
     *
     * @param firstBucket           첫 버킷(포함)
     * @param lastBucket            마지막 버킷(포함) — 지금 버킷이라 늘 부분이다
     * @param camps                 캠프별
     * @param outcomeWithoutPromise 결과는 났는데 약속(또는 캠프)을 몰라 정시율에서 빠진 수 — 전 캠프
     */
    record DeliveryKpi(Instant firstBucket, Instant lastBucket, List<CampKpi> camps, long outcomeWithoutPromise) {
        public DeliveryKpi {
            camps = List.copyOf(camps);
        }
    }

    /**
     * 한 캠프의 배송 축.
     *
     * @param campId              캠프
     * @param delivered           완료
     * @param failed              실패 — 분모에 있고 분자에 없다
     * @param onTimePromised      원 약속 기준 정시
     * @param onTimeRevised       개정 약속 기준 정시
     * @param revised             완료된 주문 가운데 약속이 개정된 수
     * @param onTimeRatioPromised 원 약속 기준 정시율(SLO) — 결과가 없으면 {@code null}(게이지의 {@code NaN})
     * @param onTimeRatioRevised  개정 약속 기준 정시율(참고값)
     */
    record CampKpi(UUID campId, long delivered, long failed, long onTimePromised, long onTimeRevised, long revised,
            @Nullable Double onTimeRatioPromised, @Nullable Double onTimeRatioRevised) {
        public CampKpi {
            Objects.requireNonNull(campId, "campId");
        }
    }

    /**
     * @param campId      캠프
     * @param firstBucket 첫 버킷(포함)
     * @param lastBucket  마지막 버킷(포함)
     * @param orders      배송 시각 역순, 최대 {@link #MAX_EXCEPTIONS}
     * @param truncated   목록이 잘렸다
     */
    record ExceptionList(UUID campId, Instant firstBucket, Instant lastBucket, List<CancelledButDelivered> orders,
            boolean truncated) {
        public ExceptionList {
            Objects.requireNonNull(campId, "campId");
            orders = List.copyOf(orders);
        }
    }

    /**
     * @param waveId 웨이브
     * @param planId 계획 — 아직 없으면 {@code null} 이고 라우트도 없다
     * @param depot  창고 — {@code wave.closed} 가 아직 오지 않았으면 {@code null}. 그때 지도는 stop 들의 중심으로
     *               물러난다
     * @param routes 라우트 id 순
     */
    record WaveRoutes(UUID waveId, @Nullable UUID planId, @Nullable Depot depot, List<RouteSummary> routes) {
        public WaveRoutes {
            Objects.requireNonNull(waveId, "waveId");
            routes = List.copyOf(routes);
        }
    }
}
