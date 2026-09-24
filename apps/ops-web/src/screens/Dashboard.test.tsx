import { fireEvent, screen, within } from '@testing-library/react';
import { describe, expect, it } from 'vitest';
import { fakeCore, json, renderWith } from '../test/fakeCore';
import { CAMP, camps, kpi, waves } from '../test/fixtures';
import { Dashboard } from './Dashboard';

function core(exceptions: unknown = { campId: CAMP, orders: [], total: 0 }) {
  return fakeCore({
    'GET /api/v1/camps': () => json(200, camps),
    [`GET /api/v1/camps/${CAMP}/waves`]: () => json(200, waves),
    'GET /api/v1/kpi/delivery': () => json(200, kpi),
    [`GET /api/v1/camps/${CAMP}/exceptions`]: () => json(200, exceptions),
  });
}

describe('캠프 대시보드', () => {
  it('캠프 코드 · 웨이브 · 정시율 두 기준 · 개정 수를 그린다', async () => {
    renderWith(<Dashboard />, core());

    // 캠프는 코드로 부르고, 코드가 없는 캠프(옛 이벤트만 있다)는 id 를 줄여 쓴다.
    const picker = await screen.findByRole('combobox', { name: /캠프/ });
    expect(within(picker).getByRole('option', { name: 'CAMP-SEO-N · 웨이브 2' })).toBeDefined();
    expect(within(picker).getByRole('option', { name: '…0000ca02 · 웨이브 1' })).toBeDefined();

    const table = await screen.findByRole('table', { name: '웨이브' });
    expect(within(table).getByText('PLANNED')).toBeDefined();
    expect(within(table).getByText('1,250,000원')).toBeDefined();
    expect(within(table).getByRole('link', { name: '지도' }).getAttribute('href')).toBe(
      `#/waves/${waves.waves[0]!.waveId}`,
    );

    const ratios = await screen.findByLabelText('정시율');
    expect(within(ratios).getByText('96.0%')).toBeDefined();
    // 개정 기준의 null 은 「창 안에 결과가 없다」 — 0% 로 그리지 않는다.
    expect(within(ratios).getByText('—')).toBeDefined();
    expect(within(ratios).queryByText('0.0%')).toBeNull();
    expect(within(ratios).getByText('3건')).toBeDefined();
  });

  it('예외 목록은 전체 수와 해소를 모른다는 문장을 싣는다', async () => {
    const orders = [
      { orderId: '0199c000-0000-7000-8000-0000000000c1', waveId: null, routeId: null, deliveredAt: '2026-09-24T16:10:00Z' },
      { orderId: '0199c000-0000-7000-8000-0000000000c2', waveId: null, routeId: null, deliveredAt: '2026-08-10T16:10:00Z' },
    ];
    renderWith(<Dashboard />, core({ campId: CAMP, orders, total: 350 }));

    const panel = await screen.findByLabelText('예외 목록');
    // 잘렸다는 사실은 total 이 말한다 — 화면은 그것을 숨기지 않는다.
    expect(within(panel).getByText(/350건/)).toBeDefined();
    expect(within(panel).getByText(/최근 2건만 보인다/)).toBeDefined();
    expect(within(panel).getByText(/해소 여부\(환불·회수\)는 이 시스템이 모릅니다/)).toBeDefined();
    expect(within(panel).getAllByRole('row')).toHaveLength(3); // 머리 + 두 행 — 창 밖(8월)의 건도 남는다
  });

  it('캠프를 바꾸면 그 캠프를 읽는다', async () => {
    const fake = core();
    renderWith(<Dashboard />, fake);
    const picker = await screen.findByRole('combobox', { name: /캠프/ });
    fireEvent.change(picker, { target: { value: camps.camps[1]!.campId } });

    await screen.findAllByRole('alert'); // 가짜 코어에 그 캠프가 없다 — 404 가 보인다
    expect(fake.requests.map((request) => request.key)).toContain(
      `GET /api/v1/camps/${camps.camps[1]!.campId}/waves`,
    );
  });
});
