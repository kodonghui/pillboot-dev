import { useId } from 'react'
import { Link } from 'react-router'
import Icon from './Icon.jsx'
import { paths } from '../paths.js'
import './ErrorScreen.css'

// CMN-02 오류 화면. 문구와 버튼은 목업 보드 그대로다.
const screens = {
  forbidden: {
    icon: 'lock',
    title: '권한이 없어요.',
    text: '다른 회원의 정보는 볼 수 없어요.',
    to: paths.home,
    actionIcon: 'home',
    action: '홈으로',
  },
  notFound: {
    icon: 'search',
    title: '찾을 수 없어요.',
    text: '주소가 바뀌었거나 지워진 화면이에요.',
    to: paths.home,
    actionIcon: 'home',
    action: '홈으로',
  },
  chatNotFound: {
    icon: 'chat',
    title: '대화방을 찾을 수 없어요.',
    text: '대화방이 삭제됐거나 사용할 수 없는 주소예요. 대화 목록에서 다시 선택해 주세요.',
    to: paths.chat,
    actionIcon: 'list',
    action: '대화 목록으로',
  },
}

// kind: forbidden(403) · notFound(404) · chatNotFound(CMN-02-01 없는 대화방)
function ErrorScreen({ kind }) {
  const screen = screens[kind]
  const titleId = useId()

  return (
    <section className="error-screen" aria-labelledby={titleId}>
      <span className="error-screen__icon">
        <Icon name={screen.icon} />
      </span>
      <div className="error-screen__text">
        <h1 className="screen-title" id={titleId}>
          {screen.title}
        </h1>
        <p className="screen-body error-screen__description">{screen.text}</p>
      </div>
      <Link className="mt-btn mt-btn--solid" to={screen.to}>
        <Icon name={screen.actionIcon} />
        {screen.action}
      </Link>
    </section>
  )
}

export default ErrorScreen
