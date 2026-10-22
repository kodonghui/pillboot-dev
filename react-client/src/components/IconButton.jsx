import Icon from './Icon.jsx'
import './IconButton.css'

// 아이콘만 있는 44px 네모 버튼(목업 .ibtn: 이전 달 · 다음 달 · 닫기 · 1개 줄이기 등).
// 보이는 글자가 없으므로 label 이 화면 읽기의 이름이 된다.
function IconButton({ ref, icon, label, disabled, describedBy, onClick }) {
  return (
    <button
      ref={ref}
      className="icon-button"
      type="button"
      aria-label={label}
      aria-describedby={describedBy}
      disabled={disabled}
      onClick={onClick}
    >
      <Icon name={icon} />
    </button>
  )
}

export default IconButton
