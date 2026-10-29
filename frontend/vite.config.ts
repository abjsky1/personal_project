import react, { reactCompilerPreset } from '@vitejs/plugin-react'
import babel from '@rolldown/plugin-babel'
import { defineConfig } from 'vite'

// https://vite.dev/config/
export default defineConfig({
  plugins: [
    react(),
    babel({ presets: [reactCompilerPreset()] })
  ],
  // 개발 서버(npm run dev) 설정. npm run build로 만드는 결과물에는 영향이 없다.
  server: {
    // 프록시: 브라우저가 5173(Vite)으로 보낸 요청 중 '/api'로 시작하는 것은 Vite가 8080(Spring)으로 대신 전달한다.
    // 브라우저 입장에서는 5173 한 곳과만 통신하므로 CORS에 막히지 않는다.
    // → Spring에 @CrossOrigin을 붙일 필요가 없고, fetch에는 전체 주소 대신 '/api/...'만 쓰면 된다.
    // 배포 후에는 Nginx가 이 역할('/api' → Spring)을 대신하므로 프론트 코드는 그대로 쓸 수 있다.
    proxy: {
      '/api': 'http://localhost:8080',
    },
  },
})
