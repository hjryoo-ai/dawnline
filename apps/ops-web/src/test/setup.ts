/**
 * jsdom 에서 Leaflet 이 서게 하는 두 가지 — 둘 다 브라우저에는 있고 jsdom 에는 없다.
 * 1. SVG 렌더러: Leaflet 은 import 때 `createSVGRect` 로 SVG 지원을 판정하고, 없으면 폴리라인을 그릴 렌더러가 없다.
 * 2. 크기: 지도는 컨테이너 크기로 줌과 타일을 정한다. jsdom 은 레이아웃이 없어 늘 0 이다.
 */
import { cleanup } from '@testing-library/react';
import { afterEach } from 'vitest';

Object.defineProperty(window.SVGSVGElement.prototype, 'createSVGRect', {
  configurable: true,
  value: () => ({ x: 0, y: 0, width: 0, height: 0 }),
});
Object.defineProperty(window.HTMLElement.prototype, 'clientWidth', { configurable: true, get: () => 800 });
Object.defineProperty(window.HTMLElement.prototype, 'clientHeight', { configurable: true, get: () => 600 });

afterEach(() => {
  cleanup();
  window.sessionStorage.clear();
  window.location.hash = '';
});
