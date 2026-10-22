import { Link, NavLink, useLocation, useMatches } from 'react-router'
import Icon from './Icon.jsx'
import LogoutButton from './LogoutButton.jsx'
import ProfileSwitcher from './ProfileSwitcher.jsx'
import SettingsMenu from './SettingsMenu.jsx'
import { paths } from '../paths.js'
import { useAuthStore } from '../store/auth.js'

// 모든 화면 위 헤더(디자인 시스템 AppHeader). variant: guest(로그인 전) · member(로그인 후)
// 관리할 사람(PRF-04)은 지금 화면 라우트의 handle.profileSwitcher 에 따라 보이거나 잠그거나 숨긴다(router.js).
// 주 메뉴의 지금 위치는 주소로 정하고, 라우트에 handle.menu 가 있으면 그 함수가 고른 메뉴로 정한다(router.js).
function AppHeader({ variant }) {
  const member = variant === 'member'
  const nickname = useAuthStore((state) => state.member?.nickname)
  const location = useLocation()
  const matches = useMatches()
  const switcher = matches.findLast((match) => match.handle?.profileSwitcher)?.handle.profileSwitcher
  const menu = matches.findLast((match) => match.handle?.menu)?.handle.menu(location)

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
              <MenuLink to={paths.home} menu={menu} end>
                홈
              </MenuLink>
              <MenuLink to={paths.drugs} menu={menu}>
                약 목록
              </MenuLink>
              <MenuLink to={paths.calendar} menu={menu}>
                복용 달력
              </MenuLink>
              <MenuLink to={paths.chat} menu={menu}>
                챗봇
              </MenuLink>
              <SettingsMenu />
            </nav>
            <div className="mt-hd__r">
              {switcher && <ProfileSwitcher locked={switcher === 'locked'} />}
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

// 주 메뉴 링크. menu(라우트가 고른 지금 메뉴 주소)가 없으면 NavLink 가 주소로 지금 위치를 표시한다.
function MenuLink({ to, end, menu, children }) {
  if (!menu) {
    return (
      <NavLink to={to} end={end}>
        {children}
      </NavLink>
    )
  }
  return (
    <Link to={to} aria-current={menu === to ? 'page' : undefined}>
      {children}
    </Link>
  )
}

export default AppHeader
