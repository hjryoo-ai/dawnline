package com.dawnline.fulfillment.application;

import com.dawnline.common.GeoPoint;
import com.dawnline.fulfillment.application.port.out.FulfillmentOrderRepository;
import com.dawnline.fulfillment.application.port.out.ReferenceData;
import com.dawnline.fulfillment.application.port.out.WaveRepository;
import com.dawnline.fulfillment.domain.Camp;
import com.dawnline.fulfillment.domain.FulfillmentCenter;
import com.dawnline.fulfillment.domain.FulfillmentOrder;
import com.dawnline.fulfillment.domain.FulfillmentOrderStatus;
import com.dawnline.fulfillment.domain.ServiceTier;
import com.dawnline.fulfillment.domain.Wave;
import com.dawnline.fulfillment.domain.Zone;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * 유스케이스 단위 테스트용 인메모리 저장소.
 *
 * <p>DB 의 <strong>계약</strong>만 흉내 낸다 — {@code ON CONFLICT DO NOTHING} 의 반환값, 자연키
 * UNIQUE, 그리고 되살린 애그리거트가 별개 인스턴스라는 사실이다. 락은 흉내 내지 않는다.
 * 락이 실제로 어떤 SQL 이 되고 무엇을 막는지는 {@code FulfillmentPersistenceIT} 가 실물로 본다 —
 * 여기서 흉내 내면 <em>흉내를 검사하는</em> 테스트가 된다.
 */
final class InMemoryFulfillmentRepositories {

    private final Map<UUID, Wave> wavesById = new LinkedHashMap<>();
    private final Map<UUID, FulfillmentOrder> ordersById = new LinkedHashMap<>();
    private final Map<String, Zone> zonesByGeohash = new LinkedHashMap<>();
    private final Map<UUID, Camp> campsById = new LinkedHashMap<>();
    private final List<FulfillmentCenter> centers = new ArrayList<>();

    /** 등록된 권역. */
    void addZone(Zone zone) {
        zonesByGeohash.put(zone.geohash5(), zone);
    }

    /** 등록된 캠프. */
    void addCamp(Camp camp) {
        campsById.put(camp.id(), camp);
    }

    /** 등록된 FC. */
    void addCenter(FulfillmentCenter center) {
        centers.add(center);
    }

    /** 저장된 웨이브 전부. */
    List<Wave> waves() {
        return List.copyOf(wavesById.values());
    }

    /** 저장된 주문. */
    Optional<FulfillmentOrder> order(UUID orderId) {
        return Optional.ofNullable(ordersById.get(orderId));
    }

    WaveRepository waveRepository() {
        return new WaveRepository() {
            @Override
            public boolean insertIfAbsent(Wave wave) {
                boolean exists = wavesById.values().stream().anyMatch(existing ->
                        existing.campId().equals(wave.campId())
                                && existing.serviceTier() == wave.serviceTier()
                                && existing.cutoffAt().equals(wave.cutoffAt()));
                if (exists) {
                    return false;
                }
                wavesById.put(wave.id(), wave);
                return true;
            }

            @Override
            public Optional<Wave> findByNaturalKey(UUID campId, ServiceTier tier, Instant cutoffAt) {
                return wavesById.values().stream()
                        .filter(wave -> wave.campId().equals(campId)
                                && wave.serviceTier() == tier
                                && wave.cutoffAt().equals(cutoffAt))
                        .findFirst();
            }

            @Override
            public Optional<Wave> findById(UUID id) {
                return Optional.ofNullable(wavesById.get(id));
            }

            @Override
            public Optional<Wave> findByIdForShare(UUID id) {
                return findById(id);
            }

            @Override
            public Optional<Wave> findByIdForUpdate(UUID id) {
                return findById(id);
            }

            @Override
            public List<Wave> findDueForClosing(Instant cutoffAtOrBefore, int limit) {
                return wavesById.values().stream()
                        .filter(wave -> wave.isDueForClosing(cutoffAtOrBefore, java.time.Duration.ZERO))
                        .limit(limit)
                        .toList();
            }

            @Override
            public void update(Wave wave) {
                wavesById.put(wave.id(), wave);
            }

            @Override
            public int deleteSettledClosedBefore(Instant closedBefore, int limit) {
                throw new UnsupportedOperationException();
            }
        };
    }

    FulfillmentOrderRepository orderRepository() {
        return new FulfillmentOrderRepository() {
            @Override
            public boolean insertIfAbsent(FulfillmentOrder order) {
                if (ordersById.containsKey(order.orderId())) {
                    return false;
                }
                ordersById.put(order.orderId(), order);
                return true;
            }

            @Override
            public Optional<FulfillmentOrder> findById(UUID orderId) {
                return Optional.ofNullable(ordersById.get(orderId));
            }

            @Override
            public List<FulfillmentOrder> findPlannedInWave(UUID waveId) {
                return ordersById.values().stream()
                        .filter(order -> order.status() == FulfillmentOrderStatus.PLANNED
                                && order.waveId().filter(waveId::equals).isPresent())
                        .toList();
            }

            @Override
            public int countPlannedInWave(UUID waveId) {
                return findPlannedInWave(waveId).size();
            }

            @Override
            public void update(FulfillmentOrder order) {
                ordersById.put(order.orderId(), order);
            }

            @Override
            public int deleteSettledUpdatedBefore(Instant updatedBefore, int limit) {
                throw new UnsupportedOperationException();
            }
        };
    }

    ReferenceData referenceData() {
        return new ReferenceData() {
            @Override
            public Optional<Zone> findZone(String geohash5) {
                return Optional.ofNullable(zonesByGeohash.get(geohash5));
            }

            @Override
            public Optional<Camp> findCamp(UUID campId) {
                return Optional.ofNullable(campsById.get(campId));
            }

            @Override
            public List<FulfillmentCenter> findAllCenters() {
                return List.copyOf(centers);
            }

            @Override
            public List<Camp> findAllCamps() {
                return List.copyOf(campsById.values());
            }

            @Override
            public Map<UUID, Map<String, Integer>> findStock(Collection<String> skus) {
                return Map.of();
            }
        };
    }

    /** 좌표 하나짜리 FC. */
    static FulfillmentCenter center(UUID id, String code, double lat, double lng, boolean cold,
            java.util.Set<ServiceTier> tiers) {
        return new FulfillmentCenter(id, code, new GeoPoint(lat, lng), cold, tiers, true);
    }
}
