import { Link, NavLink } from 'react-router'
import Icon from './Icon.jsx'
import LogoutButton from './LogoutButton.jsx'
import SettingsMenu from './SettingsMenu.jsx'
import { paths } from '../paths.js'
import { useAuthStore } from '../store/auth.js'

// 모든 화면 위 헤더(디자인 시스템 AppHeader). variant: guest(로그인 전) · member(로그인 후)
function AppHeader({ variant }) {
  const member = variant === 'member'
  const nickname = useAuthStore((state) => state.member?.nickname)

  return (
    <header className="mt-hd">
      <div className="mt-hd__in">
        <Link className="mt-hd__logo" to={member ? paths.home : paths.welcome}>
          <span className="mt-hd__mark">
            <Icon name="pill" />
          </span>
          필받음
        </Link>
        {member ? (
          <>
            <nav className="mt-hd__nav" aria-label="주 메뉴">
              <NavLink to={paths.home} end>
                홈
              </NavLink>
              <NavLink to={paths.drugs}>약 목록</NavLink>
              <NavLink to={paths.calendar}>복용 달력</NavLink>
              <NavLink to={paths.chat}>챗봇</NavLink>
              <SettingsMenu />
            </nav>
            {/* 관리할 사람(PRF-04)은 프로필 화면을 만들 때 넣는다. */}
            <div className="mt-hd__r">
              {nickname && <span className="mt-hd__nick">{nickname}님</span>}
              <LogoutButton />
            </div>
          </>
        ) : (
          <div className="mt-hd__r">
            <Link className="mt-btn mt-btn--ghost" to={paths.login}>
              로그인
            </Link>
            <Link className="mt-btn mt-btn--solid mt-btn--md" to={paths.signup}>
              시작하기
            </Link>
          </div>
        )}
      </div>
    </header>
  )
}

export default AppHeader
