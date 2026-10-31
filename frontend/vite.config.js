import { defineConfig } from 'vite'
import react from '@vitejs/plugin-react'

export default defineConfig({
  plugins: [react()],
  server: {
    port: 3000,
    strictPort: true, // Force port 3000, error if occupied
    proxy: {
      '/api': {
        target: 'http://localhost:3001',
        changeOrigin: true,
      },
      // Uploaded profile pictures are stored on the backend and served from
      // /uploads/** inside the /api servlet context (/api/uploads/**).
      '/uploads': {
        target: 'http://localhost:3001',
        changeOrigin: true,
        rewrite: (path) => '/api' + path,
      },
    },
  },
  test: {
    globals: true,
    environment: 'jsdom',
    setupFiles: './src/test/setup.js',
  },
  build: {
    outDir: 'dist',
    sourcemap: false,
    chunkSizeWarningLimit: 600,
    rollupOptions: {
      output: {
        manualChunks(id) {
          if (id.includes('node_modules')) {
            if (id.includes('react') || id.includes('react-dom') || id.includes('react-router-dom')) {
              return 'vendor-react';
            }
            if (id.includes('recharts') || id.includes('d3-') || id.includes('victory-vendor')) {
              return 'vendor-charts';
            }
            if (id.includes('react-icons')) {
              return 'vendor-icons';
            }
            if (id.includes('axios') || id.includes('dayjs')) {
              return 'vendor-utils';
            }
            return 'vendor-other';
          }
        },
      },
    },
  },
})
