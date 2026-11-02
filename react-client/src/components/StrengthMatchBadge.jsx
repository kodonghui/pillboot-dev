import Icon from './Icon.jsx'
import './StrengthName.css'

// 검색어 · 처방전에서 읽은 함량과 후보 함량의 비교(V3-REG-002, 화면정의서 v3-1-drug-strength A안).
// 확인 불가(UNKNOWN)는 근거 없는 경고를 늘리지 않으려고 표시하지 않는다. 색만으로 나누지 않고 아이콘과 글자를 함께 쓴다.
function StrengthMatchBadge({ match }) {
  if (match === 'MATCH') {
    return (
      <span className="mt-badge mt-badge--positive strength-match">
        <Icon name="check" size="s" />
        함량 일치
      </span>
    )
  }
  if (match === 'DIFFERENT') {
    return (
      <span className="mt-badge mt-badge--caution strength-match">
        <Icon name="alertTriangle" size="s" />
        함량 다름
      </span>
    )
  }
  return null
}

export default StrengthMatchBadge
