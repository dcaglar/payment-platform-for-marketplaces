import { StrictMode } from 'react';
import { createRoot } from 'react-dom/client';
import { BrowserRouter } from 'react-router-dom';
import { App } from './App';
import { LoginPage } from './features/session/LoginPage';
import { SessionProvider } from './features/session/session';
import './styles.css';

createRoot(document.getElementById('root')!).render(
  <StrictMode>
    <BrowserRouter>
      <SessionProvider loggedOut={<LoginPage />}>
        <App />
      </SessionProvider>
    </BrowserRouter>
  </StrictMode>
);
