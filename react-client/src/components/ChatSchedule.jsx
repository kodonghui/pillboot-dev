import { useEffect, useId, useRef, useState } from 'react'
import { Link } from 'react-router'
import { cancelChatProposal, confirmChatProposal, getChatProposal } from '../api/chat.js'
import { errorOf } from '../api/client.js'
import { todayKst } from '../dates.js'
import { paths } from '../paths.js'
import { calendarMonth, doseText } from '../scheduleInfo.js'
import { hourMinute, monthDay, yearMonthDay } from '../text.js'
import DrugThumb from './DrugThumb.jsx'
import Icon from './Icon.jsx'
import NoValue from './NoValue.jsx'
import Notice from './Notice.jsx'
import VisuallyHidden from './VisuallyHidden.jsx'
import './ChatProposal.css'
import './ChatSchedule.css'

// 확정 · 취소가 되지 않았을 때 버튼 위 빨간 안내(design/v3-2-chat-schedule.md 표시와 전환)
const problems = {
  notFound: '이 카드를 찾을 수 없어요. 대화를 다시 열어 주세요.',
  notSaved: '지금은 일정을 바꾸지 못했어요. 잠시 뒤 다시 눌러 주세요.',
  notCancelled: '지금은 취소하지 못했어요. 잠시 뒤 다시 눌러 주세요.',
}

// 일정 상세(SCH-05)와 같은 이름 · 순서의 네 값. same: '지금'과 '바뀐 뒤'가 같은 값인지(복용 시간은 목록 전체로 맞춤)
const fields = [
  { key: 'period', label: '기간', same: (a, b) => a.startDate === b.startDate && a.endDate === b.endDate },
  { key: 'times', label: '복용 시간', same: (a, b) => a.doseTimes?.join() === b.doseTimes?.join() },
  { key: 'dose', label: '1회 개수', same: (a, b) => a.dosePerTime === b.dosePerTime },
  { key: 'timing', label: '복용 때', same: (a, b) => (a.timing ?? null) === (b.timing ?? null) },
]

// 카드의 기간이 두 해에 걸치는지(시작일 · 종료일의 연도가 둘 이상). 걸치면 카드의 날짜에 연도를 붙여
// 연도만 바뀐 기간도 다른 글자로 보인다. 오늘 연도와는 비교하지 않는다.
function spansYears(...values) {
  const years = values.flatMap((v) => [v.startDate, v.endDate]).filter(Boolean).map((date) => date.slice(0, 4))
  return new Set(years).size > 1
}

// 값 하나의 글자. 없으면 null(확인되지 않은 1회 개수 · 복용 때 없음).
// badges: 복용 시간을 숫자 배지로(확정 카드). year: 기간 날짜에 연도를 붙임(spansYears)
function fieldValue(key, values, { badges = false, year = false } = {}) {
  if (key === 'period') {
    if (!values.startDate) return null
    const date = year ? yearMonthDay : monthDay
    // '부터' 뒤에서만 줄을 바꾼다.
    return (
      <span>
        <span className="chat-schedule__nowrap">{date(values.startDate)}부터</span>{' '}
        <span className="chat-schedule__nowrap">{date(values.endDate)}까지</span>
      </span>
    )
  }
  if (key === 'times') {
    if (!values.doseTimes) return null
    if (!badges) return values.doseTimes.join(' · ')
    return (
      <span className="chat-schedule__times">
        {values.doseTimes.map((time) => (
          <span className="mt-badge" key={time}>
            {time}
          </span>
        ))}
      </span>
    )
  }
  if (key === 'dose') return values.dosePerTime == null ? null : doseText(values.dosePerTime)
  return values.timing ?? null
}

// 네 값 목록(확정 카드 · 일정이 없던 약의 새 일정)
function ValueList({ values, badges, fresh, year }) {
  return (
    <dl className={`chat-schedule__result${fresh ? ' chat-schedule__fresh' : ''}`}>
      {fields.map((field) => (
        <div key={field.key}>
          <dt className="screen-label">{field.label}</dt>
          <dd className="screen-body">{fieldValue(field.key, values, { badges, year }) ?? <NoValue />}</dd>
        </div>
      ))}
    </dl>
  )
}

// 바뀌려던 일정 요약(취소 · 시간이 지난 · 약을 지운 카드): 약 이름 + 바뀐 뒤 복용 시간 · 1회 개수
function summaryOf(schedule) {
  const { doseTimes, dosePerTime } = schedule.proposed
  return `${schedule.drugName ? `${schedule.drugName} ` : ''}${[...doseTimes, doseText(dosePerTime)].join(' · ')}`
}

// 확정한 카드: 바뀐 뒤 일정과 복용 달력 링크. notice: 다른 화면에서 이미 확정한 카드일 때 위에 두는 안내
function DoneCard({ proposal, titleId, announce, notice }) {
  const { myDrugId, drugName, current, proposed } = proposal.schedule
  const calendarLink = `${paths.calendar}?${new URLSearchParams({
    month: calendarMonth(proposed.startDate, proposed.endDate, todayKst()),
    profile: proposal.profileId,
    drug: myDrugId,
  })}`
  return (
    <section className="chat-proposal chat-proposal--done chat-schedule" aria-labelledby={titleId}>
      {notice && <Notice variant="info">{notice}</Notice>}
      <div className="chat-proposal__result" role={announce ? 'status' : undefined}>
        <Icon name="checkCircle" size="l" />
        <h3 className="screen-item" id={titleId}>
          {drugName ? `${drugName} 일정을 바꿨어요` : '일정을 바꿨어요'}
        </h3>
      </div>
      {/* 연도는 대기 카드와 같이 '지금'까지 맞춰 봐, 연도를 보이던 카드가 확정 뒤에도 연도를 보인다. */}
      <ValueList values={proposed} badges year={spansYears(current, proposed)} />
      <p className="screen-caption chat-proposal__muted">
        {hourMinute(proposal.decidedAt)} 확정 · 복용 알림을 켰다면 바뀐 시간에 와요.
      </p>
      {myDrugId && (
        <Link className="mt-btn mt-btn--assistive mt-btn--sm" to={calendarLink}>
          <Icon name="calendar" />
          복용 달력에서 보기
        </Link>
      )}
    </section>
  )
}

// 취소했거나 시간이 지났거나 약을 지운 카드. 일정을 바꾸지 않았다는 것과 바꾸려던 일정 요약
const closedTexts = {
  cancelled: (proposal) => ({
    icon: 'ban',
    title: '취소했어요',
    body: `${hourMinute(proposal.decidedAt)}에 취소해서 일정을 바꾸지 않았어요.`,
  }),
  expired: (proposal) => ({
    icon: 'clock',
    title: '확정할 수 있는 시간이 지났어요',
    body: `카드는 30분 동안만 쓸 수 있어 ${hourMinute(proposal.expiresAt)}에 끝났어요. 일정은 그대로예요. 바꾸려면 원하는 일정을 다시 말해 주세요.`,
  }),
  deleted: () => ({
    icon: 'trash',
    title: '약목록에서 지운 약이에요',
    body: '카드를 만든 뒤 이 약을 약목록에서 지워서 일정을 바꿀 수 없어요. 아무것도 바꾸지 않았어요.',
  }),
}

function ClosedCard({ proposal, reason, titleId, announce }) {
  const { icon, title, body } = closedTexts[reason](proposal)
  return (
    <section className="chat-proposal chat-proposal--closed chat-schedule" aria-labelledby={titleId}>
      <div className="chat-proposal__result" role={announce ? 'status' : undefined}>
        <Icon name={icon} size="l" />
        <h3 className="screen-item" id={titleId}>
          {title}
        </h3>
      </div>
      <p className="screen-body chat-proposal__muted">{body}</p>
      <p className="screen-caption chat-proposal__muted">바꾸려던 일정: {summaryOf(proposal.schedule)}</p>
    </section>
  )
}

// 바뀌는 값 한 줄: '지금'(회색 취소선) → '바뀐 뒤'(굵게). 화면 읽기는 '지금 …' · '바뀐 뒤 …'로 읽는다.
function ChangeRow({ field, current, proposed, year }) {
  const before = fieldValue(field.key, current, { year })
  return (
    <div className="chat-schedule__change">
      <dt className="screen-label">{field.label}</dt>
      <dd className="screen-body">
        <span className="chat-schedule__old">
          <VisuallyHidden>지금 </VisuallyHidden>
          {before === null ? <NoValue /> : <s>{before}</s>}
        </span>
        <span className="chat-schedule__arrow" aria-hidden="true">
          →
        </span>
        <span className="chat-schedule__new">
          <VisuallyHidden>바뀐 뒤 </VisuallyHidden>
          {fieldValue(field.key, proposed, { year }) ?? <NoValue />}
        </span>
      </dd>
    </div>
  )
}

// 대화 속 일정 바꾸기 카드(V3-CHAT-007, design/v3-2-chat-schedule.md B안, DEC-025). '지금 → 바뀐 뒤'를 보이고
// 확정해야 일정이 바뀐다(DEC-001). 카드 틀과 서버 상태 다루기는 등록 제안 카드(ChatProposal.jsx)와 같다.
// 마감 시각이 지나면 서버를 다시 부르지 않고 시간이 지난 카드로 바꾼다. 약을 지운 카드는 서버를 부르지 않는다.
function ChatSchedule({ proposal, onChange }) {
  const titleId = useId()
  // 누르고 응답을 기다리는 버튼: 'confirm' · 'cancel'
  const [busy, setBusy] = useState(null)
  const [problem, setProblem] = useState(null)
  // 409 SCHEDULE_CHANGED: 다시 눌러도 같은 409라 확정 버튼을 끈다(취소는 누를 수 있음).
  const [changed, setChanged] = useState(false)
  // 결과 카드 위 안내(다른 화면에서 이미 확정한 카드)
  const [notice, setNotice] = useState(null)
  // 이 화면에서 결과 · 닫힌 카드로 바뀌었다. 바뀐 제목을 읽어 준다.
  const [announce, setAnnounce] = useState(false)
  const [expired, setExpired] = useState(false)
  const aliveRef = useRef(false)

  useEffect(() => {
    aliveRef.current = true
    return () => {
      aliveRef.current = false
    }
  }, [])

  const pending = proposal.status === 'PENDING'
  useEffect(() => {
    if (!pending) return
    const timer = setTimeout(() => {
      setExpired(true)
      setAnnounce(true)
    }, Math.max(0, Date.parse(proposal.expiresAt) - Date.now()))
    return () => clearTimeout(timer)
  }, [pending, proposal.expiresAt])

  const { schedule } = proposal
  if (proposal.status === 'CONFIRMED') {
    return <DoneCard proposal={proposal} titleId={titleId} announce={announce} notice={notice} />
  }
  if (proposal.status === 'CANCELLED' || proposal.status === 'EXPIRED' || expired) {
    const reason = proposal.status === 'CANCELLED' ? 'cancelled' : 'expired'
    return <ClosedCard proposal={proposal} reason={reason} titleId={titleId} announce={announce} />
  }
  if (schedule.myDrugId == null) {
    return <ClosedCard proposal={proposal} reason="deleted" titleId={titleId} announce={announce} />
  }

  const { current, proposed } = schedule
  const scheduled = current.startDate != null
  const changes = fields.filter((field) => !field.same(current, proposed))
  const kept = fields.filter((field) => field.same(current, proposed))
  const year = spansYears(current, proposed)

  // 서버 상태가 바뀐 카드를 받았다. 결과 · 닫힌 카드(약을 지운 카드 포함)로 바뀌면 제목을 읽어 준다.
  const settle = (next) => {
    if (next.status !== 'PENDING' || next.schedule.myDrugId == null) setAnnounce(true)
    onChange(next)
  }

  // 응답 없이 끊겨 서버가 확정했는지 모르면 카드를 다시 읽어 지금 상태를 보인다.
  const reread = () => {
    getChatProposal(proposal.proposalId)
      .then((next) => {
        if (!aliveRef.current) return
        if (next.status !== 'PENDING') setProblem(null)
        settle(next)
      })
      .catch(() => {})
  }

  const confirm = () => {
    setBusy('confirm')
    setProblem(null)
    confirmChatProposal(proposal.proposalId)
      .then((next) => {
        if (!aliveRef.current) return
        setBusy(null)
        settle(next)
      })
      .catch((error) => {
        if (!aliveRef.current) return
        setBusy(null)
        const { status, code, message } = errorOf(error)
        const latest = error.response?.data?.proposal
        if (status === 409 && latest) {
          if (code === 'SCHEDULE_CHANGED' && latest.schedule.myDrugId != null) {
            // 카드는 대기 그대로이고 '지금'은 카드 값 그대로 둔다. 안내 문구는 화면이 정한다.
            setChanged(true)
            setProblem(
              `카드를 만든 뒤 ${latest.schedule.drugName ?? schedule.drugName} 일정이 바뀌어서 바꾸지 않았어요. 지금 일정에서 바꾸려면 원하는 일정을 다시 말해 주세요.`,
            )
          } else if (code === 'PROPOSAL_ALREADY_CONFIRMED') {
            setNotice('다른 화면에서 이미 바꾼 카드예요. 더 바꾸지 않았어요.')
          }
          settle(latest)
        } else if (status === 404) {
          setProblem(problems.notFound)
        } else if (status === 400) {
          setProblem(message)
        } else if (status !== 401) {
          setProblem(problems.notSaved)
          // 끊김이거나 앞단(프록시)이 502 · 504로 바꿔 준 끊김이면 서버가 저장했는지 모른다.
          if (!error.response || status >= 500) reread()
        }
      })
  }

  const cancel = () => {
    setBusy('cancel')
    setProblem(null)
    cancelChatProposal(proposal.proposalId)
      .then((next) => {
        if (!aliveRef.current) return
        setBusy(null)
        settle(next)
      })
      .catch((error) => {
        if (!aliveRef.current) return
        setBusy(null)
        const { status, code } = errorOf(error)
        const latest = error.response?.data?.proposal
        if (status === 409 && latest) {
          if (code === 'PROPOSAL_ALREADY_CONFIRMED') setNotice('다른 화면에서 이미 바꾼 카드라 취소하지 않았어요.')
          settle(latest)
        } else if (status === 404) {
          setProblem(problems.notFound)
        } else if (status !== 401) {
          setProblem(problems.notCancelled)
        }
      })
  }

  return (
    <section className="chat-proposal chat-schedule" aria-labelledby={titleId}>
      <div className="chat-proposal__head">
        <h3 className="screen-item" id={titleId}>
          복용 일정 바꾸기
        </h3>
        <span className="screen-caption chat-proposal__until">
          <Icon name="clock" size="s" />
          {hourMinute(proposal.expiresAt)}까지 확정할 수 있어요
        </span>
      </div>
      <div className="chat-schedule__drug">
        <DrugThumb size="sm" />
        <p className="screen-body chat-schedule__name">{schedule.drugName}</p>
      </div>
      {scheduled ? (
        <>
          <p className="screen-label chat-schedule__section">바뀌는 값</p>
          <dl className="chat-schedule__changes">
            {changes.map((field) => (
              <ChangeRow key={field.key} field={field} current={current} proposed={proposed} year={year} />
            ))}
          </dl>
          {kept.length > 0 && (
            <>
              <p className="screen-label chat-schedule__section">그대로 두는 값</p>
              <dl className="chat-schedule__same">
                {kept.map((field) => (
                  <div key={field.key}>
                    <dt className="screen-caption">{field.label}</dt>
                    <dd className="screen-caption">{fieldValue(field.key, proposed, { year }) ?? <NoValue />}</dd>
                  </div>
                ))}
              </dl>
            </>
          )}
        </>
      ) : (
        <>
          <p className="screen-label chat-schedule__section">지금은 일정이 없어요 · 새로 넣을 일정</p>
          <ValueList values={proposed} fresh year={year} />
        </>
      )}
      {!problem && (
        <p className="screen-caption chat-schedule__note">
          <Icon name="info" size="s" />
          <span>
            {scheduled
              ? '말씀하지 않은 값은 지금 일정 그대로 뒀어요.'
              : '지금은 일정이 없어서 말씀하지 않은 값은 기본 일정(오늘 하루, 07:00 · 11:00 · 17:00)으로 채웠어요.'}
          </span>
        </p>
      )}
      {problem && (
        <Notice variant="negative" role="alert">
          {problem}
        </Notice>
      )}
      <div className="chat-proposal__actions">
        <button className="mt-btn mt-btn--solid" type="button" disabled={busy !== null || changed} onClick={confirm}>
          <Icon
            name={busy === 'confirm' ? 'loader' : 'calendarCheck'}
            className={busy === 'confirm' ? 'icon--spin' : undefined}
          />
          {busy === 'confirm' ? '바꾸는 중…' : '이 일정으로 바꾸기'}
        </button>
        <button className="mt-btn mt-btn--assistive" type="button" disabled={busy !== null} onClick={cancel}>
          {busy === 'cancel' && <Icon name="loader" className="icon--spin" />}
          취소
        </button>
      </div>
      <p className="screen-caption chat-proposal__muted">확정하기 전에는 지금 일정이 그대로예요.</p>
    </section>
  )
}

export default ChatSchedule
