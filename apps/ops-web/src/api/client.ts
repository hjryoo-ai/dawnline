/**
 * ops-api 호출 층 (ADR-056 후보 2). 타입은 전부 커밋된 계약(`contracts/openapi/ops-api.yaml`)에서 생성된
 * `schema.ts` 에서 온다 — 여기서 손으로 쓰는 것은 경로 문자열과 함수 이름뿐이고, 경로도 `paths` 의 키로 검사된다.
 *
 * 화면이 부르는 연산마다 함수 하나다. 본문 파라미터를 **정해진 타입**으로 받는 것이 이 층의 이유다: 제네릭으로
 * 받으면 초과 속성 검사가 돌지 않아 계약 밖 칸(예: 재배정의 「이유」)이 컴파일을 지난다 — ADR-056 기준 5.
 */
import type { components, paths } from './schema';

export type Schemas = components['schemas'];
export type Problem = Schemas['ProblemDetail'];

/** 감사 id 가 실리는 응답 헤더 (ops-api 의 `MdcKeys.AUDIT_ID_HEADER`). */
export const AUDIT_ID_HEADER = 'X-Dawnline-Audit-Id';

type Method = 'get' | 'post';
type Operation<P extends keyof paths, M extends Method> = NonNullable<paths[P][M]>;

/** 그 경로에 정의된 메서드만 — 생성물은 없는 메서드를 `?: never` 로 적는다. */
type MethodOf<P extends keyof paths> = {
  [M in Method]: [NonNullable<paths[P][M]>] extends [never] ? never : M;
}[Method];

type Ok<O> = O extends { responses: { 200: { content: { '*/*': infer B } } } } ? B : never;
type Body<O> = O extends { requestBody: { content: { 'application/json': infer B } } } ? B : never;
type PathParams<O> = O extends { parameters: { path: infer X } } ? X : never;
type QueryParams<O> = O extends { parameters: { query?: infer X } } ? X : never;

/** 호출의 결과. 성공이면 본문과 (커맨드면) 감사 id, 아니면 상태와 Problem Details. */
export type ApiResult<T> =
  | { ok: true; data: T; auditId: string | null }
  | { ok: false; status: number; problem: Problem | null };

interface Init<O> {
  path?: PathParams<O>;
  query?: QueryParams<O>;
  body?: Body<O>;
}

export interface ApiConfig {
  /** 설정 화면이 넣은 토큰. 없으면 헤더를 싣지 않는다 — 서버가 401 `unauthenticated` 로 답한다. */
  token: () => string | null;
  /** 같은 출처다(nginx 가 `/api` 를 ops-api 로 넘긴다). 테스트만 바꾼다. */
  baseUrl?: string;
  fetch?: typeof fetch;
}

export function createApi(config: ApiConfig) {
  const baseUrl = config.baseUrl ?? '';
  const doFetch = config.fetch ?? ((input, init) => fetch(input, init));

  async function call<P extends keyof paths, M extends MethodOf<P>>(
    path: P,
    method: M,
    init: Init<Operation<P, M>>,
  ): Promise<ApiResult<Ok<Operation<P, M>>>> {
    let url = String(path);
    for (const [name, value] of Object.entries((init.path ?? {}) as Record<string, string>)) {
      url = url.replace(`{${name}}`, encodeURIComponent(value));
    }
    const query = new URLSearchParams();
    for (const [name, value] of Object.entries((init.query ?? {}) as Record<string, string | undefined>)) {
      if (value !== undefined) {
        query.set(name, value);
      }
    }
    const headers: Record<string, string> = { Accept: 'application/json, application/problem+json' };
    const token = config.token();
    if (token) {
      headers['Authorization'] = `Bearer ${token}`;
    }
    const requestInit: RequestInit = { method: method.toUpperCase(), headers };
    if (init.body !== undefined) {
      headers['Content-Type'] = 'application/json';
      requestInit.body = JSON.stringify(init.body);
    }
    let response: Response;
    try {
      response = await doFetch(`${baseUrl}${url}${query.size > 0 ? `?${query}` : ''}`, requestInit);
    } catch {
      return { ok: false, status: 0, problem: null };
    }
    if (response.ok) {
      return {
        ok: true,
        data: (await response.json()) as Ok<Operation<P, M>>,
        auditId: response.headers.get(AUDIT_ID_HEADER),
      };
    }
    return { ok: false, status: response.status, problem: await problemOf(response) };
  }

  return {
    call,
    camps: () => call('/api/v1/camps', 'get', {}),
    waves: (campId: string) => call('/api/v1/camps/{campId}/waves', 'get', { path: { campId } }),
    deliveryKpi: () => call('/api/v1/kpi/delivery', 'get', {}),
    exceptions: (campId: string) => call('/api/v1/camps/{campId}/exceptions', 'get', { path: { campId } }),
    waveRoutes: (waveId: string) => call('/api/v1/waves/{waveId}/routes', 'get', { path: { waveId } }),
    route: (routeId: string) => call('/api/v1/routes/{routeId}', 'get', { path: { routeId } }),
    reassign: (routeId: string, orderId: string, body: Schemas['ReassignBody']) =>
      call('/api/v1/routes/{routeId}/stops/{orderId}/reassign', 'post', { path: { routeId, orderId }, body }),
    closeWave: (waveId: string, body: Schemas['CloseBody']) =>
      call('/api/v1/waves/{waveId}/close', 'post', { path: { waveId }, body }),
  };
}

export type Api = ReturnType<typeof createApi>;

async function problemOf(response: Response): Promise<Problem | null> {
  const type = response.headers.get('Content-Type') ?? '';
  if (!type.includes('json')) {
    return null;
  }
  try {
    return (await response.json()) as Problem;
  } catch {
    return null;
  }
}
