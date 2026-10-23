import { generatePath, Link } from 'react-router'
import { roomTime } from '../chatInfo.js'
import { paths } from '../paths.js'
import Icon from './Icon.jsx'
import Notice from './Notice.jsx'
import ProfileTag from './ProfileTag.jsx'
import './ChatRoomList.css'

// 불러오는 동안 보이는 빈 줄 너비(목업 CHT-01-02 .skel)
const skeletonRows = [
  [88, 40],
  [72, 34],
  [80, 38],
]

// CHT-01 왼쪽 대화방 목록(목업 .pane-l). 최근에 쓴 방이 위에 온다. 제목이 아직 없는 방은 「새 대화」다.
// sessions: 지금 사람의 목록(아직 없으면 null) · failed: 마지막 불러오기 실패 · loading: 불러오는 중
// preparing · prepareFailed: 새 대화방 준비 중 · 준비 실패(목업 CHT-02-01)
function ChatRoomList({
  name,
  color,
  sessions,
  currentId,
  failed,
  loading,
  preparing,
  prepareFailed,
  onRetry,
  onNew,
  onRetryPrepare,
}) {
  const retryButton = (
    <button className="mt-btn mt-btn--assistive mt-btn--sm chat-list__action" type="button" disabled={loading} onClick={onRetry}>
      <Icon name={loading ? 'loader' : 'refresh'} size="s" className={loading ? 'icon--spin' : undefined} />
      {loading ? '다시 불러오는 중…' : '다시 시도'}
    </button>
  )

  let body
  if (!sessions && failed) {
    // 처음 불러오기 실패(CHT-01-03). 빈 목록으로 보지 않고, 새 대화방을 저절로 만들지 않는다.
    body = (
      <div className="mt-notice mt-notice--negative chat-list__failed" role="alert">
        <div className="chat-list__failed-text">
          <Icon name="alertCircle" className="mt-notice__icon" />
          <p className="mt-notice__text">대화방을 불러오지 못했어요. 잠시 후 다시 시도해 주세요.</p>
        </div>
        {retryButton}
      </div>
    )
  } else if (!sessions) {
    body = (
      <div className="chat-list__loading" role="status" aria-live="polite">
        <p className="chat-list__busy">
          <Icon name="loader" className="icon--spin" />
          대화방을 불러오는 중이에요.
        </p>
        {skeletonRows.map(([first, second]) => (
          <div className="chat-list__skeleton" aria-hidden="true" key={first}>
            <span className="chat-list__bar" style={{ width: `${first}%` }} />
            <span className="chat-list__bar" style={{ width: `${second}%` }} />
          </div>
        ))}
      </div>
    )
  } else {
    body = (
      <>
        {failed && (
          // 목록 새로 고침 실패(CHT-01-03). 전에 불러온 목록은 그대로 둔다.
          <Notice variant="caution" role="alert" action={retryButton}>
            대화방 목록을 새로 고치지 못했어요. 지금 보이는 목록은 전에 불러온 내용이에요.
          </Notice>
        )}
        <nav aria-label="대화방 목록">
          <ul className="chat-list__rooms">
            {sessions.map((session) => (
              <li key={session.sessionId}>
                <Link
                  className="chat-list__room"
                  to={generatePath(paths.chatRoom, { sessionId: String(session.sessionId) })}
                  aria-current={session.sessionId === currentId ? 'page' : undefined}
                >
                  <span className="chat-list__title">{session.title ?? '새 대화'}</span>
                  <span className="screen-caption chat-list__muted">{roomTime(session.updatedAt)}</span>
                </Link>
              </li>
            ))}
          </ul>
        </nav>
      </>
    )
  }

  return (
    <aside className="mt-card chat-list" aria-label={`${name}의 대화방`}>
      <div className="chat-list__head">
        <ProfileTag color={color}>{name}</ProfileTag>
        <span className="screen-label chat-list__label">대화방</span>
      </div>
      <button
        className="mt-btn mt-btn--assistive mt-btn--block"
        type="button"
        disabled={(!sessions && !failed) || preparing}
        onClick={onNew}
      >
        <Icon name="plus" />새 대화
      </button>
      {preparing && (
        <p className="chat-list__busy" role="status">
          <Icon name="loader" className="icon--spin" />
          대화방을 준비하고 있어요.
        </p>
      )}
      {prepareFailed && (
        <Notice
          variant="negative"
          role="alert"
          action={
            <button className="mt-btn mt-btn--assistive mt-btn--sm" type="button" onClick={onRetryPrepare}>
              <Icon name="refresh" size="s" />
              다시 시도
            </button>
          }
        >
          대화방을 준비하지 못했어요.
        </Notice>
      )}
      {body}
    </aside>
  )
}

export default ChatRoomList
