import { useState } from 'react'
import { Navigate, Outlet, ScrollRestoration, useLocation, useMatches } from 'react-router'
import AppHeader from './AppHeader.jsx'
import { paths } from '../paths.js'
import { useAuthStore } from '../store/auth.js'
import './Layout.css'

function Layout({ variant }) {
  const footer = useMatches().some((match) => match.handle?.footer)

  return (
    <div className="screen-body">
      <AppHeader variant={variant} />
      <main className="layout-main">
        <Outlet />
      </main>
      {footer && (
        <footer className="layout-footer">
          <div className="layout-footer__in">
            <p className="screen-caption">약 정보 출처: 식품의약품안전처 공공데이터(의약품 허가정보, e약은요, 낱알식별, DUR)</p>
            <p className="screen-caption">필받음</p>
          </div>
        </footer>
      )}
      <ScrollRestoration />
    </div>
  )
}

// 로그인 전 화면(MEM-01~04, CMN-03). 로그인한 채 들어오면 홈으로 보낸다.
// 들어올 때의 로그인 여부만 보므로, 이 화면에서 로그인하면 그 화면이 다음 화면으로 직접 보낸다.
export function GuestLayout() {
  const [signedInOnEntry] = useState(() => useAuthStore.getState().token !== null)
  if (signedInOnEntry) return <Navigate to={paths.home} replace />
  return <Layout variant="guest" />
}

// 로그인 후 화면. 로그인 정보가 없으면 로그아웃은 로그인, 만료는 로그인 만료 안내,
// 처음 들어온 홈은 시작 화면, 그 밖의 주소는 로그인으로 보낸다(docs/auth-logout.md).
export function MemberLayout() {
  const token = useAuthStore((state) => state.token)
  const exitReason = useAuthStore((state) => state.exitReason)
  const { pathname } = useLocation()

  if (!token) {
    let to = paths.login
    if (exitReason === 'expired') to = paths.sessionExpired
    else if (exitReason === null && pathname === paths.home) to = paths.welcome
    return <Navigate to={to} replace />
  }
  return <Layout variant="member" />
}
