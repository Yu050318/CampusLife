import { defineConfig, loadEnv } from 'vite'
import uni from '@dcloudio/vite-plugin-uni'

export default defineConfig(({ mode }) => {
  // Secrets are loaded by the dev server only; never use a VITE_ prefix for securityJsCode.
  const env = { ...loadEnv(mode, process.cwd(), ''), ...process.env }
  const sdkPaths = /^\/_AMapService\/v3\/(ip|geocode\/regeo|assistant\/coordinate\/convert)(?:\?|$)/
  return {
  plugins: [uni()],
  server: {
    host: '127.0.0.1',
    port: 5173,
    strictPort: true,
    proxy: {
      '^/api/nearby/pois(?:\\?|$)': {
        target: env.NEARBY_BACKEND_URL || 'http://127.0.0.1:8081',
        rewrite: path => path.replace(/^\/api/, ''),
        configure(proxy) {
          proxy.on('proxyReq', (request, incoming) => {
            request.removeHeader('x-forwarded-for')
            request.removeHeader('forwarded')
            request.setHeader('X-Real-IP', incoming.socket.remoteAddress || '')
          })
        }
      },
      '/_AMapService': {
        target: 'https://restapi.amap.com', changeOrigin: true,
        bypass(request, response) {
          if (!sdkPaths.test(request.url) || !env.AMAP_JS_SECURITY_CODE || !env.VITE_AMAP_JS_KEY) {
            response.statusCode = 503
            response.end('Map service unavailable')
            return false
          }
        },
        rewrite: path => path.replace(/^\/_AMapService/, ''),
        configure(proxy) {
          proxy.on('proxyReq', request => {
            const upstream = new URL(request.path, 'https://restapi.amap.com')
            upstream.searchParams.set('jscode', env.AMAP_JS_SECURITY_CODE)
            upstream.searchParams.set('key', env.VITE_AMAP_JS_KEY)
            request.path = upstream.pathname + upstream.search
            request.removeHeader('authorization')
            request.removeHeader('cookie')
          })
        }
      }
    }
  }
  }
})
