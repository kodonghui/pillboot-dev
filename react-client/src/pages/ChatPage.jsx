import { useEffect, useEffectEvent, useId, useRef, useState } from 'react'
import { generatePath, Navigate, useNavigate, useParams } from 'react-router'
import { createChatSession, getChatSessions } from '../api/chat.js'
import { getMyDrugs } from '../api/drugs.js'
import { exampleQuestions } from '../chatInfo.js'
import ChatRoom from '../components/ChatRoom.jsx'
import ChatRoomList from '../components/ChatRoomList.jsx'
import ErrorScreen from '../components/ErrorScreen.jsx'
import Icon from '../components/Icon.jsx'
import Notice from '../components/Notice.jsx'
import { useManagedProfile } from '../components/useManagedProfile.js'
import { paths } from '../paths.js'
import { useAuthStore } from '../store/auth.js'
import './ChatPage.css'

// 주소의 대화방 번호. 숫자가 아니면 없는 대화방이다.
function toSessionId(param) {
  return /^[1-9]\d{0,14}$/.test(param) ? Number(param) : null
}

// 대화방을 보여 줄 페이지 스크롤 위치. 대화방 위를 화면 위 24px(ChatRoom.css scroll-margin)에 둔다.
// 화면이 아주 낮아 그러면 입력창이 화면 아래로 나가면, 입력창 아래가 화면 아래 24px 에 오도록 더 내린다.
// 화면에 맞으면 대화방 아래도 24px 이고 그 자리가 페이지 끝이다(ChatPage.css).
function roomSpot(card) {
  const style = getComputedStyle(card)
  const top = window.scrollY + card.getBoundingClientRect().top - parseFloat(style.scrollMarginTop)
  const composer = card.querySelector('.chat-composer')
  const reach = composer
    ? window.scrollY + composer.getBoundingClientRect().bottom + parseFloat(style.scrollMarginBottom) - window.innerHeight
    : top
  return Math.min(Math.max(top, reach), document.documentElement.scrollHeight - window.innerHeight)
}

// 대화방을 고르기 전 오른쪽 안내와 추천 질문(목업 CHT-01-01). drugCount: 아직 모르면 null
function ChatIntro({ name, drugCount, locked, onAsk }) {
  const titleId = useId()
  return (
    <section className="mt-card mt-card--flush chat-intro" aria-labelledby={titleId}>
      <div className="chat-intro__body">
        <span className="chat-intro__feature">
          <Icon name="chat" size="l" />
        </span>
        <div className="chat-intro__title">
          <h2 className="screen-section" id={titleId}>
            대화방을 고르거나 새 대화를 시작하세요
          </h2>
          <p className="screen-body chat-intro__neutral">
            {drugCount === null ? `${name}의 약과` : `${name}의 약 ${drugCount}개와`} 복약 일정, 공공데이터를 근거로 답해요.
            근거를 찾지 못하면 '확인할 수 없어요'라고 답해요.
          </p>
        </div>
        <div className="chat-intro__examples">
          <p className="screen-label chat-intro__neutral">이렇게 물어볼 수 있어요</p>
          <div className="chat-intro__grid">
            {exampleQuestions.map((text) => (
              <button
                className="mt-btn mt-btn--assistive chat-intro__example"
                type="button"
                key={text}
                disabled={locked}
                onClick={() => onAsk(text)}
              >
                {text}
              </button>
            ))}
          </div>
          <p className="screen-caption chat-intro__muted">질문을 누르면 새 대화를 준비한 뒤 한 번만 보내요.</p>
        </div>
      </div>
      <div className="chat-intro__foot">
        <Notice variant="info">
          필받음은 진단이나 처방을 대신하지 않아요. 약을 바꾸거나 멈추기 전에 의사·약사와 상담하세요.
        </Notice>
      </div>
    </section>
  )
}

// CHT-01 챗봇 · CHT-02 대화방. 왼쪽은 관리할 사람의 대화방 목록, 오른쪽은 고른 대화방(없으면 시작 안내와 추천 질문)이다.
// 대화방이 하나도 없으면 새 대화방을 바로 준비한다. 새 대화방은 서버가 쓰지 않은 빈 방이 있으면 그 방을 다시 준다.
// 사람을 바꾸면 열린 방을 닫고 그 사람의 목록을 새로 불러온다. 이전 사람의 제목과 메시지는 섞어 보여 주지 않는다.
function ChatPage() {
  const { sessionId: sessionParam } = useParams()
  const navigate = useNavigate()
  const { status, profile, color, retry: retryProfiles } = useManagedProfile()
  const profileId = profile?.profileId
  const sessionId = sessionParam === undefined ? null : toSessionId(sessionParam)
  const [listKey, setListKey] = useState(0)
  // 마지막으로 받은 목록. 새로 고치다 실패해도 같은 사람의 목록이면 그대로 보여 준다.
  const [rooms, setRooms] = useState(null)
  // 마지막 목록 요청의 결과. { profileId, key, failed }
  const [listResult, setListResult] = useState(null)
  const [drugs, setDrugs] = useState(null)
  // 새 대화방 준비. { profileId, question, failed }
  const [prepare, setPrepare] = useState(null)
  // 새 방에서 한 번만 보낼 추천 질문. { sessionId, question }
  const [queued, setQueued] = useState(null)
  // 없는 대화방 · 권한 없음. { sessionId, kind }
  const [missing, setMissing] = useState(null)
  // 불러오기가 끝난 대화방(대화방 높이가 정해짐). { sessionId } · 불러올 때마다 새 값
  const [loadedRoom, setLoadedRoom] = useState(null)
  const aliveRef = useRef(false)
  const colsRef = useRef(null)
  // 마지막으로 페이지를 내려 보여 준 불러오기(loadedRoom)
  const shownRef = useRef(null)

  // 프로필을 받기 전(profileId 없음)에도 읽으므로, 받은 목록이 없으면 그 사람의 목록도 없다.
  const sessions = rooms !== null && rooms.profileId === profileId ? rooms.sessions : null
  const result = listResult !== null && listResult.profileId === profileId ? listResult : null
  const listFailed = Boolean(result?.failed)
  const listLoading = result?.key !== listKey
  // 목록을 받았거나 받지 못했고, 새로 받는 중도 아님(목록 길이가 정해짐)
  const listReady = (sessions !== null || listFailed) && !listLoading

  useEffect(() => {
    aliveRef.current = true
    return () => {
      aliveRef.current = false
    }
  }, [])

  // 대화방을 열면 대화방이 화면을 채우도록(위아래 24px, ChatRoom.css) 페이지를 대화방 자리(roomSpot)로 내린다.
  // 대화방을 다 불러온 뒤에 내린다. 불러오는 동안은 머리 · 입력창이 없어 화면이 낮으면 대화방 높이가 달라진다.
  // 좁은 화면에서는 목록이 대화방 위에 있어 목록 길이가 바뀌면 대화방이 밀리므로, 목록도 다 받은 뒤에 내린다.
  // 불러올 때마다 한 번만 내린다. 동작 줄이기 설정이면 바로 옮긴다.
  useEffect(() => {
    const card = colsRef.current?.querySelector('.chat-room')
    if (!card || !listReady || loadedRoom?.sessionId !== sessionId || shownRef.current === loadedRoom) return
    shownRef.current = loadedRoom
    const reduce = window.matchMedia('(prefers-reduced-motion: reduce)').matches
    window.scrollTo({ top: roomSpot(card), behavior: reduce ? 'auto' : 'smooth' })
  }, [sessionId, listReady, loadedRoom])

  // 대화방 자리에 있는 채로 창 너비가 바뀌면(휴대폰 · 태블릿을 돌림, 창이 좁아져 목록이 대화방 위로 감) 다시 맞춘다.
  // 크기가 바뀌기 전에 자리에 있었는지는 스크롤할 때마다 적어 둔다. 사용자가 위로 올려 둔 채면 그대로 둔다.
  // 높이만 바뀌면(화면 키보드 · 주소 표시줄) 입력칸을 가리지 않게 건드리지 않는다. 화면에 맞는 대화방은 그 자리가 페이지 끝이라 저절로 맞는다.
  useEffect(() => {
    if (sessionId === null) return
    const card = () => colsRef.current?.querySelector('.chat-room')
    const inPlace = () => {
      const node = card()
      return Boolean(node) && Math.abs(window.scrollY - roomSpot(node)) <= 1
    }
    let fitted = inPlace()
    let width = window.innerWidth
    const onScroll = () => {
      fitted = inPlace()
    }
    const onResize = () => {
      if (window.innerWidth === width) return
      width = window.innerWidth
      if (fitted && !inPlace()) window.scrollTo({ top: roomSpot(card()) })
    }
    window.addEventListener('scroll', onScroll, { passive: true })
    window.addEventListener('resize', onResize)
    return () => {
      window.removeEventListener('scroll', onScroll)
      window.removeEventListener('resize', onResize)
    }
  }, [sessionId])

  // 새 대화방을 준비하고 연다. question 이 있으면 방을 연 뒤 한 번만 보낸다(목업 CHT-01-01 추천 질문).
  const startRoom = (question) => {
    const owner = profileId
    setPrepare({ profileId: owner, question, failed: false })
    createChatSession(owner)
      .then((session) => {
        if (!aliveRef.current) return
        setPrepare((current) => (current?.profileId === owner ? null : current))
        if (useAuthStore.getState().profileId !== owner) return
        if (question) setQueued({ sessionId: session.sessionId, question })
        setListKey((key) => key + 1)
        // 페이지를 맨 위로 되돌리지 않는다. 대화방 위치로는 아래 효과가 내린다.
        navigate(generatePath(paths.chatRoom, { sessionId: String(session.sessionId) }), { preventScrollReset: true })
      })
      .catch(() => {
        if (!aliveRef.current) return
        setPrepare((current) => (current?.profileId === owner ? { ...current, failed: true } : current))
      })
  }

  // 목록을 받았다. 방이 하나도 없고 고른 방도 없으면 목록을 거치지 않고 새 대화를 연다(목업 CHT-01-01).
  const onRoomsLoaded = useEffectEvent((owner, key, sessions) => {
    setRooms({ profileId: owner, sessions })
    setListResult({ profileId: owner, key, failed: false })
    if (sessions.length === 0 && sessionParam === undefined) startRoom(null)
  })

  useEffect(() => {
    if (!profileId) return
    let active = true
    getChatSessions(profileId)
      .then((sessions) => active && onRoomsLoaded(profileId, listKey, sessions))
      .catch(() => active && setListResult({ profileId, key: listKey, failed: true }))
    return () => {
      active = false
    }
  }, [profileId, listKey])

  // 시작 안내의 약 개수. 불러오지 못하면 개수 없이 쓴다.
  useEffect(() => {
    if (!profileId) return
    let active = true
    getMyDrugs(profileId)
      .then((list) => active && setDrugs({ profileId, count: list.length }))
      .catch(() => {})
    return () => {
      active = false
    }
  }, [profileId])

  if (status === 'none') return <Navigate to={paths.profileNew} replace />
  if (sessionParam !== undefined && sessionId === null) return <ErrorScreen kind="chatNotFound" />
  if (missing && missing.sessionId === sessionId) return <ErrorScreen kind={missing.kind} />
  if (status === 'failed') {
    return (
      <Notice
        variant="negative"
        role="alert"
        action={
          <button className="mt-btn mt-btn--assistive mt-btn--md" type="button" onClick={retryProfiles}>
            다시 시도
          </button>
        }
      >
        프로필을 불러오지 못했어요. 잠시 후 다시 시도해 주세요.
      </Notice>
    )
  }
  if (!profile) return null

  const name = profile.profileName
  const preparing = prepare?.profileId === profileId && !prepare.failed
  const prepareFailed = prepare?.profileId === profileId && prepare.failed

  return (
    <div className={sessionId === null ? 'chat-page' : 'chat-page chat-page--room'}>
      <h1 className="screen-title">챗봇</h1>
      <div className="chat-page__cols" ref={colsRef}>
        <ChatRoomList
          name={name}
          color={color}
          sessions={sessions}
          currentId={sessionId}
          failed={listFailed}
          loading={listLoading}
          preparing={preparing}
          prepareFailed={prepareFailed}
          onRetry={() => setListKey((key) => key + 1)}
          onNew={() => startRoom(null)}
          onRetryPrepare={() => startRoom(prepare.question)}
        />
        {sessionId === null ? (
          <ChatIntro
            name={name}
            drugCount={drugs?.profileId === profileId ? drugs.count : null}
            locked={(!sessions && !listFailed) || preparing}
            onAsk={startRoom}
          />
        ) : (
          <ChatRoom
            key={sessionId}
            sessionId={sessionId}
            profileId={profileId}
            listTitle={sessions?.find((session) => session.sessionId === sessionId)?.title}
            question={queued?.sessionId === sessionId ? queued.question : null}
            onQuestionTaken={() => setQueued(null)}
            onChange={() => setListKey((key) => key + 1)}
            onMissing={(kind) => setMissing({ sessionId, kind })}
            onReady={() => setLoadedRoom({ sessionId })}
          />
        )}
      </div>
    </div>
  )
}

export default ChatPage
