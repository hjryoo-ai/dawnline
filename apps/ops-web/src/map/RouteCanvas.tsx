import L from 'leaflet';
import { useEffect, useRef, useState } from 'react';
import type { Schemas } from '../api/client';
import type { Tiles } from '../session';

/** stop 상태 색 — dispatch 의 `RouteStopStatus`(PLANNED · ARRIVED · COMPLETED · FAILED · CANCELLED). */
export const STOP_COLORS: Record<string, string> = {
  PLANNED: '#6b7280',
  ARRIVED: '#d97706',
  COMPLETED: '#15803d',
  FAILED: '#b91c1c',
  CANCELLED: '#a3a3a3',
};

const OSM_URL = 'https://tile.openstreetmap.org/{z}/{x}/{y}.png';
const OSM_ATTRIBUTION = '&copy; <a href="https://www.openstreetmap.org/copyright">OpenStreetMap</a> contributors';

/**
 * 라우트 하나의 지도(ADR-057 결정 1). **기본은 타일 레이어가 없다** — 캠프 중심 좌표 위에 창고 · stop · 순서 폴리라인만.
 * OSM 타일은 설정에서 켠 경우에만 더하고, 타일이 실패해도 나머지는 그대로다(한 줄 알림).
 */
export function RouteCanvas(props: {
  depot: Schemas['Depot'] | null;
  stops: Schemas['RouteStop'][];
  atRisk: boolean;
  tiles: Tiles;
}) {
  const container = useRef<HTMLDivElement>(null);
  const [tileFailed, setTileFailed] = useState(false);

  useEffect(() => {
    if (!container.current) {
      return;
    }
    const map = L.map(container.current, { zoomControl: true, attributionControl: true });
    if (props.tiles === 'osm') {
      L.tileLayer(OSM_URL, { attribution: OSM_ATTRIBUTION, maxZoom: 19 })
        .on('tileerror', () => setTileFailed(true))
        .addTo(map);
    }
    const ordered = [...props.stops].sort((a, b) => a.seq - b.seq);
    const points: L.LatLngTuple[] = ordered.map((stop) => [stop.lat, stop.lng]);
    // 창고에서 나가 창고로 돌아온다 — 창고가 없으면(옛 이벤트) stop 들만.
    const depot: L.LatLngTuple | null = props.depot ? [props.depot.lat, props.depot.lng] : null;
    const path: L.LatLngTuple[] = depot ? [depot, ...points, depot] : points;
    if (path.length > 1) {
      L.polyline(path, {
        color: props.atRisk ? '#b91c1c' : '#1d4ed8',
        weight: props.atRisk ? 5 : 3,
        dashArray: props.atRisk ? '8 6' : undefined,
      }).addTo(map);
    }
    if (depot) {
      L.circleMarker(depot, { radius: 9, color: '#111827', fillOpacity: 1 })
        .bindTooltip('창고')
        .addTo(map);
    }
    for (const stop of ordered) {
      L.circleMarker([stop.lat, stop.lng], {
        radius: 7,
        color: STOP_COLORS[stop.status] ?? '#6b7280',
        fillOpacity: 0.9,
      })
        .bindTooltip(`#${stop.seq} ${stop.status}`)
        .addTo(map);
    }
    const bounds = L.latLngBounds(path.length > 0 ? path : [[37.5665, 126.978]]); // 비어 있으면 서울 시청
    if (path.length > 1) {
      map.fitBounds(bounds, { padding: [24, 24] });
    } else {
      // stop 이 하나거나 없다 — 캠프(또는 그 stop)를 가운데 둔다.
      map.setView(bounds.getCenter(), 14);
    }
    return () => {
      map.remove();
    };
  }, [props.depot, props.stops, props.atRisk, props.tiles]);

  return (
    <div className="map-wrap">
      {tileFailed && (
        <p role="status" className="notice">
          타일을 불러오지 못했습니다 — 타일 없이 그립니다.
        </p>
      )}
      <div ref={container} className={`map ${props.tiles === 'none' ? 'map-blank' : ''}`} data-testid="route-canvas" />
    </div>
  );
}
