import axios from 'axios'
import { useAuthStore } from '../store/auth.js'

// 필받음 백엔드 개발 서버. 환경마다 주소가 달라지면 환경 변수로 바꾼다.
const client = axios.create({ baseURL: 'http://localhost:8080' })

// 저장된 토큰을 Bearer 헤더로 붙인다. 호출하는 쪽에서 헤더를 직접 넣었으면 그대로 둔다.
client.interceptors.request.use((config) => {
  const { token } = useAuthStore.getState()
  if (token && !config.headers.Authorization) config.headers.Authorization = `Bearer ${token}`
  return config
})

// 지금 저장된 토큰으로 보낸 요청이 401 이면 로그인 만료로 처리한다(docs/auth-logout.md).
// 로그인 실패(LOGIN_FAILED), 토큰 없이 보낸 요청, 로그아웃 · 만료로 이미 지운 토큰의 늦은 401 은 넘긴다.
// 그래서 여러 요청이 한꺼번에 401 을 받아도 처리는 한 번만 일어난다.
client.interceptors.response.use(undefined, (error) => {
  const { token, expire } = useAuthStore.getState()
  if (
    error.response?.status === 401 &&
    error.response.data?.code !== 'LOGIN_FAILED' &&
    token &&
    error.config?.headers?.Authorization === `Bearer ${token}`
  ) {
    expire()
  }
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
