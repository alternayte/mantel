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
  },
})
