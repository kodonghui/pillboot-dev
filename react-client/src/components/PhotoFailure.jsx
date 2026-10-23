import { useId } from 'react'
import Icon from './Icon.jsx'
import './PhotoFailure.css'

// 사진으로 약을 찾지 못한 화면(IMG-06). 상태 배지 · 제목 · 설명, 읽은 이름 · 단서 상자(children), 다음 행동 버튼(actions).
// icon: 아이콘 이름 · plain: 서버 · 조회 장애 · 만료처럼 사용자가 고칠 수 없는 경우의 회색 아이콘 칸
// note: 패널 아래 안내 { variant, icon, text } · caption: 패널 아래 회색 한 줄(목업 .muted)
function PhotoFailure({ icon = 'alertCircle', plain, badge, title, text, children, actions, note, caption }) {
  const titleId = useId()

  return (
    <div className="photo-failure">
      <section className="mt-card photo-failure__panel" role="alert" aria-labelledby={titleId}>
        <span className={plain ? 'photo-failure__icon photo-failure__icon--plain' : 'photo-failure__icon'}>
          <Icon name={icon} size="l" />
        </span>
        <div className="photo-failure__body">
          <span className="mt-badge photo-failure__badge">{badge}</span>
          <h2 className="screen-section" id={titleId}>
            {title}
          </h2>
          <p className="screen-body photo-failure__neutral">{text}</p>
          {children && <div className="photo-failure__box">{children}</div>}
          <div className="photo-failure__actions">{actions}</div>
        </div>
      </section>
      {note && (
        <div className={note.variant ? `mt-notice mt-notice--${note.variant}` : 'mt-notice'}>
          <Icon name={note.icon ?? 'info'} className="mt-notice__icon" />
          <p className="mt-notice__text">{note.text}</p>
        </div>
      )}
      {caption && <p className="screen-caption photo-failure__muted">{caption}</p>}
    </div>
  )
}

export default PhotoFailure
