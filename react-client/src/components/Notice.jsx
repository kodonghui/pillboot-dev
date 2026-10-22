import Icon from './Icon.jsx'
import './Notice.css'

const icons = { info: 'info', caution: 'alertCircle', negative: 'alertCircle', positive: 'checkCircle' }

// 화면 안에 남는 안내 · 주의 · 오류 · 완료 블록(디자인 시스템 Notice). action 은 오른쪽에 두는 버튼 하나다.
function Notice({ variant, role, id, action, children }) {
  return (
    <div className={`mt-notice mt-notice--${variant}${action ? ' notice--with-action' : ''}`} role={role} id={id}>
      <Icon name={icons[variant]} className="mt-notice__icon" />
      <p className="mt-notice__text">{children}</p>
      {action}
    </div>
  )
}

export default Notice
