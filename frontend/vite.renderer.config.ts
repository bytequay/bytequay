import { defineConfig } from 'vite';
import react from '@vitejs/plugin-react';
import path from 'node:path';

// https://vitejs.dev/config
export default defineConfig({
  plugins: [react()],
  server: {
    host: '127.0.0.1',
    // Allow Vite to serve assets from the repo-level `assets/` dir (the
    // authoritative icon pack lives at <repo>/assets/icons/bytequay-icons).
    fs: {
      allow: [path.resolve(__dirname, '..')],
    },
  },
});
