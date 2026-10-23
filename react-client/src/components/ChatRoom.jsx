import { useEffect, useEffectEvent, useId, useLayoutEffect, useRef, useState } from 'react'
import { Link, Navigate } from 'react-router'
import { askQuestion, getChatMessages, retryQuestion } from '../api/chat.js'
import { errorOf } from '../api/client.js'
import { messageTime, sendTime, splitAnswer } from '../chatInfo.js'
import { paths } from '../paths.js'
import ChatAnswer from './ChatAnswer.jsx'
import ChatComposer from './ChatComposer.jsx'
import Icon from './Icon.jsx'
import Notice from './Notice.jsx'
import './ChatRoom.css'

// 10초가 지나도 답이 없으면 기다림 문구를 바꾼다(목업 CHT-02-02).
const slowAfter = 10_000
// 저장 여부를 아직 알 수 없으면 이 간격으로 대화를 다시 불러온다(목업 CHT-02-11).
const checkEvery = 5_000
// 서버의 답변 한도(85초)와 전달 여유. 보낸 뒤 이 시간이 지나도 질문이 대화에 없으면 저장되지 않은 것이다.
const answerWindow = 95_000
// 보낸 뒤 이 시간까지 저장 여부를 알 수 없으면 저절로 확인하지 않고 "대화 상태 다시 확인"을 보여 준다.
const checkLimit = 120_000

// 대화 상태 확인(목업 CHT-02-11) 단계별 문구
const checkViews = {
  checking: { icon: 'wifiOff', title: '연결이 끊겼어요. 질문과 답변이 저장됐는지 확인할게요.' },
  unknown: { icon: 'clock', title: '대화 상태를 확인하고 있어요.', text: '확인이 끝나면 답변을 보여 드릴게요.' },
  failed: { icon: 'alertCircle', title: '대화 상태를 확인하지 못했어요.', text: '질문과 입력 중인 내용은 그대로 있어요.' },
}

// 서버 메시지. 답변은 본문과 근거를 나눠 둔다.
function toMessage(message) {
  return message.role === 'ASSISTANT' ? { ...message, answer: splitAnswer(message.content) } : message
}

// 메시지를 번호로 합쳐 턴 순서(같은 턴은 질문 먼저)로 놓는다. 같은 메시지를 두 번 넣지 않는다.
function mergeMessages(messages, added) {
  const byId = new Map(messages.map((message) => [message.messageId, message]))
  for (const message of added) byId.set(message.messageId, message)
  const rank = (message) => (message.role === 'USER' ? 0 : 1)
  return [...byId.values()].sort((a, b) => a.turnNo - b.turnNo || rank(a) - rank(b) || a.messageId - b.messageId)
}

// 최신 페이지로 대화방 정보와 메시지를 고친다. 그보다 앞에 불러 둔 이전 대화는 남긴다.
function withLatest(room, data) {
  const page = data.messages.map(toMessage)
  const firstTurn = page[0]?.turnNo
  const kept = room && firstTurn !== undefined ? room.messages.filter((message) => message.turnNo < firstTurn) : []
  return {
    profileId: data.profileId,
    profileName: data.profileName,
    title: data.title,
    drugCount: data.drugCount,
    retryMessageId: data.retryMessageId,
    messages: [...kept, ...page],
    hasMore: kept.length > 0 ? room.hasMore : data.hasMore,
  }
}

// 턴마다 질문과 답변을 묶는다.
function toTurns(messages) {
  const turns = []
  for (const message of messages) {
    let turn = turns.at(-1)
    if (turn?.turnNo !== message.turnNo) {
      turn = { turnNo: message.turnNo, question: null, answer: null }
      turns.push(turn)
    }
    if (message.role === 'USER') turn.question = message
    else turn.answer = message
  }
  return turns
}

function lastTurnNo(messages) {
  return messages.at(-1)?.turnNo ?? 0
}

// 내 질문 말풍선. temp: 저장되지 않은 질문(무관 · 위험 안내, 목업 .bub-u.tmp)
function Question({ content, time, temp }) {
  return (
    <div className="chat-msg chat-msg--question">
      <p className="visually-hidden">{temp ? '내 질문(저장 안 됨)' : '내 질문'}</p>
      <p className={temp ? 'screen-body chat-msg__question chat-msg__question--temp' : 'screen-body chat-msg__question'}>
        {content}
      </p>
      <span className="screen-caption chat-msg__time">{time}</span>
    </div>
  )
}

// 저장되지 않은 질문과 그 안내. 이 화면에만 남고 대화방을 다시 열면 보이지 않는다.
// 응급(목업 CHT-02-05)과 무관 · 위험(08 · 09)은 보드마다 그린 모양이 달라 각각 따른다.
function BlockedNote({ note }) {
  const { category, message } = note.notice
  if (category === 'EMERGENCY') {
    return (
      <>
        <Question content={note.content} time="저장되지 않은 질문" />
        <div className="chat-msg chat-msg--answer">
          <p className="visually-hidden">필받음의 응급 안내</p>
          <div className="chat-room__emergency" role="alert">
            <div className="chat-room__emergency-head">
              <Icon name="alertTriangle" size="l" />
              <h3 className="screen-item">응급 안내</h3>
            </div>
            <p className="screen-body chat-room__emergency-text">{message}</p>
            <a className="mt-btn mt-btn--danger chat-room__inline-action" href="tel:119">
              <Icon name="phone" />
              119에 전화하기
            </a>
          </div>
        </div>
        <p className="screen-caption chat-room__muted">이 질문과 안내는 대화에 저장되지 않아요.</p>
      </>
    )
  }
  const unsafe = category === 'UNSAFE'
  return (
    <>
      <Question content={note.content} time={`${messageTime(note.at)} · 저장 안 됨`} temp />
      <div className="chat-msg chat-msg--answer">
        <p className="visually-hidden">필받음의 안내</p>
        <div className={unsafe ? 'chat-room__guide chat-room__guide--caution' : 'chat-room__guide chat-room__guide--info'}>
          <Icon name={unsafe ? 'ban' : 'info'} />
          <p className="screen-body chat-room__strong">{message}</p>
        </div>
        <span className="screen-caption chat-msg__time">이 질문과 안내는 대화에 저장되지 않아요.</span>
      </div>
    </>
  )
}

// 답을 기다리는 말풍선(목업 CHT-02-02)
function Waiting({ slow }) {
  return (
    <div className="chat-msg chat-msg--answer">
      <p className="visually-hidden">필받음의 답변</p>
      <div className="chat-msg__answer chat-room__waiting" role="status">
        <Icon name="loader" className="icon--spin" />
        <p className="screen-body chat-room__strong">
          {slow ? '답변을 준비하는 데 시간이 걸리고 있어요. 잠시만 기다려 주세요.' : '답변을 만드는 중…'}
        </p>
      </div>
    </div>
  )
}

// 대화 상태 확인(목업 CHT-02-11). 저장 취소로 보여 주지 않고 대화 기록으로 확인한다.
function CheckState({ check, onRecheck }) {
  const view = checkViews[check.phase]
  return (
    <div className="chat-msg chat-msg--answer">
      <p className="visually-hidden">필받음의 상태 안내</p>
      <div className="chat-room__state" role="status">
        <Icon name={view.icon} className="chat-room__state-icon" />
        <div className="chat-room__state-body">
          <p className="screen-body">
            <b>{view.title}</b>
          </p>
          {check.phase === 'checking' ? (
            <p className="screen-caption chat-room__muted chat-room__busy">
              <Icon name="loader" size="s" className="icon--spin" />
              대화 상태를 확인하고 있어요.
            </p>
          ) : (
            <p className="screen-caption chat-room__muted">{view.text}</p>
          )}
          {check.phase === 'failed' && (
            <button
              className="mt-btn mt-btn--assistive mt-btn--sm chat-room__inline-action"
              type="button"
              disabled={check.busy}
              onClick={onRecheck}
            >
              <Icon name={check.busy ? 'loader' : 'refresh'} size="s" className={check.busy ? 'icon--spin' : undefined} />
              대화 상태 다시 확인
            </button>
          )}
        </div>
      </div>
    </div>
  )
}

// CHT-02 대화방. 저장된 질문 · 답변(최근 30턴, 위에서 이전 대화를 더 불러온다)과 질문 입력창.
// 질문은 한 번에 하나만 보내고 답이 오면 같은 턴에 붙인다. 응급 · 무관 · 위험 질문의 안내는 저장되지 않아 이 화면에만 남는다.
// 연결이 끊기거나 90초가 지나면 저장 여부를 대화 기록으로 확인한다. 관리할 사람이 이 방의 사람과 다르면 대화방 목록으로 간다.
// question: 추천 질문으로 연 새 방에서 한 번만 보낼 질문 · onChange: 제목 · 순서가 바뀌었을 수 있어 목록을 다시 받는다
// onMissing: 대화방이 없거나(chatNotFound) 권한이 없을 때(forbidden) 오류 화면으로 바꾼다
function ChatRoom({ sessionId, profileId, listTitle, question, onQuestionTaken, onChange, onMissing }) {
  const titleId = useId()
  const [attempt, setAttempt] = useState(0)
  const [loadFailed, setLoadFailed] = useState(-1)
  const [room, setRoom] = useState(null)
  const [draft, setDraft] = useState('')
  const [notSaved, setNotSaved] = useState(false)
  // 막힌 질문을 입력창에 돌려놓았다(목업 "질문을 고쳐서 다시 보낼 수 있어요.")
  const [blocked, setBlocked] = useState(false)
  // 답을 기다리는 질문. { kind: 'ask', content, at, slow } · { kind: 'retry', messageId }
  const [pending, setPending] = useState(null)
  // 저장되지 않은 질문과 안내. afterTurn: 보낼 때의 마지막 턴(그 뒤에 보인다)
  const [notes, setNotes] = useState([])
  // 대화 상태 확인. { phase, sentAt, content?, at?, baseTurn?, messageId?, busy? }
  const [check, setCheck] = useState(null)
  const [refreshing, setRefreshing] = useState(false)
  // 이전 대화: null(아직 안 불러옴) · 'loading' · 'failed' · 'loaded'
  const [older, setOlder] = useState(null)
  const aliveRef = useRef(false)
  const timersRef = useRef({ slow: 0, check: 0 })
  const msgsRef = useRef(null)
  // 이전 대화를 앞에 붙이기 전 첫 턴의 화면 위치. 붙인 뒤 같은 자리로 스크롤한다.
  const anchorRef = useRef(null)

  useEffect(() => {
    aliveRef.current = true
    const timers = timersRef.current
    return () => {
      aliveRef.current = false
      clearTimeout(timers.slow)
      clearTimeout(timers.check)
    }
  }, [])

  useLayoutEffect(() => {
    const anchor = anchorRef.current
    if (!anchor) return
    anchorRef.current = null
    const node = msgsRef.current?.querySelector(`[data-turn="${anchor.turnNo}"]`)
    if (node) window.scrollBy(0, node.getBoundingClientRect().top - anchor.top)
  }, [room])

  // 대화방이 없거나(404) 권한이 없으면(403) 오류 화면으로 바꾼다. 401 은 client 가 로그인 만료로 처리한다.
  const leaveIfGone = (error) => {
    const { status } = errorOf(error)
    if (status === 404) onMissing('chatNotFound')
    else if (status === 403) onMissing('forbidden')
    return status === 401 || status === 403 || status === 404
  }

  // 보내지 못한 질문을 입력창에 돌려놓는다. 그 사이 새로 쓴 질문이 있으면 그대로 둔다.
  const restoreDraft = (text) => setDraft((current) => (current.trim() === '' ? text : current))

  // 저장 여부를 대화 기록으로 확인한다. 알 수 없으면 잠시 뒤 다시 확인하고, 질문이 끝내 없으면 저장되지 않은 것으로 본다.
  const runCheck = (target) => {
    clearTimeout(timersRef.current.check)
    getChatMessages(sessionId)
      .then((data) => {
        if (!aliveRef.current) return
        setRoom((current) => withLatest(current, data))
        const questions = data.messages.filter((message) => message.role === 'USER')
        let mine = null
        if (target.messageId) mine = questions.find((message) => message.messageId === target.messageId)
        else if (target.content !== undefined) mine = questions.find((message) => message.turnNo > target.baseTurn)
        const elapsed = Date.now() - target.sentAt
        if (target.content !== undefined && !mine && elapsed > answerWindow) {
          setCheck(null)
          restoreDraft(target.content)
          setNotSaved(true)
          return
        }
        // 답이 붙었거나 다시 보낼 수 있으면 확인이 끝난 것이다.
        const settled =
          mine &&
          (data.retryMessageId === mine.messageId ||
            data.messages.some((message) => message.role === 'ASSISTANT' && message.turnNo === mine.turnNo))
        if ((target.content !== undefined && !mine) || (mine && !settled)) {
          const next = { ...target, messageId: mine?.messageId, busy: false }
          if (elapsed > checkLimit) {
            setCheck({ ...next, phase: 'failed' })
            return
          }
          setCheck({ ...next, phase: 'unknown' })
          timersRef.current.check = setTimeout(() => runCheck(next), checkEvery)
          return
        }
        setCheck(null)
        onChange()
      })
      .catch((error) => {
        if (!aliveRef.current || leaveIfGone(error)) return
        setCheck({ ...target, phase: 'failed', busy: false })
      })
  }

  // 연결이 끊기거나 90초가 지나 저장 여부를 모를 때(CHT-02-11). 보내기를 잠그고 대화 기록으로 확인한다.
  const checkSaved = (target) => {
    setCheck({ ...target, phase: 'checking' })
    runCheck(target)
  }

  // 대화를 다시 불러온다(다른 곳에서 답하는 중 · 이미 답이 생김). 불러오지 못하면 대화 상태를 확인하지 못했다고 알린다.
  const refreshRoom = () => {
    setRefreshing(true)
    getChatMessages(sessionId)
      .then((data) => {
        if (!aliveRef.current) return
        setRefreshing(false)
        setRoom((current) => withLatest(current, data))
        onChange()
      })
      .catch((error) => {
        if (!aliveRef.current) return
        setRefreshing(false)
        if (!leaveIfGone(error)) setCheck({ phase: 'failed', sentAt: Date.now() })
      })
  }

  // 질문 보내기(목업 CHT-02-02). 입력창은 비워 다음 질문을 쓸 수 있게 하고, 답이 오면 같은 턴에 붙인다.
  // baseTurn: 보낼 때의 마지막 턴. 연결이 끊겼을 때 이보다 뒤의 질문이 대화에 있으면 저장된 것이다.
  const ask = (content, baseTurn) => {
    const text = content.trim()
    const { at, ms: sentAt } = sendTime()
    setPending({ kind: 'ask', content: text, at, slow: false })
    setNotSaved(false)
    setBlocked(false)
    clearTimeout(timersRef.current.slow)
    timersRef.current.slow = setTimeout(
      () => setPending((current) => (current?.kind === 'ask' ? { ...current, slow: true } : current)),
      slowAfter,
    )
    askQuestion(sessionId, text)
      .then((result) => {
        if (!aliveRef.current) return
        clearTimeout(timersRef.current.slow)
        setPending(null)
        if (result.result === 'BLOCKED') {
          setNotes((list) => [...list, { key: sentAt, afterTurn: baseTurn, content: text, at, notice: result.notice }])
          restoreDraft(text)
          setBlocked(true)
          return
        }
        setRoom((current) => ({
          ...current,
          retryMessageId: null,
          messages: mergeMessages(current.messages, [
            { messageId: result.question.messageId, turnNo: result.question.turnNo, role: 'USER', content: text, createdAt: at },
            toMessage({ ...result.answer, role: 'ASSISTANT' }),
          ]),
        }))
        onChange()
      })
      .catch((error) => {
        if (!aliveRef.current) return
        clearTimeout(timersRef.current.slow)
        setPending(null)
        if (leaveIfGone(error)) return
        const { status } = errorOf(error)
        const savedId = error.response?.data?.questionMessageId
        if (status === 503 && savedId) {
          // 질문은 저장됐고 답변만 만들지 못했다(CHT-02-06). 「다시 보내기」로 같은 질문의 답만 다시 만든다.
          setRoom((current) => ({
            ...current,
            retryMessageId: savedId,
            messages: mergeMessages(current.messages, [
              { messageId: savedId, turnNo: baseTurn + 1, role: 'USER', content: text, createdAt: at },
            ]),
          }))
          onChange()
        } else if (status === 400 || status === 503) {
          // 서버가 저장 전에 실패를 알려 줬다(CHT-02-10).
          restoreDraft(text)
          setNotSaved(true)
        } else if (status === 409) {
          // 저장되지 않았다. 다른 곳에서 이 방의 질문에 답하는 중이라 대화를 다시 불러온다.
          restoreDraft(text)
          refreshRoom()
        } else {
          checkSaved({ content: text, at, sentAt, baseTurn })
        }
      })
  }

  // 「다시 보내기」(CHT-02-06): 저장된 같은 질문의 답만 다시 만든다. 새 질문 말풍선은 만들지 않는다.
  const retry = (messageId) => {
    const { ms: sentAt } = sendTime()
    setPending({ kind: 'retry', messageId })
    retryQuestion(sessionId, messageId)
      .then((result) => {
        if (!aliveRef.current) return
        setPending(null)
        setRoom((current) => ({
          ...current,
          retryMessageId: null,
          messages: mergeMessages(current.messages, [toMessage({ ...result.answer, role: 'ASSISTANT' })]),
        }))
        onChange()
      })
      .catch((error) => {
        if (!aliveRef.current) return
        setPending(null)
        // 또 실패(503)하면 질문과 「다시 보내기」를 그대로 남긴다.
        if (leaveIfGone(error) || errorOf(error).status === 503) return
        // 이미 답이 생겼거나(400 · 409) 다른 곳에서 답하는 중이면 대화를 다시 불러온다. 응답이 없으면 저장 여부를 확인한다.
        if (error.response) refreshRoom()
        else checkSaved({ messageId, sentAt })
      })
  }

  // 이전 대화 불러오기(CHT-02-12). 지금 보이는 대화 앞에 붙이고 읽던 위치를 그대로 둔다.
  const loadOlder = () => {
    const firstTurn = room.messages[0].turnNo
    setOlder('loading')
    getChatMessages(sessionId, firstTurn)
      .then((data) => {
        if (!aliveRef.current) return
        const node = msgsRef.current?.querySelector(`[data-turn="${firstTurn}"]`)
        anchorRef.current = node ? { turnNo: firstTurn, top: node.getBoundingClientRect().top } : null
        setRoom((current) => ({
          ...current,
          messages: mergeMessages(current.messages, data.messages.map(toMessage)),
          hasMore: data.hasMore,
        }))
        setOlder('loaded')
      })
      .catch((error) => {
        if (aliveRef.current && !leaveIfGone(error)) setOlder('failed')
      })
  }

  // 처음 불러오기. 추천 질문으로 연 새 방이면 불러온 뒤 그 질문을 한 번만 보낸다.
  const onLoaded = useEffectEvent((data) => {
    setRoom(withLatest(null, data))
    if (question) {
      onQuestionTaken()
      ask(question, lastTurnNo(data.messages))
    }
  })

  const onLoadFailed = useEffectEvent((error) => {
    if (!leaveIfGone(error)) setLoadFailed(attempt)
  })

  useEffect(() => {
    let active = true
    getChatMessages(sessionId)
      .then((data) => active && onLoaded(data))
      .catch((error) => active && onLoadFailed(error))
    return () => {
      active = false
    }
  }, [sessionId, attempt])

  if (!room) {
    return (
      <section className="mt-card mt-card--flush chat-room" aria-label="대화방">
        <div className="chat-room__msgs chat-room__msgs--center">
          {loadFailed === attempt ? (
            <Notice
              variant="negative"
              role="alert"
              action={
                <button
                  className="mt-btn mt-btn--assistive mt-btn--md"
                  type="button"
                  onClick={() => setAttempt((count) => count + 1)}
                >
                  다시 시도
                </button>
              }
            >
              대화를 불러오지 못했어요. 잠시 후 다시 시도해 주세요.
            </Notice>
          ) : (
            <p className="screen-body chat-room__muted chat-room__busy" role="status">
              <Icon name="loader" className="icon--spin" />
              대화를 불러오는 중이에요.
            </p>
          )}
        </div>
      </section>
    )
  }

  // 관리할 사람을 바꿨거나 다른 사람의 대화방 주소로 왔으면 방을 닫고 지금 사람의 목록으로 간다(목업 CHT-01-01).
  if (room.profileId !== profileId) return <Navigate to={paths.chat} replace />

  const name = room.profileName
  const turns = toTurns(room.messages)
  const last = turns.at(-1)
  const hasQuestion = room.messages.some((message) => message.role === 'USER')
  // 답이 없는 마지막 질문인데 다시 보낼 수 없으면 다른 곳에서 답하는 중이다(목업 "다른 탭에서 같은 방을 처리 중").
  const elsewhere =
    !pending && !check && Boolean(last?.question) && !last.answer && room.retryMessageId !== last.question.messageId
  const busy = Boolean(pending) || Boolean(check) || refreshing || elsewhere
  const empty = room.messages.length === 0 && notes.length === 0 && !pending && !check

  let hint = null
  if (pending || elsewhere) hint = '답변이 끝나면 보낼 수 있어요. 쓰고 있는 질문은 그대로 남아요.'
  else if (check) hint = '대화 상태를 확인하는 동안에는 보낼 수 없어요.'
  else if (room.retryMessageId) hint = '「다시 보내기」는 위의 저장된 질문을 다시 보내요. 입력창의 질문과는 따로예요.'
  else if (blocked) hint = '질문을 고쳐서 다시 보낼 수 있어요.'

  const recheck = () => {
    setCheck({ ...check, busy: true })
    runCheck(check)
  }

  const notesAfter = (turnNo) =>
    notes.filter((note) => note.afterTurn === turnNo).map((note) => <BlockedNote key={note.key} note={note} />)

  // 답이 없는 질문 다음에 보일 것: 대화 상태 확인 · 다시 보내기 · 다른 곳에서 답하는 중
  const unanswered = (turn) => {
    const id = turn.question.messageId
    if (check?.messageId === id) return <CheckState check={check} onRecheck={recheck} />
    if (room.retryMessageId === id) {
      const retrying = pending?.kind === 'retry' && pending.messageId === id
      return (
        <div className="chat-msg chat-msg--answer chat-room__failed-wrap">
          <p className="visually-hidden">필받음의 답변</p>
          <div className="mt-notice mt-notice--negative chat-room__failed">
            <Icon name="alertCircle" className="mt-notice__icon" />
            <div className="chat-room__failed-text">
              <p className="screen-body">
                <b>답변을 만들지 못했어요</b>
              </p>
              <p>질문은 그대로 남겨 두었어요.</p>
            </div>
            <button
              className="mt-btn mt-btn--assistive mt-btn--sm"
              type="button"
              disabled={busy}
              onClick={() => retry(id)}
            >
              <Icon name={retrying ? 'loader' : 'refresh'} size="s" className={retrying ? 'icon--spin' : undefined} />
              {retrying ? '다시 보내는 중…' : '다시 보내기'}
            </button>
          </div>
        </div>
      )
    }
    if (elsewhere && turn === last) {
      return (
        <div className="chat-msg chat-msg--answer">
          <p className="visually-hidden">필받음의 답변</p>
          <div className="chat-msg__answer chat-room__elsewhere" role="status">
            <p className="screen-body chat-room__strong chat-room__neutral">이전 질문에 답하는 중이에요.</p>
            <button
              className="mt-btn mt-btn--assistive mt-btn--sm chat-room__inline-action"
              type="button"
              disabled={refreshing}
              onClick={refreshRoom}
            >
              <Icon name={refreshing ? 'loader' : 'refresh'} size="s" className={refreshing ? 'icon--spin' : undefined} />
              대화 상태 확인
            </button>
          </div>
        </div>
      )
    }
    return null
  }

  // 이전 대화 불러오기 줄(목업 CHT-02-12 .loadtop)
  let olderBar = null
  if (older === 'failed') {
    olderBar = (
      <Notice
        variant="negative"
        role="alert"
        action={
          <button className="mt-btn mt-btn--assistive mt-btn--sm" type="button" onClick={loadOlder}>
            <Icon name="refresh" size="s" />
            다시 시도
          </button>
        }
      >
        이전 대화를 불러오지 못했어요.
      </Notice>
    )
  } else if (older === 'loading') {
    olderBar = (
      <p className="screen-caption chat-room__muted chat-room__older" role="status">
        <Icon name="loader" size="s" className="icon--spin" />
        이전 대화를 불러오는 중이에요.
      </p>
    )
  } else if (room.hasMore) {
    olderBar = (
      <div className="chat-room__older">
        <button className="mt-btn mt-btn--assistive mt-btn--sm" type="button" onClick={loadOlder}>
          <Icon name="chevronUp" size="s" />
          이전 대화 불러오기
        </button>
        {!older && <span className="screen-caption chat-room__muted">최근 대화 30개를 보여 드려요.</span>}
      </div>
    )
  } else if (older === 'loaded') {
    olderBar = (
      <p className="screen-caption chat-room__muted chat-room__older" role="status">
        이전 대화를 모두 불러왔어요.
      </p>
    )
  }

  return (
    <section className="mt-card mt-card--flush chat-room" aria-labelledby={titleId}>
      <div className="chat-room__head">
        <div className="chat-room__head-row">
          <h2 className="screen-section" id={titleId}>
            {room.title ?? listTitle ?? '새 대화'}
          </h2>
          <Link className="mt-btn mt-btn--assistive mt-btn--sm chat-room__register" to={paths.drugNew}>
            <Icon name="plus" />약 등록
          </Link>
        </div>
        <p className="screen-caption chat-room__muted">
          {hasQuestion
            ? `이 대화는 첫 질문을 저장할 때의 ${name} 프로필을 기준으로 답해요.`
            : `첫 질문을 보내면 그때의 ${name} 프로필을 기준으로 답해요.`}
        </p>
      </div>
      <div className={empty ? 'chat-room__msgs chat-room__msgs--center' : 'chat-room__msgs'} ref={msgsRef}>
        {empty && room.drugCount > 0 && (
          <div className="chat-room__intro">
            <span className="chat-room__feature">
              <Icon name="chat" size="l" />
            </span>
            <p className="screen-body chat-room__neutral">
              {name}의 약 {room.drugCount}개와 복약 일정, 공공데이터를 근거로 답해요. 근거를 찾지 못하면 '확인할 수 없어요'라고
              답해요.
            </p>
          </div>
        )}
        {empty && room.drugCount === 0 && (
          <div className="chat-room__intro chat-room__intro--no-drugs">
            <span className="chat-room__feature">
              <Icon name="pill" size="l" />
            </span>
            <h3 className="screen-item">약을 등록하면 내 약 기준으로 답해요</h3>
            <p className="screen-body chat-room__neutral">{name}의 약 목록에 등록된 약이 없어요.</p>
            <Link className="mt-btn mt-btn--solid chat-room__intro-action" to={paths.drugNew}>
              <Icon name="plus" />약 등록
            </Link>
          </div>
        )}
        {olderBar}
        {notesAfter(0)}
        {turns.map((turn) => (
          <div className="chat-room__turn" data-turn={turn.turnNo} key={turn.turnNo}>
            {turn.question && <Question content={turn.question.content} time={messageTime(turn.question.createdAt)} />}
            {turn.answer && <ChatAnswer message={turn.answer} profileName={name} />}
            {turn.question && !turn.answer && unanswered(turn)}
            {notesAfter(turn.turnNo)}
          </div>
        ))}
        {pending?.kind === 'ask' && (
          <div className="chat-room__turn">
            <Question content={pending.content} time={messageTime(pending.at)} />
            <Waiting slow={pending.slow} />
          </div>
        )}
        {check && !check.messageId && (
          <div className="chat-room__turn">
            {check.content !== undefined && <Question content={check.content} time={messageTime(check.at)} />}
            <CheckState check={check} onRecheck={recheck} />
          </div>
        )}
      </div>
      <div className="chat-room__composer">
        {room.drugCount === 0 && !empty && (
          <Notice
            variant="info"
            action={
              <Link className="mt-btn mt-btn--assistive mt-btn--sm" to={paths.drugNew}>
                <Icon name="plus" />약 등록
              </Link>
            }
          >
            약을 등록하면 내 약 기준으로 답해요.
          </Notice>
        )}
        <ChatComposer
          value={draft}
          onChange={(value) => {
            setDraft(value)
            setNotSaved(false)
          }}
          onSend={() => {
            setDraft('')
            ask(draft, lastTurnNo(room.messages))
          }}
          locked={busy}
          hint={hint}
          error={notSaved ? '질문을 처리하지 못했어요. 잠시 후 다시 시도해 주세요.' : null}
        />
      </div>
    </section>
  )
}

export default ChatRoom
