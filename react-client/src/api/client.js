import axios from 'axios'
import { useAuthStore } from '../store/auth.js'

// 필받음 백엔드 개발 서버. 환경마다 주소가 달라지면 환경 변수로 바꾼다.
// 백엔드 주소. 비워 두면 화면과 같은 주소로 보내고, 개발 서버에서는 vite.config.js 의 프록시가 localhost:8080 으로 넘긴다.
// 화면과 백엔드 주소가 다른 곳에서는 VITE_API_BASE_URL 로 정한다. fetch 로 보내는 요청(chat.js)도 이 주소를 쓴다.
export const apiBase = import.meta.env.VITE_API_BASE_URL ?? ''
const client = axios.create({ baseURL: apiBase })

// 저장된 토큰의 Bearer 헤더 값. 토큰이 없으면 비어 있다.
export function bearer() {
  const { token } = useAuthStore.getState()
  return token ? `Bearer ${token}` : undefined
}

// 저장된 토큰을 Bearer 헤더로 붙인다. 호출하는 쪽에서 헤더를 직접 넣었으면 그대로 둔다.
client.interceptors.request.use((config) => {
  const authorization = bearer()
  if (authorization && !config.headers.Authorization) config.headers.Authorization = authorization
  return config
})

// 지금 저장된 토큰으로 보낸 요청이 401 이면 로그인 만료로 처리한다(docs/auth-logout.md).
// 로그인 실패(LOGIN_FAILED), 토큰 없이 보낸 요청, 로그아웃 · 만료로 이미 지운 토큰의 늦은 401 은 넘긴다.
// 그래서 여러 요청이 한꺼번에 401 을 받아도 처리는 한 번만 일어난다. sentAuthorization: 요청에 붙였던 헤더 값
export function expireOn401(status, code, sentAuthorization) {
  const { token, expire } = useAuthStore.getState()
  if (status === 401 && code !== 'LOGIN_FAILED' && token && sentAuthorization === `Bearer ${token}`) expire()
}

client.interceptors.response.use(undefined, (error) => {
  expireOn401(error.response?.status, error.response?.data?.code, error.config?.headers?.Authorization)
  return Promise.reject(error)
})

// 서버 오류 응답의 상태 코드와 code · message. 네트워크 오류면 모두 비어 있다.
export function errorOf(error) {
  return {
    status: error.response?.status,
    code: error.response?.data?.code,
    message: error.response?.data?.message,
  }
}

export default client
