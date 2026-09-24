import { render } from '@testing-library/react';
import type { ReactNode } from 'react';
import { ApiProvider } from '../api/ApiContext';
import { createApi } from '../api/client';

export interface Request {
  key: string;
  init: RequestInit;
}

export type Handler = (request: Request) => Response;

export function json(status: number, body: unknown, headers: Record<string, string> = {}): Response {
  return new Response(JSON.stringify(body), {
    status,
    headers: { 'Content-Type': status >= 400 ? 'application/problem+json' : 'application/json', ...headers },
  });
}

/**
 * 가짜 ops-api — `"GET /api/v1/camps"` 같은 키로 답을 정한다. 화면은 실제 호출 층(`createApi`)을 지나므로 경로 ·
 * 헤더 · 본문이 운영과 같은 모양으로 온다.
 */
export function fakeCore(routes: Record<string, Handler>) {
  const requests: Request[] = [];
  const fetch = async (input: RequestInfo | URL, init?: RequestInit) => {
    const url = String(input).split('?')[0];
    const request = { key: `${init?.method ?? 'GET'} ${url}`, init: init ?? {} };
    requests.push(request);
    const handler = routes[request.key];
    return handler ? handler(request) : json(404, { code: 'not-found', status: 404, detail: request.key });
  };
  return { fetch: fetch as typeof globalThis.fetch, requests };
}

export function renderWith(ui: ReactNode, core: ReturnType<typeof fakeCore>, token: string | null = 'token') {
  const api = createApi({ token: () => token, fetch: core.fetch });
  return render(<ApiProvider api={api}>{ui}</ApiProvider>);
}

export function headerOf(request: Request, name: string): string | undefined {
  return (request.init.headers as Record<string, string> | undefined)?.[name];
}
