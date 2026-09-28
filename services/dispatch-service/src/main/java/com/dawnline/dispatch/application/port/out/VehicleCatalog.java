package com.dawnline.dispatch.application.port.out;

import com.dawnline.common.TimeWindow;
import com.dawnline.dispatch.domain.optimizer.VehicleSpec;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** 캠프의 가용 차량 (DESIGN.md §5.3 {@code vehicles}). */
public interface VehicleCatalog {

    /**
     * 이 웨이브를 위해 캠프의 활성 차량이 <strong>언제부터 언제까지 있는가</strong>
     * ([ADR-075](docs/adr/ADR-075-promise-start-is-a-floor-vehicle-time-belongs-to-the-earlier-plan.md) 결정 3 · 5).
     *
     * <p>둘을 한 자리에서 계산한다.
     * <ul>
     *   <li><strong>근무는 약속창에 닿는 근무다.</strong> {@code shift_start}/{@code shift_end} 는 벽시계({@code TIME})이고
     *       {@link VehicleSpec#shift()} 는 {@link Instant} 다. 날짜를 붙이는 기준은 계획 시각이 아니라 약속창이다 — 「계획 시각에 아직
     *       끝나지 않은 첫 근무」는 계획이 언제 도는지에 따라 답이 바뀌었다(14:03 에 닫은 내일의 웨이브가 오늘 근무를 받았다).</li>
     *   <li><strong>근무 시작은 {@code available_from} 이다</strong> — {@code max(근무 시작, 그 차량의 끝나지 않은 발행 라우트 중 가장
     *       늦은 계획 복귀)}. 세는 라우트는 <em>다른 웨이브</em>의, <em>이 계획 시각 전에 시작한</em> 계획의 것이다 — 계획 뒤에 시각을
     *       다시 계산하는 경로(취소 · 재계획 · 재배정)가 같은 값을 다시 얻고, 자기 라우트나 뒤에 계획된 라우트에 밀리지 않는다.
     *       복귀가 근무 끝을 넘은 차량은 목록에 없다.</li>
     * </ul>
     *
     * @param campId   캠프 id
     * @param planFor  계획 시각. 이 시각 전에 끝난 근무는 고르지 않는다
     * @param promised 웨이브의 약속창(후보 약속창의 합)
     * @param waveId   이 웨이브 — 자기 라우트는 점유로 세지 않는다
     */
    List<VehicleSpec> availableAt(UUID campId, Instant planFor, TimeWindow promised, UUID waveId);
}
