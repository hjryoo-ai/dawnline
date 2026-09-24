/**
 * 브라우저 세션 하나의 설정 — 토큰과 지도 타일 선택. sessionStorage 라 탭을 닫으면 사라진다(토큰이 남지 않는다).
 * 저장소가 막힌 브라우저(사생활 모드 등)에서도 화면은 선다 — 읽기·쓰기 실패는 「없음」이다.
 */
export type Tiles = 'none' | 'osm';

const TOKEN_KEY = 'dawnline.ops.token';
const TILES_KEY = 'dawnline.ops.tiles';

function read(key: string): string | null {
  try {
    return window.sessionStorage.getItem(key);
  } catch {
    return null;
  }
}

function write(key: string, value: string | null): void {
  try {
    if (value === null) {
      window.sessionStorage.removeItem(key);
    } else {
      window.sessionStorage.setItem(key, value);
    }
  } catch {
    // 저장소가 막혔다 — 이 세션에서는 기억하지 못할 뿐이다.
  }
}

export const session = {
  token: (): string | null => read(TOKEN_KEY),
  setToken: (token: string | null) => write(TOKEN_KEY, token && token.trim() ? token.trim() : null),
  /** 기본은 타일 없음(ADR-057 결정 1). OSM 은 데모용 선택이다. */
  tiles: (): Tiles => (read(TILES_KEY) === 'osm' ? 'osm' : 'none'),
  setTiles: (tiles: Tiles) => write(TILES_KEY, tiles === 'osm' ? 'osm' : null),
};

/** 토큰의 페이로드를 읽는다 — 검증은 ops-api 가 한다. 화면은 역할과 만료를 보여 줄 뿐이다. */
export function describeToken(token: string): { role: string | null; actor: string | null; expiresAt: Date | null } {
  try {
    const part = token.split('.')[1];
    if (!part) {
      return { role: null, actor: null, expiresAt: null };
    }
    const payload = JSON.parse(atob(part.replace(/-/g, '+').replace(/_/g, '/'))) as Record<string, unknown>;
    const roles = payload['roles'];
    return {
      // tools/ops-token 이 찍는 모양: roles=[<ROLE>] (ops-api 의 SecurityConfig.ROLES_CLAIM).
      role: Array.isArray(roles) && typeof roles[0] === 'string' ? roles[0] : null,
      actor: typeof payload['sub'] === 'string' ? payload['sub'] : null,
      expiresAt: typeof payload['exp'] === 'number' ? new Date(payload['exp'] * 1000) : null,
    };
  } catch {
    return { role: null, actor: null, expiresAt: null };
  }
}
