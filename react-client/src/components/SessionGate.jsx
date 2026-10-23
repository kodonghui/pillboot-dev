import { useEffect, useRef, useState } from 'react'
import { Outlet } from 'react-router'
import { getMe } from '../api/auth.js'
import { useAuthStore } from '../store/auth.js'

// 새로고침 뒤에는 저장된 토큰을 GET /api/auth/me 로 확인한 다음에 화면을 그린다(docs/auth-logout.md).
function SessionGate() {
  const [checking, setChecking] = useState(() => useAuthStore.getState().token !== null)
  // StrictMode 에서 효과가 두 번 돌아도 요청은 한 번만 보낸다.
  const started = useRef(false)

  useEffect(() => {
    if (!checking || started.current) return
    started.current = true
    getMe()
      .then((member) => useAuthStore.getState().setMember(member))
      // 401 은 client 가 로그인 만료로 처리한다. 네트워크 오류 · 500 은 저장된 로그인을 그대로 쓴다.
      .catch(() => {})
      .finally(() => setChecking(false))
  }, [checking])

  return checking ? null : <Outlet />
}

export default SessionGate
