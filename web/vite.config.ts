import { defineConfig } from 'vite'
import { tanstackStart } from '@tanstack/react-start/plugin/vite'
import viteReact from '@vitejs/plugin-react'

// Development: `bun run dev` proxies the API to a running server's web map (default port 8790).
const api = process.env.FASTDIFFUSION_API ?? 'http://127.0.0.1:8790'

export default defineConfig({
  server: {
    port: 3000,
    proxy: { '/api': { target: api, changeOrigin: true } },
  },
  plugins: [tanstackStart({ spa: { enabled: true } }), viteReact()],
})
