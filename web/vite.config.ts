import { defineConfig } from 'vite'
import react from '@vitejs/plugin-react'

// The build output is served from the Ktor jar. Gradle picks up build/web-resources as a resource
// root, so the assets land under /web inside the jar. No JavaScript runtime ships.
export default defineConfig({
  plugins: [react()],
  server: {
    port: 5173,
    proxy: {
      '/api': 'http://localhost:8080',
    },
  },
  build: {
    outDir: '../build/web-resources/web',
    emptyOutDir: true,
    // Two entries, not one route tree: a recipient opening a link must not download the authoring
    // application, and the viewer's budget is 90 kB gzipped for everything it loads (SDD.md 7.3).
    rollupOptions: {
      input: {
        app: 'index.html',
        viewer: 'viewer.html',
      },
    },
    manifest: true,
  },
})
