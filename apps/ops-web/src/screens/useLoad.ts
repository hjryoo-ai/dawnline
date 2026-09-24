import { useCallback, useEffect, useState } from 'react';
import type { ApiResult } from '../api/client';
import type { Failure } from '../components/ProblemNotice';

/** 조회 하나의 상태 — 읽는 중 · 값 · 실패. `reload` 는 커맨드 뒤에 다시 읽는다. */
export function useLoad<T>(load: () => Promise<ApiResult<T>>, deps: readonly unknown[]) {
  const [data, setData] = useState<T | null>(null);
  const [failure, setFailure] = useState<Failure | null>(null);
  const [loading, setLoading] = useState(true);
  const [tick, setTick] = useState(0);

  const run = useCallback(load, deps);
  useEffect(() => {
    let live = true;
    setLoading(true);
    run().then((result) => {
      if (!live) {
        return;
      }
      if (result.ok) {
        setData(result.data);
        setFailure(null);
      } else {
        setData(null);
        setFailure({ status: result.status, problem: result.problem });
      }
      setLoading(false);
    });
    return () => {
      live = false;
    };
  }, [run, tick]);

  return { data, failure, loading, reload: () => setTick((n) => n + 1) };
}
