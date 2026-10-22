import { useId, useRef, useState } from 'react'
import { Link } from 'react-router'
import Icon from './Icon.jsx'
import ProfileDot from './ProfileDot.jsx'
import { closeOnFocusOut, useDismiss } from './useDismiss.js'
import { useManagedProfile } from './useManagedProfile.js'
import { paths } from '../paths.js'
import { profileColor, relationLabels } from '../profileInfo.js'
import { useAuthStore } from '../store/auth.js'
import './ProfileSwitcher.css'

// 헤더 "관리할 사람" 버튼과 고르기 창(PRF-04). 보이는 화면에서만 그려지므로 그때마다 프로필 목록을 새로 받는다.
// 목록을 불러오지 못하면 버튼을 그리지 않는다(401 은 client 가 로그인 만료로 처리한다).
// locked: 약 등록 도중(IMG-01~06 · MED-03)처럼 보이기만 하고 바꿀 수 없을 때
function ProfileSwitcher({ locked }) {
  const setProfileId = useAuthStore((state) => state.setProfileId)
  const { profiles, profile: current, color } = useManagedProfile()
  const [open, setOpen] = useState(false)
  const wrapperRef = useRef(null)
  const toggleRef = useRef(null)
  const panelId = useId()
  const titleId = useId()

  useDismiss(open, setOpen, wrapperRef, toggleRef)

  if (!current) return null

  const pick = (profile) => {
    setProfileId(profile.profileId)
    setOpen(false)
    toggleRef.current.focus()
  }

  const label = (
    <>
      <span className="mt-hd__who-k">관리할 사람</span>
      <ProfileDot color={color} />
      <span className="mt-hd__who-v">{current.profileName}</span>
    </>
  )

  if (locked) {
    return (
      <div className="profile-switcher">
        <button className="mt-hd__who" type="button" disabled title="약 등록을 마치거나 취소한 뒤에 바꿀 수 있어요.">
          {label}
          <Icon name="lockSmall" size="s" />
        </button>
      </div>
    )
  }

  return (
    <div className="profile-switcher" ref={wrapperRef} onBlur={closeOnFocusOut(setOpen)}>
      <button
        ref={toggleRef}
        className="mt-hd__who profile-switcher__toggle"
        type="button"
        aria-expanded={open}
        aria-controls={open ? panelId : undefined}
        aria-label={`관리할 사람 바꾸기, 지금 ${current.profileName}`}
        onClick={() => setOpen(!open)}
      >
        {label}
        <Icon name={open ? 'chevronUp' : 'chevronDown'} size="s" />
      </button>
      {open && (
        <section className="mt-hd__menu profile-switcher__panel" id={panelId} aria-labelledby={titleId}>
          <p className="mt-field__label profile-switcher__title" id={titleId}>
            관리할 사람
          </p>
          <ul className="profile-switcher__list">
            {profiles.map((profile) => {
              const now = profile.profileId === current.profileId
              return (
                <li key={profile.profileId}>
                  <button
                    className="profile-switcher__item"
                    type="button"
                    aria-pressed={now}
                    onClick={() => pick(profile)}
                  >
                    <ProfileDot color={profileColor(profiles, profile.profileId)} />
                    <span className="profile-switcher__name">{profile.profileName}</span>
                    <span className="screen-caption profile-switcher__relation">
                      {relationLabels[profile.relationshipType] ?? profile.relationshipType}
                    </span>
                    {now && (
                      <span className="screen-label profile-switcher__now">
                        <Icon name="check" size="s" />
                        지금
                      </span>
                    )}
                  </button>
                </li>
              )
            })}
          </ul>
          <p className="screen-caption profile-switcher__help">약 목록과 챗봇이 고른 사람 기준으로 바뀌어요.</p>
          <div className="profile-switcher__footer">
            <Link className="mt-btn mt-btn--ghost mt-btn--md profile-switcher__manage" to={paths.profiles}>
              <Icon name="users" size="s" />
              프로필 관리
            </Link>
          </div>
        </section>
      )}
    </div>
  )
}

export default ProfileSwitcher
