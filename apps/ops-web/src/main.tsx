import { StrictMode } from 'react';
import { createRoot } from 'react-dom/client';
import 'leaflet/dist/leaflet.css';
import './styles.css';
import { App } from './App';
import { createApi } from './api/client';
import { session } from './session';

const api = createApi({ token: () => session.token() });

createRoot(document.getElementById('root')!).render(
  <StrictMode>
    <App api={api} />
  </StrictMode>,
);
