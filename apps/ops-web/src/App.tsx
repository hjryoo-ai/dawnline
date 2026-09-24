import { useEffect, useState } from 'react';
import { ApiProvider } from './api/ApiContext';
import type { Api } from './api/client';
import { Dashboard } from './screens/Dashboard';
import { RouteMap } from './screens/RouteMap';
import { Settings } from './screens/Settings';

/** 화면 둘 + 설정 하나(ADR-057 결정 3). 주소는 해시다 — nginx 가 어느 경로든 index.html 을 주지 않아도 된다. */
type Screen = { name: 'dashboard' } | { name: 'settings' } | { name: 'map'; waveId: string };

export function parseHash(hash: string): Screen {
  const path = hash.replace(/^#/, '');
  if (path === '/settings') {
    return { name: 'settings' };
  }
  const map = /^\/waves\/([^/]+)$/.exec(path);
  if (map?.[1]) {
    return { name: 'map', waveId: decodeURIComponent(map[1]) };
  }
  return { name: 'dashboard' };
}

export function App({ api }: { api: Api }) {
  const [screen, setScreen] = useState<Screen>(() => parseHash(window.location.hash));
  useEffect(() => {
    const onHash = () => setScreen(parseHash(window.location.hash));
    window.addEventListener('hashchange', onHash);
    return () => window.removeEventListener('hashchange', onHash);
  }, []);

  return (
    <ApiProvider api={api}>
      <header className="top">
        <strong>Dawnline 운영</strong>
        <nav>
          <a href="#/">대시보드</a>
          <a href="#/settings">설정</a>
        </nav>
      </header>
      <main>
        {screen.name === 'dashboard' && <Dashboard />}
        {screen.name === 'settings' && <Settings />}
        {screen.name === 'map' && <RouteMap waveId={screen.waveId} />}
      </main>
    </ApiProvider>
  );
}
