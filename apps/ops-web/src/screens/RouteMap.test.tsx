import { fireEvent, screen, waitFor, within } from '@testing-library/react';
import { describe, expect, it } from 'vitest';
import { session } from '../session';
import { fakeCore, json, renderWith } from '../test/fakeCore';
import { ORDER, OTHER_ROUTE, ROUTE, WAVE, openWave, routeDetail, routes } from '../test/fixtures';
import { RouteMap } from './RouteMap';

const AUDIT = '0199c000-0000-7000-8000-00000000ad01';

function core(wave = routes) {
  return fakeCore({
    [`GET /api/v1/waves/${WAVE}/routes`]: () => json(200, wave),
    [`GET /api/v1/routes/${ROUTE}`]: () => json(200, routeDetail),
    [`POST /api/v1/waves/${WAVE}/close`]: () =>
      json(200, { waveId: WAVE, status: 'CLOSED', closeCause: 'MANUAL', closedAt: '2026-09-24T14:00:00Z' }, {
        'X-Dawnline-Audit-Id': AUDIT,
      }),
    [`POST /api/v1/routes/${ROUTE}/stops/${ORDER}/reassign`]: () =>
      json(200, { orderId: ORDER, fromRouteId: ROUTE, fromRevision: 2, toRouteId: OTHER_ROUTE, toRevision: 2 }, {
        'X-Dawnline-Audit-Id': AUDIT,
      }),
  });
}

describe('라우트 지도의 커맨드 확인', () => {
  it('조기 마감은 이유 없이는 보낼 수 없고, 보낸 뒤 감사 id 를 보인다', async () => {
    const fake = core(openWave);
    renderWith(<RouteMap waveId={WAVE} />, fake);

    fireEvent.click(await screen.findByRole('button', { name: '웨이브 조기 마감' }));
    const dialog = screen.getByRole('dialog', { name: '웨이브 조기 마감' });
    const confirm = within(dialog).getByRole('button', { name: '마감' });
    const reason = within(dialog).getByRole('textbox', { name: /이유/ });

    expect(confirm).toHaveProperty('disabled', true);
    fireEvent.change(reason, { target: { value: '   ' } });
    expect(confirm).toHaveProperty('disabled', true); // 공백만으로는 안 된다 — 코어가 400 으로 돌려보낼 입력
    fireEvent.change(reason, { target: { value: ' 피크 대비 선마감 ' } });
    expect(confirm).toHaveProperty('disabled', false);
    fireEvent.click(confirm);

    expect(await screen.findByText(new RegExp(`감사 id ${AUDIT}`))).toBeDefined();
    const close = fake.requests.find((request) => request.key === `POST /api/v1/waves/${WAVE}/close`);
    expect(close?.init.body).toBe('{"reason":"피크 대비 선마감"}');
  });

  it('재배정 창에는 이유 칸이 없다 — 계약의 본문은 targetRouteId 하나다', async () => {
    const fake = core();
    renderWith(<RouteMap waveId={WAVE} />, fake);

    const stops = await screen.findByRole('table', { name: 'stop 순서' });
    // 이미 배송된 stop 은 옮길 수 없다 — PLANNED 인 첫 stop 의 주문만 버튼이 살아 있다.
    const [first, second] = within(stops).getAllByRole('button', { name: '재배정' });
    expect(second).toHaveProperty('disabled', true);
    fireEvent.click(first!);

    const dialog = screen.getByRole('dialog', { name: 'stop 재배정' });
    expect(within(dialog).queryByRole('textbox')).toBeNull();
    fireEvent.click(within(dialog).getByRole('button', { name: '재배정' }));

    await screen.findByText(new RegExp(`감사 id ${AUDIT}`));
    const reassign = fake.requests.find((request) => request.key.endsWith('/reassign'));
    expect(reassign?.init.body).toBe(`{"targetRouteId":"${OTHER_ROUTE}"}`);
  });
});

describe('라우트의 이름', () => {
  it('같은 순간에 만든 라우트도 이름이 갈린다 — UUIDv7 의 앞은 시각이다', async () => {
    // 전제: 픽스처의 두 라우트는 앞 8자가 같다 — 한 계획의 라우트는 같은 순간에 만들어진다.
    expect(ROUTE.slice(0, 8)).toBe(OTHER_ROUTE.slice(0, 8));
    renderWith(<RouteMap waveId={WAVE} />, core());

    const list = await screen.findByRole('table', { name: '라우트' });
    const names = within(list).getAllByRole('button').map((button) => button.textContent);
    expect(names).toHaveLength(2);
    expect(new Set(names).size).toBe(2);

    // 재배정 대상 목록은 선택한 라우트를 빼고 보인다 — 그 이름이 선택한 라우트의 이름과 달라야 고를 수 있다.
    await screen.findByRole('table', { name: 'stop 순서' });
    const target = screen.getByRole('combobox', { name: '재배정 대상 라우트' });
    const options = within(target).getAllByRole('option').map((option) => option.textContent);
    expect(options).toEqual([names[1]]);
    expect(options).not.toContain(names[0]);
  });
});

describe('라우트 지도의 바탕', () => {
  it('기본은 타일 레이어가 없다', async () => {
    const { container } = renderWith(<RouteMap waveId={WAVE} />, core());
    await screen.findByRole('table', { name: 'stop 순서' });
    await waitFor(() => expect(container.querySelector('path.leaflet-interactive')).not.toBeNull());

    expect(container.querySelector('img.leaflet-tile')).toBeNull();
  });

  it('OSM 타일이 전부 실패해도 지도 · stop · 명령이 남는다', async () => {
    session.setTiles('osm');
    const { container } = renderWith(<RouteMap waveId={WAVE} />, core());
    await screen.findByRole('table', { name: 'stop 순서' });
    await waitFor(() => expect(container.querySelectorAll('img.leaflet-tile').length).toBeGreaterThan(0));

    // 전제: 타일을 실제로 요청했다 — 아니면 아래는 아무것도 검사하지 않는다.
    const tiles = [...container.querySelectorAll<HTMLImageElement>('img.leaflet-tile')];
    expect(tiles[0]!.src).toContain('tile.openstreetmap.org');
    tiles.forEach((tile) => tile.dispatchEvent(new Event('error')));

    expect(await screen.findByText('타일을 불러오지 못했습니다 — 타일 없이 그립니다.')).toBeDefined();
    expect(container.querySelector('path.leaflet-interactive')).not.toBeNull(); // 순서 폴리라인
    expect(within(screen.getByRole('table', { name: 'stop 순서' })).getAllByRole('row')).toHaveLength(3);
    expect(screen.getByRole('button', { name: '웨이브 조기 마감' })).toBeDefined();
    expect(container.querySelector('.leaflet-control-attribution')?.textContent).toContain('OpenStreetMap');
  });
});
