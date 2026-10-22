import '../profileColors.css'
import './ProfileDot.css'

// 프로필 색 점(목업 .dot). color 는 profileInfo.js 의 profileColor 값(1~8)이다.
function ProfileDot({ color }) {
  return <span className={`profile-dot profile-color--${color}`} aria-hidden="true" />
}

export default ProfileDot
