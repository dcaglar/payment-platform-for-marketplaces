import { defineConfig } from 'vite';
import react from '@vitejs/plugin-react';

// The page and its server share one origin (localhost:3100): Vite forwards /auth and /api to the server on 3101,
// so the session cookie is first-party and the browser never calls Keycloak's token endpoint or our API itself.
export default defineConfig({
  plugins: [react()],
  server: {
    port: 3100,
    strictPort: true,
    proxy: {
      '/auth': 'http://localhost:3101',
      '/api': 'http://localhost:3101',
    },
  },
});
