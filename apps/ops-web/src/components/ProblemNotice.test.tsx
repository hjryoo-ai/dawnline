import { fireEvent, screen, within } from '@testing-library/react';
import { describe, expect, it } from 'vitest';
import { Dashboard } from '../screens/Dashboard';
import { RouteMap } from '../screens/RouteMap';
import { fakeCore, headerOf, json, renderWith } from '../test/fakeCore';
import { WAVE, openWave } from '../test/fixtures';

describe('인증 실패를 운영자의 말로', () => {
  it('토큰이 없으면 401 이고 설정으로 안내한다', async () => {
    // 가짜 코어는 ops-api 처럼 Authorization 이 없으면 401 unauthenticated 로 답한다.
    const fake = fakeCore({
      'GET /api/v1/camps': (request) =>
        headerOf(request, 'Authorization')
          ? json(200, { camps: [] })
          : json(401, { type: 'https://dawnline.internal/problems/unauthenticated', status: 401, code: 'unauthenticated' }),
    });
    renderWith(<Dashboard />, fake, null);

    const alert = await screen.findByRole('alert');
    expect(alert.textContent).toContain('토큰이 없거나 만료됐습니다');
    expect(within(alert).getByRole('link', { name: '설정' }).getAttribute('href')).toBe('#/settings');
    expect(headerOf(fake.requests[0]!, 'Authorization')).toBeUndefined();
  });

  it('뷰어의 커맨드는 403 이고 역할의 문제라고 말한다', async () => {
    const fake = fakeCore({
      [`GET /api/v1/waves/${WAVE}/routes`]: () => json(200, openWave),
      [`POST /api/v1/waves/${WAVE}/close`]: () =>
        json(403, { type: 'https://dawnline.internal/problems/forbidden', status: 403, code: 'forbidden' }),
    });
    renderWith(<RouteMap waveId={WAVE} />, fake, 'viewer-token');

    fireEvent.click(await screen.findByRole('button', { name: '웨이브 조기 마감' }));
    const dialog = screen.getByRole('dialog');
    fireEvent.change(within(dialog).getByRole('textbox'), { target: { value: '선마감' } });
    fireEvent.click(within(dialog).getByRole('button', { name: '마감' }));

    const alert = await screen.findByRole('alert');
    expect(alert.textContent).toContain('이 역할로는 할 수 없습니다');
    expect(alert.textContent).not.toContain('토큰이 없거나');
  });
});
