import '../profileColors.css'
import './ProfileTag.css'

// 사람 이름 + 색 점(디자인 시스템 ProfileTag). color 는 profileInfo.js 의 profileColor 값(1~8)이고, 없으면 1번 색이다.
function ProfileTag({ color, children }) {
  return <span className={`mt-ptag profile-tag profile-color--${color ?? 1}`}>{children}</span>
}

export default ProfileTag
