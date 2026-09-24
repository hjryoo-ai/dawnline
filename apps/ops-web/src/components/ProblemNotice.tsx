import type { Problem } from '../api/client';

export interface Failure {
  status: number;
  problem: Problem | null;
}

/**
 * 실패를 운영자의 말로. 401 과 403 은 상태가 아니라 `code` 로 가른다(ADR-056 기준 6) — 401 은 토큰을 다시 넣으라는
 * 뜻이고 403 은 그 역할로는 안 된다는 뜻이다(§5.5). 본문이 없으면(프록시의 오류 등) 상태로 물러난다.
 */
export function ProblemNotice({ failure }: { failure: Failure | null }) {
  if (!failure) {
    return null;
  }
  const code = failure.problem?.code ?? null;
  if (code === 'unauthenticated' || (code === null && failure.status === 401)) {
    return (
      <p role="alert" className="notice notice-auth">
        토큰이 없거나 만료됐습니다. <a href="#/settings">설정</a>에서 토큰을 다시 넣으세요.
      </p>
    );
  }
  if (code === 'forbidden' || (code === null && failure.status === 403)) {
    return (
      <p role="alert" className="notice notice-auth">
        이 역할로는 할 수 없습니다 — 커맨드는 <code>OPS_OPERATOR</code> 이상이 필요합니다.
      </p>
    );
  }
  if (failure.status === 0) {
    return (
      <p role="alert" className="notice">
        ops-api 에 닿지 않았습니다. 스택이 떠 있는지 확인하세요.
      </p>
    );
  }
  const detail = failure.problem?.detail ?? failure.problem?.title ?? '';
  return (
    <p role="alert" className="notice">
      {failure.status} {code ?? ''} {detail}
    </p>
  );
}
