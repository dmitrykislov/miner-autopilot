/// <reference types="vitest/config" />
import { defineConfig } from 'vitest/config'
import react from '@vitejs/plugin-react'

// Ports are externalised via env (see .env / .env.example), sourced by start.sh:
//   FRONTEND_PORT — Vite dev server port (default 5173)
//   SERVER_PORT   — backend port the dev proxy forwards /api to (default 8080)
//   TLS_ENABLED   — the backend serves HTTPS by default, so the proxy must match its scheme
const FRONTEND_PORT = parseInt(process.env.FRONTEND_PORT || '5173', 10)
const BACKEND_PORT = process.env.SERVER_PORT || '8080'
const TLS = (process.env.TLS_ENABLED || 'true') !== 'false'

// The production build is emitted straight into the launcher module's static folder,
// so `mvn package` bundles the UI inside the runnable jar (served at "/").
export default defineConfig({
  plugins: [react()],
  build: {
    outDir: '../autopilot-launcher/src/main/resources/static',
    emptyOutDir: true,
  },
  server: {
    port: FRONTEND_PORT,
    proxy: {
      // SSE needs no buffering; changeOrigin keeps the Host header sane. `secure: false` accepts the
      // backend's self-signed dev certificate (the proxy is localhost-to-localhost).
      '/api': {
        target: `${TLS ? 'https' : 'http'}://localhost:${BACKEND_PORT}`,
        changeOrigin: true,
        secure: false,
      },
    },
  },
  test: {
    environment: 'jsdom',
    globals: true,
    setupFiles: './src/test/setup.js',
    css: false,
    include: ['src/**/*.{test,spec}.{js,jsx}'],
  },
})
