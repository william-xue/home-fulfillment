import vue from '@vitejs/plugin-vue'
import { defineConfig } from 'vite'

const frontendPort = Number(process.env.HF_FRONTEND_PORT || 5180)

export default defineConfig({
  plugins: [
    vue(),
    {
      name: 'local-demo-login',
      apply: 'serve',
      configureServer(server) {
        const password = process.env.HF_DEMO_PASSWORD
        if (process.env.HF_SEED !== 'true' || !password || server.config.server.host !== '127.0.0.1') return

        // Keep demo credentials out of transformed modules and production builds.
        server.middlewares.use((req, res, next) => {
          if (req.url !== '/__local-demo-login') return next()
          res.setHeader('Cache-Control', 'no-store')
          if (req.method !== 'GET' || req.headers.host !== `127.0.0.1:${frontendPort}`
              || req.headers['sec-fetch-site'] !== 'same-origin' || req.headers['sec-fetch-mode'] !== 'same-origin') {
            res.statusCode = 404
            res.end()
            return
          }
          res.setHeader('Content-Type', 'application/json; charset=utf-8')
          res.setHeader('Cross-Origin-Resource-Policy', 'same-origin')
          res.end(JSON.stringify({ username: 'warehouse-sh', password }))
        })
      },
    },
  ],
  server: {
    host: '127.0.0.1',
    port: frontendPort,
    strictPort: true,
    proxy: {
      // M0 exposes only the health route. M1's business APIs stay under /api/v1.
      '/api/health': {
        target: `http://127.0.0.1:${process.env.HF_BACKEND_PORT || 8090}`,
        rewrite: () => '/actuator/health',
      },
      '/api/v1': `http://127.0.0.1:${process.env.HF_BACKEND_PORT || 8090}`,
    },
  },
})
