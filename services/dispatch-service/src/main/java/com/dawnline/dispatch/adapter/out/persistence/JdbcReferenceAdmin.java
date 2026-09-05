package com.dawnline.dispatch.adapter.out.persistence;

import com.dawnline.common.Ids;
import com.dawnline.common.error.NotFoundException;
import com.dawnline.dispatch.application.port.in.ResourceViews;
import com.dawnline.dispatch.application.port.out.ReferenceAdmin;
import jakarta.persistence.EntityManager;
import java.sql.Time;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import tools.jackson.databind.ObjectMapper;

/** 참조 데이터 관리 어댑터 (DESIGN.md §5.3). */
public class JdbcReferenceAdmin implements ReferenceAdmin {

    private static final ObjectMapper JSON = new ObjectMapper();

    private final EntityManager entityManager;

    /**
     * @param entityManager 공유 EntityManager 프록시
     */
    public JdbcReferenceAdmin(EntityManager entityManager) {
        this.entityManager = Objects.requireNonNull(entityManager, "entityManager");
    }

    @Override
    @SuppressWarnings("unchecked")
    public List<ResourceViews.RuleView> listRules(@Nullable UUID campId) {
        String sql = """
                SELECT id, camp_id, name, type, severity, priority, enabled, rule_version,
                       params::text
                  FROM dispatch_rules
                 WHERE camp_id IS NULL OR camp_id = ?
                 ORDER BY camp_id NULLS FIRST, priority, name
                """;
        List<Object[]> rows = entityManager.createNativeQuery(sql)
                .setParameter(1, campId).getResultList();
        List<ResourceViews.RuleView> views = new ArrayList<>(rows.size());
        for (Object[] row : rows) {
            views.add(new ResourceViews.RuleView((UUID) row[0], (UUID) row[1], (String) row[2],
                    (String) row[3], (String) row[4], ((Number) row[5]).intValue(),
                    (Boolean) row[6], ((Number) row[7]).intValue(), (String) row[8]));
        }
        return List.copyOf(views);
    }

    @Override
    public int updateRule(UUID ruleId, Map<String, Object> params, boolean enabled) {
        int updated = entityManager.createNativeQuery("""
                UPDATE dispatch_rules
                   SET params = cast(? as jsonb), enabled = ?, rule_version = rule_version + 1,
                       updated_at = now()
                 WHERE id = ?
                """)
                .setParameter(1, JSON.writeValueAsString(params))
                .setParameter(2, enabled)
                .setParameter(3, ruleId)
                .executeUpdate();
        if (updated == 0) {
            throw NotFoundException.of("DispatchRule", ruleId.toString());
        }
        return ((Number) entityManager
                .createNativeQuery("SELECT rule_version FROM dispatch_rules WHERE id = ?")
                .setParameter(1, ruleId).getSingleResult()).intValue();
    }

    @Override
    @SuppressWarnings("unchecked")
    public List<ResourceViews.VehicleView> listVehicles(UUID campId) {
        List<Object[]> rows = entityManager.createNativeQuery("""
                SELECT id, camp_id, code, type, max_weight_g, max_volume_cm3, is_cold,
                       allows_hazmat, fixed_cost_krw, cost_per_km_krw, cost_per_min_krw,
                       shift_start, shift_end, active
                  FROM vehicles WHERE camp_id = ? ORDER BY code
                """).setParameter(1, campId).getResultList();
        List<ResourceViews.VehicleView> views = new ArrayList<>(rows.size());
        for (Object[] row : rows) {
            views.add(new ResourceViews.VehicleView((UUID) row[0], (UUID) row[1], (String) row[2],
                    (String) row[3], ((Number) row[4]).intValue(), ((Number) row[5]).intValue(),
                    (Boolean) row[6], (Boolean) row[7], ((Number) row[8]).intValue(),
                    ((Number) row[9]).intValue(), ((Number) row[10]).intValue(),
                    localTime(row[11]), localTime(row[12]), (Boolean) row[13]));
        }
        return List.copyOf(views);
    }

    @Override
    public UUID createVehicle(ResourceViews.NewVehicle request) {
        UUID id = Ids.newId();
        entityManager.createNativeQuery("""
                INSERT INTO vehicles (id, camp_id, code, type, max_weight_g, max_volume_cm3,
                                      is_cold, allows_hazmat, fixed_cost_krw, cost_per_km_krw,
                                      cost_per_min_krw, shift_start, shift_end, active)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, TRUE)
                """)
                .setParameter(1, id).setParameter(2, request.campId())
                .setParameter(3, request.code()).setParameter(4, request.type())
                .setParameter(5, request.maxWeightG()).setParameter(6, request.maxVolumeCm3())
                .setParameter(7, request.cold()).setParameter(8, request.allowsHazmat())
                .setParameter(9, request.fixedCostKrw()).setParameter(10, request.costPerKmKrw())
                .setParameter(11, request.costPerMinKrw())
                .setParameter(12, Time.valueOf(request.shiftStart()))
                .setParameter(13, Time.valueOf(request.shiftEnd()))
                .executeUpdate();
        return id;
    }

    @Override
    @SuppressWarnings("unchecked")
    public List<ResourceViews.DriverView> listDrivers(UUID campId) {
        List<Object[]> rows = entityManager.createNativeQuery("""
                SELECT id, camp_id, vehicle_id, code, name, status
                  FROM drivers WHERE camp_id = ? ORDER BY code
                """).setParameter(1, campId).getResultList();
        List<ResourceViews.DriverView> views = new ArrayList<>(rows.size());
        for (Object[] row : rows) {
            views.add(new ResourceViews.DriverView((UUID) row[0], (UUID) row[1], (UUID) row[2],
                    (String) row[3], (String) row[4], (String) row[5]));
        }
        return List.copyOf(views);
    }

    @Override
    public UUID createDriver(ResourceViews.NewDriver request) {
        UUID id = Ids.newId();
        entityManager.createNativeQuery("""
                INSERT INTO drivers (id, camp_id, vehicle_id, code, name, status)
                VALUES (?, ?, ?, ?, ?, 'AVAILABLE')
                """)
                .setParameter(1, id).setParameter(2, request.campId())
                .setParameter(3, request.vehicleId()).setParameter(4, request.code())
                .setParameter(5, request.name())
                .executeUpdate();
        return id;
    }

    /** 드라이버가 {@link LocalTime} 을 줄 수도 {@link Time} 을 줄 수도 있다 (5b 에서 겪었다). */
    private static LocalTime localTime(Object value) {
        if (value instanceof LocalTime time) {
            return time;
        }
        if (value instanceof Time time) {
            return time.toLocalTime();
        }
        throw new IllegalStateException("근무 시각 컬럼을 읽을 수 없습니다: " + value.getClass());
    }
}
