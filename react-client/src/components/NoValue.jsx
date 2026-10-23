import VisuallyHidden from './VisuallyHidden.jsx'
import './NoValue.css'

// 값이 없는 칸(목업 "-"). 화면 읽기에는 "정보 없음"으로 읽힌다.
function NoValue() {
  return (
    <span className="no-value">
      <span aria-hidden="true">-</span>
      <VisuallyHidden>정보 없음</VisuallyHidden>
    </span>
  )
}

export default NoValue
