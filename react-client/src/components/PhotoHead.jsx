import { Link } from 'react-router'
import { paths } from '../paths.js'
import Icon from './Icon.jsx'
import ProfileTag from './ProfileTag.jsx'
import './PhotoHead.css'

// 사진 등록 화면(IMG-01~06) 머리: 등록 방법으로 돌아가는 링크, 제목, 관리할 사람과 안내 한 줄.
// locked 면 결과 화면처럼 "관리할 사람을 바꿀 수 없어요" 줄을 붙인다. onLeave 는 돌아가기 전에 할 정리(사진 지우기 등)다.
function PhotoHead({ title, profile, color, text = '약 목록에 넣어요', locked, onLeave }) {
  return (
    <div className="photo-head">
      <Link className="mt-btn mt-btn--ghost photo-head__back" to={paths.drugNew} onClick={onLeave}>
        <Icon name="chevronLeft" size="s" />
        등록 방법으로
      </Link>
      <h1 className="screen-title">{title}</h1>
      <div className="photo-head__who">
        <ProfileTag color={color}>{profile.profileName}</ProfileTag>
        <span className="screen-body photo-head__text">{text}</span>
        {locked && (
          <span className="screen-caption photo-head__lock">
            <Icon name="lockSmall" size="s" />
            등록이 끝날 때까지 관리할 사람을 바꿀 수 없어요
          </span>
        )}
      </div>
    </div>
  )
}

export default PhotoHead
