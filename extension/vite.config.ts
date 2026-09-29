import { defineConfig } from 'vite';
import react from '@vitejs/plugin-react';
import { resolve } from 'node:path';

export default defineConfig({
  plugins: [react()],
  build: {
    // Two entry points: the inline field and the builder dialog.
    rollupOptions: {
      input: {
        index: resolve(import.meta.dirname, 'index.html'),
        dialog: resolve(import.meta.dirname, 'dialog.html'),
      },
    },
    outDir: 'dist',
    emptyOutDir: true,
  },
  server: { port: 5173, cors: true },
});
