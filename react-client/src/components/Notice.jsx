import Icon from './Icon.jsx'
import './Notice.css'

const icons = { info: 'info', caution: 'alertCircle', negative: 'alertCircle', positive: 'checkCircle' }

// 화면 안에 남는 안내 · 주의 · 오류 · 완료 블록(디자인 시스템 Notice). action 은 오른쪽에 두는 버튼 하나다.
// variant 가 없으면 회색 일반 안내(mt-notice)다. icon 으로 변형별 기본 아이콘을 바꿀 수 있다.
function Notice({ variant, icon, role, id, action, children }) {
  const classes = ['mt-notice', variant && `mt-notice--${variant}`, action && 'notice--with-action'].filter(Boolean)

  return (
    <div className={classes.join(' ')} role={role} id={id}>
      <Icon name={icon ?? icons[variant] ?? 'info'} className="mt-notice__icon" />
      <p className="mt-notice__text">{children}</p>
      {action}
    </div>
  )
}

export default Notice
