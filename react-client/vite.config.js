import react from '@vitejs/plugin-react'
import { defineConfig } from 'vite'

// https://vite.dev/config/
export default defineConfig({
  plugins: [react()],
  server: {
    // 개발 중 API 요청(/api)은 화면과 같은 주소로 보내고 Vite 가 백엔드로 넘긴다.
    // 그래서 네트워크 주소(--host)로 열어도 CORS 에 막히지 않는다.
    // changeOrigin 은 끈다(문자열 짧은 형식은 켜진다). Host 를 그대로 넘겨야 서버가 같은 출처 요청으로 본다.
    proxy: {
      '/api': { target: 'http://localhost:8080', changeOrigin: false },
    },
  },
})
