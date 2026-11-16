import { defineConfig } from 'vite';
import react from '@vitejs/plugin-react';

// The bot serves the API on a random loopback port (see the "Web GUI listening on" log line).
// Set JMB_API=http://127.0.0.1:<port> before pnpm dev to proxy /api to it.
const api = process.env.JMB_API;

export default defineConfig({
  plugins: [react()],
  base: '/',
  server: api
    ? { proxy: { '/api': { target: api, changeOrigin: true, headers: { origin: api } } } }
    : undefined,
  build: {
    outDir: 'dist',
    emptyOutDir: true,
  },
});