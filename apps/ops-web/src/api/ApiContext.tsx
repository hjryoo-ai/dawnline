import { createContext, useContext, type ReactNode } from 'react';
import type { Api } from './client';

const ApiContext = createContext<Api | null>(null);

export function ApiProvider({ api, children }: { api: Api; children: ReactNode }) {
  return <ApiContext.Provider value={api}>{children}</ApiContext.Provider>;
}

export function useApi(): Api {
  const api = useContext(ApiContext);
  if (!api) {
    throw new Error('ApiProvider 안에서만 쓴다');
  }
  return api;
}
