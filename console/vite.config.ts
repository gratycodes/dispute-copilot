/// <reference types="vitest/config" />
import { defineConfig } from 'vite';
import react from '@vitejs/plugin-react';

// In development the API is proxied to the disputes service. In docker-compose nginx does the same.
const api = process.env.API_URL ?? 'http://localhost:8080';

export default defineConfig({
  plugins: [react()],
  server: {
    proxy: { '/api': api },
    // The policy markdown lives at the repo root and is bundled for citation tooltips.
    fs: { allow: ['..'] },
  },
  preview: { proxy: { '/api': api } },
  test: {
    include: ['src/**/*.test.{ts,tsx}'],
    environment: 'jsdom',
  },
});
