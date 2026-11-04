import { useId } from 'react'
import { Link } from 'react-router'
import { viewedProfileName } from '../kakaoLinkInfo.js'
import { paths } from '../paths.js'
import Icon from './Icon.jsx'
import './KakaoLinkCard.css'

// PRF-01 표 위 '카카오톡 약봉이' 카드(v3-kakao-web-link.md). 연결은 계정 단위라 카드 하나로 모두 보인다.
// links: 연결 목록(불러오는 중이면 null) · failed: 불러오기 실패 · onRetry: 다시 시도
function KakaoLinkCard({ links, failed, onRetry }) {
  const titleId = useId()

  let text
  let action
  if (failed) {
    text = (
      <p className="screen-body kakao-card__desc" role="alert">
        카카오톡 연결 상태를 불러오지 못했어요.
      </p>
    )
    action = (
      <button className="mt-btn mt-btn--assistive" type="button" onClick={onRetry}>
        <Icon name="refresh" size="s" />
        다시 시도
      </button>
    )
  } else if (!links) {
    text = (
      <p className="screen-body kakao-card__desc kakao-card__busy" role="status">
        <Icon name="loader" size="s" className="icon--spin" />
        연결 상태를 불러오는 중이에요.
      </p>
    )
    action = (
      <button className="mt-btn mt-btn--assistive" type="button" disabled>
        연결 관리
        <Icon name="chevronRight" size="s" />
      </button>
    )
  } else if (links.length === 0) {
    text = <p className="screen-body kakao-card__desc">카카오톡에서도 이 계정의 프로필 약으로 물어볼 수 있어요.</p>
    action = (
      <Link className="mt-btn mt-btn--outlined" to={paths.kakaoLink}>
        카카오톡 연결하기
        <Icon name="chevronRight" size="s" />
      </Link>
    )
  } else {
    text = (
      <>
        <p className="screen-body kakao-card__desc">카카오톡 {links.length}개가 연결돼 있어요.</p>
        <p className="screen-caption kakao-card__sub">지금 보는 프로필: {links.map(viewedProfileName).join(', ')}</p>
      </>
    )
    action = (
      <Link className="mt-btn mt-btn--assistive" to={paths.kakaoLink}>
        연결 관리
        <Icon name="chevronRight" size="s" />
      </Link>
    )
  }

  return (
    <section className="mt-card kakao-card" aria-labelledby={titleId}>
      <div className="kakao-card__row">
        <div className="kakao-card__main">
          <span className="kakao-card__icon">
            <Icon name="chat" />
          </span>
          <div className="kakao-card__text">
            <h2 className="screen-item" id={titleId}>
              카카오톡 약봉이
            </h2>
            {text}
          </div>
        </div>
        {action}
      </div>
    </section>
  )
}

export default KakaoLinkCard
