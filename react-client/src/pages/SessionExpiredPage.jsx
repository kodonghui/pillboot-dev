import { useId } from 'react'
import { Link } from 'react-router'
import AuthCard from '../components/AuthCard.jsx'
import Icon from '../components/Icon.jsx'
import { paths } from '../paths.js'
import './SessionExpiredPage.css'

// CMN-03 로그인 만료. 토큰을 붙인 요청이 401 을 받으면 인증 상태를 지우고 이 화면으로 온다(docs/auth-logout.md).
function SessionExpiredPage() {
  const titleId = useId()

  return (
    <AuthCard titleId={titleId} className="session-expired">
      <span className="session-expired__icon">
        <Icon name="lockSmall" size="l" />
      </span>
      <div className="session-expired__text">
        <h1 className="screen-title" id={titleId}>
          로그인이 만료됐어요
        </h1>
        <p className="session-expired__description">
          한동안 사용하지 않아서, 소중한 건강 정보를 지키려고 자동으로 로그아웃했어요. 다시 로그인해 주세요.
        </p>
        <p className="screen-caption session-expired__note">쓰던 내용은 저장되지 않았어요.</p>
      </div>
      <Link className="mt-btn mt-btn--solid mt-btn--block" to={paths.login}>
        다시 로그인
      </Link>
    </AuthCard>
  )
}

export default SessionExpiredPage
