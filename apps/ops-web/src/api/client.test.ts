import { describe, expect, it } from 'vitest';
import { AUDIT_ID_HEADER, createApi } from './client';

/** 가짜 fetch — 받은 요청을 기록하고 정해 둔 응답을 돌려준다. */
function recording(response: Response) {
  const requests: { url: string; init: RequestInit }[] = [];
  const fetch = async (input: RequestInfo | URL, init?: RequestInit) => {
    requests.push({ url: String(input), init: init ?? {} });
    return response.clone();
  };
  return { requests, fetch: fetch as typeof globalThis.fetch };
}

function json(status: number, body: unknown, headers: Record<string, string> = {}) {
  return new Response(JSON.stringify(body), {
    status,
    headers: { 'Content-Type': status >= 400 ? 'application/problem+json' : 'application/json', ...headers },
  });
}

describe('createApi', () => {
  it('토큰이 있으면 Bearer 로 싣고 경로 변수를 채운다', async () => {
    const core = recording(json(200, { waveId: 'w', planId: null, depot: null, routes: [] }));
    const api = createApi({ token: () => 'abc', fetch: core.fetch });

    const result = await api.waveRoutes('w 1');

    expect(result.ok).toBe(true);
    expect(core.requests[0]?.url).toBe('/api/v1/waves/w%201/routes');
    expect((core.requests[0]?.init.headers as Record<string, string>)['Authorization']).toBe('Bearer abc');
  });

  it('토큰이 없으면 Authorization 을 싣지 않는다 — 서버가 401 로 답하게 둔다', async () => {
    const core = recording(json(401, { code: 'unauthenticated', status: 401 }));
    const api = createApi({ token: () => null, fetch: core.fetch });

    const result = await api.camps();

    expect(core.requests[0]?.init.headers).not.toHaveProperty('Authorization');
    expect(result).toEqual({ ok: false, status: 401, problem: { code: 'unauthenticated', status: 401 } });
  });

  it('커맨드는 계약의 본문만 JSON 으로 보내고 응답의 감사 id 를 읽는다', async () => {
    const core = recording(json(200, { waveId: 'w', status: 'CLOSED' }, { [AUDIT_ID_HEADER]: 'audit-1' }));
    const api = createApi({ token: () => 't', fetch: core.fetch });

    const result = await api.closeWave('w', { reason: '피크 대비 선마감' });

    expect(core.requests[0]?.init.method).toBe('POST');
    expect(core.requests[0]?.init.body).toBe('{"reason":"피크 대비 선마감"}');
    expect(result.ok && result.auditId).toBe('audit-1');
  });

  it('연결이 안 되면 상태 0 이고 본문이 없다', async () => {
    const api = createApi({
      token: () => 't',
      fetch: (async () => {
        throw new TypeError('Failed to fetch');
      }) as typeof fetch,
    });

    expect(await api.camps()).toEqual({ ok: false, status: 0, problem: null });
  });
});
