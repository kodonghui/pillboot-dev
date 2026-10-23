import { useEffect, useId, useRef, useSyncExternalStore } from 'react'
import { profileColor } from '../profileInfo.js'
import { doseText } from '../scheduleInfo.js'
import { monthDay } from '../text.js'
import DrugName from './DrugName.jsx'
import Icon from './Icon.jsx'
import IconButton from './IconButton.jsx'
import ProfileDot from './ProfileDot.jsx'
import VisuallyHidden from './VisuallyHidden.jsx'
import VoiceOrb from './VoiceOrb.jsx'
import './VoiceScheduleDialog.css'

const listenHint = '누구의 어떤 약을 언제까지 먹는지 말해 주세요.'

const micSteps = ['주소창 왼쪽의 사이트 정보 아이콘을 눌러요.', '마이크를 ‘허용’으로 바꿔요.', '아래 ‘다시 시작’을 눌러요.']

// 끝낸 업무(서버 completedOperation.action)별 머리 상태와 일정 카드 제목. 삭제는 남은 일정이 없어 카드가 없다.
const completions = {
  SAVE: { status: '등록했어요 · 대화를 끝냈어요', card: '저장한 일정' },
  STOP: { status: '그만 먹기로 바꿨어요 · 대화를 끝냈어요', card: '바꾼 일정' },
  DELETE: { status: '일정을 삭제했어요 · 대화를 끝냈어요', card: null },
}

// 대화 중 머리 상태. 서버 대화 상태(status)와 모자란 값(missingFields)으로 고른다.
function liveView({ phase, status, missingFields }) {
  if (phase === 'connecting') return { status: '연결하고 있어요', tone: 'idle', hint: null }
  if (status === 'AWAITING_CONFIRMATION') {
    return {
      status: '동의를 기다려요 · 아직 저장 안 됨',
      tone: 'caution',
      hint: (
        <>
          <b>“응”</b>이라고 말하면 저장해요. 바꿀 건 그대로 말하면 돼요.
        </>
      ),
    }
  }
  if (status === 'WAITING_FOR_DETAILS' && missingFields.includes('target')) {
    return { status: '약 이름을 기다려요', tone: 'caution', hint: listenHint }
  }
  if (status === 'PROCESSING') return { status: '확인하고 있어요', tone: 'primary', hint: listenHint }
  return { status: '듣고 있어요', tone: 'primary', hint: listenHint }
}

// 남은 시간 '0:52'
function remainingText(deadline, now) {
  const seconds = Math.max(0, Math.ceil((deadline - now) / 1000))
  return `${Math.floor(seconds / 60)}:${String(seconds % 60).padStart(2, '0')}`
}

function ScheduleCard({ title, schedule, color }) {
  return (
    <div className="voice-dialog__card">
      <p className="screen-label voice-dialog__card-head">
        <span>{title}</span>
        <span>저장됨</span>
      </p>
      <dl>
        <div>
          <dt>누구</dt>
          <dd>
            {color && <ProfileDot color={color} />}
            {schedule.profileName}
          </dd>
        </div>
        <div>
          <dt>약</dt>
          <dd>
            <DrugName>{schedule.drugName}</DrugName>
          </dd>
        </div>
        <div>
          <dt>기간</dt>
          <dd className="voice-dialog__number">
            {monthDay(schedule.startDate)} ~ {monthDay(schedule.endDate)}
          </dd>
        </div>
        <div>
          <dt>시간</dt>
          <dd className="voice-dialog__number">{schedule.doseTimes.join(' · ')}</dd>
        </div>
        <div>
          <dt>1회</dt>
          <dd>{[doseText(schedule.dosePerTime), schedule.timing].filter(Boolean).join(' · ')}</dd>
        </div>
      </dl>
    </div>
  )
}

// 시작하지 못했거나 끊긴 대화(목업 SCH-04 busy · mic-denied · unavailable)
function failureBody(phase, titleId) {
  if (phase === 'busy') {
    return (
      <>
        <span className="voice-dialog__failure-icon voice-dialog__failure-icon--caution">
          <Icon name="devices" size="l" />
        </span>
        <h2 className="screen-item voice-dialog__failure-title" id={titleId}>
          이미 음성 대화 중이에요
        </h2>
        <p className="screen-body voice-dialog__neutral">
          다른 창이나 기기에서 대화가 열려 있어요. 음성 대화는 한 번에 하나만 열 수 있어요.
        </p>
        <p className="screen-body voice-dialog__neutral">그 대화를 끝내거나 1분이 지나면 다시 시작할 수 있어요.</p>
      </>
    )
  }
  if (phase === 'micDenied') {
    return (
      <>
        <span className="voice-dialog__failure-icon">
          <Icon name="micOff" size="l" />
        </span>
        <h2 className="screen-item voice-dialog__failure-title" id={titleId}>
          마이크를 쓸 수 없어요
        </h2>
        <p className="screen-body voice-dialog__neutral">브라우저에서 마이크 사용을 막아 두었어요.</p>
        <ol className="screen-body voice-dialog__steps">
          {micSteps.map((step, index) => (
            <li key={step}>
              <span className="screen-label voice-dialog__step-number" aria-hidden="true">
                {index + 1}
              </span>
              {step}
            </li>
          ))}
        </ol>
      </>
    )
  }
  return (
    <>
      <span className="voice-dialog__failure-icon">
        <Icon name="wifiOff" size="l" />
      </span>
      <h2 className="screen-item voice-dialog__failure-title" id={titleId}>
        음성 대화를 이어 갈 수 없어요
      </h2>
      <p className="screen-body voice-dialog__neutral">
        지금은 음성 기능을 쓸 수 없거나, 대화 중 연결이 끊겼어요. 잠시 후 다시 시도해 주세요.
      </p>
      <p className="screen-caption voice-dialog__muted">이미 저장한 일정은 그대로 있어요.</p>
    </>
  )
}

const failureStatus = {
  busy: { status: '시작하지 못했어요', tone: 'caution' },
  micDenied: { status: '시작하지 못했어요', tone: 'negative' },
  unavailable: { status: '연결이 끊겼어요', tone: 'negative' },
}

// SCH-04 AI 복약도우미 음성 대화 창. conversation(LiveConversation)의 상태를 그린다.
// 닫기 · 대화 끝내기 · Esc 는 onClose(대화를 끝내고 창을 치운다), 다시 시작 · 다시 시도는 onRestart(새 대화),
// 저장 · 그만 먹기 뒤 "일정 상세"는 onShowSchedule(바뀐 일정)을 부른다.
function VoiceScheduleDialog({ conversation, profiles, onClose, onRestart, onShowSchedule }) {
  const snapshot = useSyncExternalStore(conversation.subscribe, conversation.getSnapshot)
  const dialogRef = useRef(null)
  const closeRef = useRef(null)
  const titleId = useId()
  const failureTitleId = useId()
  const { phase, messages, operation, savedSchedule } = snapshot

  const failed = phase in failureStatus
  const timedOut = phase === 'timeout'
  const completion = operation && (phase === 'completing' || phase === 'done') ? completions[operation.action] : null
  const live = !failed && !timedOut && !completion

  useEffect(() => {
    const dialog = dialogRef.current
    if (!dialog.open) dialog.showModal()
  }, [])

  // 대화가 끝나 아래 버튼이 바뀌면서 포커스를 잃었으면 머리의 닫기 버튼으로 옮긴다.
  useEffect(() => {
    if (!live && document.activeElement === document.body) closeRef.current.focus()
  }, [live])

  const close = () => dialogRef.current.close()

  let view
  if (failed) view = { ...failureStatus[phase], hint: phase === 'unavailable' ? '날짜를 눌러 직접 추가할 수도 있어요.' : null }
  else if (timedOut) view = { status: '대화가 끝났어요', tone: 'negative', hint: '대화는 최대 1분이에요. 이번 대화에서는 저장한 게 없어요.' }
  else if (completion) view = { status: completion.status, tone: 'primary', hint: completion.card && savedSchedule ? '잘못 들었으면 일정 상세에서 고칠 수 있어요.' : null }
  else view = liveView(snapshot)

  const card = completion?.card && savedSchedule
  const showTimer = snapshot.deadline && (live || timedOut)

  let actions
  if (live) {
    actions = (
      <button className="mt-btn mt-btn--assistive mt-btn--sm" type="button" onClick={close}>
        대화 끝내기
      </button>
    )
  } else if (completion) {
    actions = (
      <>
        {card && (
          <button className="mt-btn mt-btn--assistive mt-btn--sm" type="button" onClick={() => onShowSchedule(savedSchedule)}>
            일정 상세
          </button>
        )}
        <button className="mt-btn mt-btn--solid mt-btn--sm" type="button" onClick={close}>
          닫기
        </button>
      </>
    )
  } else {
    actions = (
      <>
        <button className="mt-btn mt-btn--assistive mt-btn--sm" type="button" onClick={close}>
          닫기
        </button>
        <button className="mt-btn mt-btn--solid mt-btn--sm" type="button" onClick={onRestart}>
          {phase === 'unavailable' ? '다시 시도' : '다시 시작'}
        </button>
      </>
    )
  }

  return (
    <dialog
      ref={dialogRef}
      className="mt-dim voice-dialog"
      role={failed || timedOut ? 'alertdialog' : undefined}
      aria-labelledby={failed ? failureTitleId : titleId}
      onClose={onClose}
    >
      <section className="voice-dialog__box">
        <div className="voice-dialog__head">
          <VoiceOrb small motion={live && phase !== 'connecting' ? 'live' : failed || timedOut ? 'idle' : null} />
          <div className="voice-dialog__heading">
            {failed ? (
              <p className="screen-item voice-dialog__title">AI 복약도우미</p>
            ) : (
              <h2 className="screen-item voice-dialog__title" id={titleId}>
                AI 복약도우미
              </h2>
            )}
            <span
              className={`screen-label voice-dialog__status voice-dialog__status--${view.tone}`}
              role={failed || timedOut ? undefined : 'status'}
            >
              {view.status}
            </span>
          </div>
          {showTimer && (
            <span className={timedOut ? 'voice-dialog__timer voice-dialog__timer--over' : 'voice-dialog__timer'}>
              <VisuallyHidden>남은 시간 </VisuallyHidden>
              {timedOut ? '0:00' : remainingText(snapshot.deadline, snapshot.now)}
            </span>
          )}
          <IconButton ref={closeRef} icon="close" label={live ? '대화 끝내기' : '닫기'} onClick={close} />
        </div>

        {failed ? (
          <div className="voice-dialog__body voice-dialog__body--failure">{failureBody(phase, failureTitleId)}</div>
        ) : (
          <div className="voice-dialog__body" aria-live={timedOut ? undefined : 'polite'}>
            <p className="screen-label voice-dialog__caption">AI가 만든 음성으로 대답해요</p>
            {messages.map((message, index) => {
              // 아직 말하는 중인 내 말(목업 .msg-live): 대화 중이고 AI 대답이 오기 전인 마지막 내 말
              const speaking = live && message.role === 'user' && index === messages.length - 1
              const classes = [
                'screen-body voice-dialog__message',
                message.role === 'user' ? 'voice-dialog__message--mine' : 'voice-dialog__message--ai',
                speaking && 'voice-dialog__message--speaking',
              ]
              return (
                <p key={message.id} className={classes.filter(Boolean).join(' ')}>
                  {message.text}
                  {speaking && (
                    <span className="voice-dialog__dots" aria-hidden="true">
                      <span />
                      <span />
                      <span />
                    </span>
                  )}
                </p>
              )
            })}
            {timedOut && (
              <p className="screen-label voice-dialog__system" role="alert">
                <Icon name="clock" size="s" />
                시간이 초과되어 종료됩니다
              </p>
            )}
            {card && (
              <ScheduleCard
                title={completion.card}
                schedule={savedSchedule}
                color={profileColor(profiles, savedSchedule.profileId)}
              />
            )}
          </div>
        )}

        <div className="voice-dialog__foot">
          {view.hint && <p className="screen-caption voice-dialog__hint">{view.hint}</p>}
          {actions}
        </div>
      </section>
    </dialog>
  )
}

export default VoiceScheduleDialog
