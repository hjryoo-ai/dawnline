package com.dawnline.ops.application.port.out;

import com.dawnline.ops.domain.WaveStatus;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * {@code rm_waves} (DESIGN.md §5.5). 계약은 {@link OrderRows} 와 같다.
 */
public interface WaveRows {

    /**
     * 없으면 키만으로 만들고 잠근다.
     *
     * @param waveId 웨이브
     * @return 판정용 현재 값
     */
    WaveRow lock(UUID waveId);

    /**
     * @param waveId 웨이브
     * @param patch  적을 칸
     */
    void write(UUID waveId, Patch<WaveColumn> patch);

    /**
     * {@code order_count} 를 {@code rm_orders} 에서 다시 센다(ADR-051 결정 4) — 이 웨이브에 편입된
     * 것으로 <em>알려진</em> 주문 수다. {@link #lock} 으로 잠근 행에만 부른다: 편입을 바꾸는
     * 트랜잭션이 전부 이 행을 잠그므로, 잠금 뒤에 시작한 이 문장은 앞선 편입을 전부 본다.
     *
     * @param waveId 웨이브
     */
    void recountOrders(UUID waveId);

    /**
     * @param status 웨이브 상태
     */
    record WaveRow(@Nullable WaveStatus status) {
    }
}
