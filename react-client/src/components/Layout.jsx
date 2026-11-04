import { useState } from 'react'
import { Link, Navigate, Outlet, ScrollRestoration, useLocation, useMatches } from 'react-router'
import AppHeader from './AppHeader.jsx'
import { paths } from '../paths.js'
import { useAuthStore } from '../store/auth.js'
import './Layout.css'

function Layout({ variant }) {
  const footer = useMatches().some((match) => match.handle?.footer)
  const { pathname } = useLocation()

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
            <p className="screen-caption layout-footer__end">
              {pathname === paths.welcome && (
                <Link className="layout-footer__link" to={paths.changelog}>
                  업데이트 기록
                </Link>
              )}
              필받음
            </p>
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

// 로그인 여부와 상관없이 여는 화면(CMN-04). 머리말만 로그인 여부를 따른다.
export function PublicLayout() {
  const signedIn = useAuthStore((state) => state.token !== null)
  return <Layout variant={signedIn ? 'member' : 'guest'} />
}

// 로그인 후 화면. 로그인 정보가 없으면 로그아웃은 로그인, 만료는 로그인 만료 안내,
// 처음 들어온 홈은 시작 화면, 그 밖의 주소는 로그인으로 보낸다(docs/auth-logout.md).
// 로그인하지 않은 채 주소로 바로 들어왔으면 그 주소를 로그인 화면에 넘겨 로그인 뒤 돌아오게 한다.
export function MemberLayout() {
  const token = useAuthStore((state) => state.token)
  const exitReason = useAuthStore((state) => state.exitReason)
  const { pathname, search } = useLocation()

  if (!token) {
    if (exitReason === 'expired') return <Navigate to={paths.sessionExpired} replace />
    if (exitReason === null && pathname === paths.home) return <Navigate to={paths.welcome} replace />
    const state = exitReason === null ? { from: `${pathname}${search}` } : undefined
    return <Navigate to={paths.login} replace state={state} />
  }
  return <Layout variant="member" />
}
