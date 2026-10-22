import { useEffect, useId, useRef, useState } from 'react'
import { matchPath, NavLink, useLocation } from 'react-router'
import Icon from './Icon.jsx'
import { paths } from '../paths.js'
import './SettingsMenu.css'

const items = [
  { to: paths.profiles, icon: 'user', label: '프로필 관리' },
  { to: paths.notifications, icon: 'bell', label: '알림' },
]

// 주 메뉴의 "설정". 화면이 아니라 프로필 관리 · 알림 두 줄을 펼치는 드롭다운이다.
function SettingsMenu() {
  const location = useLocation()
  // 연 주소를 기억해 두면 다른 화면으로 옮겨 갈 때 저절로 닫힌다.
  const [openPath, setOpenPath] = useState(null)
  const open = openPath === location.pathname
  const current = items.some((item) => matchPath({ path: item.to, end: false }, location.pathname))
  const wrapperRef = useRef(null)
  const toggleRef = useRef(null)
  const panelId = useId()

  useEffect(() => {
    if (!open) return
    const closeOnOutsidePress = (event) => {
      if (!wrapperRef.current.contains(event.target)) setOpenPath(null)
    }
    const closeOnEscape = (event) => {
      if (event.key !== 'Escape') return
      setOpenPath(null)
      toggleRef.current.focus()
    }
    document.addEventListener('pointerdown', closeOnOutsidePress)
    document.addEventListener('keydown', closeOnEscape)
    return () => {
      document.removeEventListener('pointerdown', closeOnOutsidePress)
      document.removeEventListener('keydown', closeOnEscape)
    }
  }, [open])

  const closeOnFocusOut = (event) => {
    if (!event.currentTarget.contains(event.relatedTarget)) setOpenPath(null)
  }

  return (
    <div className="settings-menu" ref={wrapperRef} onBlur={closeOnFocusOut}>
      <button
        ref={toggleRef}
        className="settings-menu__toggle"
        type="button"
        aria-expanded={open}
        aria-controls={open ? panelId : undefined}
        aria-current={current ? 'page' : undefined}
        onClick={() => setOpenPath(open ? null : location.pathname)}
      >
        설정
        <Icon name={open ? 'chevronUp' : 'chevronDown'} size="s" />
      </button>
      {open && (
        <ul className="mt-hd__menu settings-menu__panel" id={panelId}>
          {items.map((item) => (
            <li key={item.to}>
              <NavLink to={item.to} onClick={() => setOpenPath(null)}>
                {({ isActive }) => (
                  <>
                    <Icon name={item.icon} />
                    {item.label}
                    {isActive && (
                      <span className="screen-label settings-menu__now">
                        <Icon name="check" size="s" />
                        지금
                      </span>
                    )}
                  </>
                )}
              </NavLink>
            </li>
          ))}
        </ul>
      )}
    </div>
  )
}

export default SettingsMenu
