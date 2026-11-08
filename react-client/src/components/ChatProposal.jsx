import { useEffect, useId, useRef, useState } from 'react'
import { Link } from 'react-router'
import { cancelChatProposal, confirmChatProposal, getChatProposal } from '../api/chat.js'
import { errorOf } from '../api/client.js'
import { paths } from '../paths.js'
import { hourMinute, iAfter } from '../text.js'
import DrugThumb from './DrugThumb.jsx'
import Icon from './Icon.jsx'
import Notice from './Notice.jsx'
import StrengthMatchBadge from './StrengthMatchBadge.jsx'
import StrengthName from './StrengthName.jsx'
import './ChatProposal.css'

// 확정 · 취소가 되지 않았을 때 버튼 위 빨간 안내(design/v3-2-chat-proposal.md 표시와 전환)
const problems = {
  notFound: '이 카드를 찾을 수 없어요. 대화를 다시 열어 주세요.',
  notSaved: '지금은 약목록에 넣지 못했어요. 잠시 뒤 다시 눌러 주세요.',
  notCancelled: '지금은 취소하지 못했어요. 잠시 뒤 다시 눌러 주세요.',
}

// 후보 요약 · 충돌 안내의 이름: 기본 이름에 함량을 붙여 쓴다(기본 이름이 없으면 품목명).
function shortName(item) {
  return item.baseName ? item.baseName + (item.strengthText ?? '') : item.name
}

// 읽은 이름(mention)마다 후보를 묶는다. 묶음은 카드에 처음 나온 순서, 묶음 안은 itemNo 순서다.
function groupsOf(items) {
  const groups = new Map()
  for (const item of [...items].sort((a, b) => a.itemNo - b.itemNo)) {
    const key = item.mention ?? ''
    if (!groups.has(key)) groups.set(key, [])
    groups.get(key).push(item)
  }
  return [...groups].map(([mention, list]) => ({ mention, items: list }))
}

// 미리 고를 후보. 묶음마다 '함량 일치'이면서 고를 수 있는 후보가 하나뿐이면 그 번호다(IMG-04와 같은 규칙).
function autoPicks(items) {
  const picks = {}
  for (const group of groupsOf(items)) {
    const matches = group.items.filter((item) => !item.alreadyRegistered && item.strengthMatch === 'MATCH')
    if (matches.length === 1) picks[group.mention] = matches[0].itemNo
  }
  return picks
}

// 409 CONFLICT 안내. 고른 약 중 그사이 약목록에 들어간 약의 이름을 쓴다.
function conflictText(items) {
  const names = items.map(shortName)
  const quoted = names.map((name) => `‘${name}’`).join('·')
  return `고른 약 중 ${quoted}${iAfter(names.at(-1))} 그사이 약목록에 들어가 있어서 아무것도 넣지 않았어요. 나머지를 확인하고 다시 넣어 주세요.`
}

// 시럽 처방의 1회 양('20mL', 서버 PrescriptionDose.liquidAmount). 아니면 null.
function liquidAmount(rx) {
  return /^[0-9]{1,3}(\.[0-9]{1,2})?mL$/.test(rx?.dosePerTime ?? '') ? rx.dosePerTime : null
}

// 처방전 한 줄에서 읽은 값(인쇄된 그대로). 읽지 못한 칸은 뺀다. 예) '처방전: 1회 1개 · 하루 3번 · 3일분'.
// 2026-11-08 QA: 시럽은 '1회 20개'가 아니라 '1회 20mL'
function prescriptionText(rx) {
  const parts = [
    rx.dosePerTime != null && `1회 ${liquidAmount(rx) ?? `${rx.dosePerTime}개`}`,
    rx.dailyCount != null && `하루 ${rx.dailyCount}번`,
    rx.totalDays != null && `${rx.totalDays}일분`,
  ].filter(Boolean)
  return parts.length > 0 ? `처방전: ${parts.join(' · ')}` : null
}

// 처방전 값이 일정 저장 규칙(1회 정수 1~10개 · 하루 1~3번 · 1~365일)에 그대로 들어가는지. 서버(PrescriptionDose)와 같은 규칙이다.
// 0.5처럼 정수가 아닌 1회 투약량은 일정에 넣지 않는다. 시럽의 1회 양('20mL')은 1회 1개와 복용 때 '1회 20mL'로 넣는다.
function schedulable(rx) {
  if (!rx || !(rx.dailyCount >= 1 && rx.dailyCount <= 3 && rx.totalDays >= 1 && rx.totalDays <= 365)) return false
  if (liquidAmount(rx)) return true
  if (!/^[0-9]+(\.0+)?$/.test(rx.dosePerTime ?? '')) return false
  const dose = Number(rx.dosePerTime)
  return dose >= 1 && dose <= 10
}

// 후보 한 줄의 이름 · 함량 비교(또는 이미 등록됨) · 제약사 · 처방전 값
function ItemBody({ item }) {
  return (
    <span className="chat-proposal__body">
      <span className="screen-body chat-proposal__name">
        <StrengthName name={item.name} baseName={item.baseName} strengthText={item.strengthText} />
      </span>
      {item.alreadyRegistered ? (
        <span className="screen-caption chat-proposal__registered">
          <Icon name="check" size="s" />
          이미 약목록에 있어요
        </span>
      ) : (
        <StrengthMatchBadge match={item.strengthMatch} />
      )}
      {item.maker && <span className="screen-caption chat-proposal__muted">{item.maker}</span>}
      {item.prescription && prescriptionText(item.prescription) && (
        <span className="screen-caption chat-proposal__muted">{prescriptionText(item.prescription)}</span>
      )}
    </span>
  )
}

// 확정한 카드: 넣은 약과 고르지 않은 수. notice: 다른 화면에서 이미 확정한 카드일 때 위에 두는 안내
function DoneCard({ proposal, titleId, announce, notice }) {
  const chosen = proposal.items.filter((item) => item.chosen)
  const rest = proposal.items.filter((item) => !item.chosen && !item.alreadyRegistered).length
  const applied = chosen.filter((item) => item.prescription?.applied).length
  return (
    <section className="chat-proposal chat-proposal--done" aria-labelledby={titleId}>
      {notice && <Notice variant="info">{notice}</Notice>}
      <div className="chat-proposal__result" role={announce ? 'status' : undefined}>
        <Icon name="checkCircle" size="l" />
        <h3 className="screen-item" id={titleId}>
          약목록에 {chosen.length}개를 넣었어요
        </h3>
      </div>
      <ul className="chat-proposal__done-list">
        {chosen.map((item) => (
          <li key={item.itemNo}>
            <DrugThumb imageUrl={item.imageUrl} size="sm" />
            <span className="chat-proposal__body">
              <span className="screen-body chat-proposal__name">
                <StrengthName name={item.name} baseName={item.baseName} strengthText={item.strengthText} />
              </span>
              {item.prescription?.applied && (
                <span className="screen-caption chat-proposal__muted">
                  처방대로 오늘부터 {item.prescription.totalDays}일 · 하루 {item.prescription.dailyCount}번 일정을 넣었어요
                </span>
              )}
            </span>
          </li>
        ))}
      </ul>
      <p className="screen-caption chat-proposal__muted">
        {hourMinute(proposal.decidedAt)} 확정{rest > 0 && ` · 고르지 않은 ${rest}개는 넣지 않았어요.`}
      </p>
      <Link className="mt-btn mt-btn--assistive mt-btn--sm" to={paths.drugs}>
        <Icon name="list" />
        {applied > 0 ? '약목록에서 복용 시각 확인하기' : '약목록에서 복용 시각 정하기'}
      </Link>
    </section>
  )
}

// 취소했거나 시간이 지난 카드. 아무것도 넣지 않았다는 것과 후보 요약(두 개까지, 나머지는 수)
function ClosedCard({ proposal, cancelled, titleId, announce }) {
  const names = proposal.items.map(shortName)
  const shown = names.slice(0, 2).join(' · ') + (names.length > 2 ? ` 외 ${names.length - 2}개` : '')
  return (
    <section className="chat-proposal chat-proposal--closed" aria-labelledby={titleId}>
      <div className="chat-proposal__result" role={announce ? 'status' : undefined}>
        <Icon name={cancelled ? 'ban' : 'clock'} size="l" />
        <h3 className="screen-item" id={titleId}>
          {cancelled ? '취소했어요' : '고를 수 있는 시간이 지났어요'}
        </h3>
      </div>
      <p className="screen-body chat-proposal__muted">
        {cancelled
          ? `${hourMinute(proposal.decidedAt)}에 취소해서 약목록에 아무것도 넣지 않았어요.`
          : `카드는 30분 동안만 쓸 수 있어 ${hourMinute(proposal.expiresAt)}에 끝났어요. 약목록에 아무것도 넣지 않았어요. 넣으려면 약 이름을 다시 보내 주세요.`}
      </p>
      {names.length > 0 && <p className="screen-caption chat-proposal__muted">후보: {shown}</p>}
    </section>
  )
}

// 대화 속 등록 제안 카드(V3-CHAT-002~006, design/v3-2-chat-proposal.md B안, DEC-023). 읽은 이름마다 후보 하나를 고르고
// 확정해야 약목록에 들어간다(DEC-001). 카드의 서버 상태는 대화방이 갖고, 확정 · 취소 · 다시 읽기로 받은 카드는 onChange 로 넘긴다.
// 마감 시각이 지나면 서버를 다시 부르지 않고 시간이 지난 카드로 바꾼다. 확정은 질문 진행과 따로라 답을 기다리는 중에도 누를 수 있다.
// fromPhoto: 사진으로 받은 카드(미리 고름 안내가 '사진에서 읽은'이 된다)
function ChatProposal({ proposal, fromPhoto, onChange }) {
  const titleId = useId()
  const groupName = useId()
  // 미리 고른 후보. 묶음 → itemNo
  const [auto] = useState(() => autoPicks(proposal.items))
  // 고른 후보. 묶음 → itemNo · 'skip'(넣지 않음). 없으면 아직 고르지 않음
  const [picks, setPicks] = useState(auto)
  // 처방전 값으로 일정도 넣기. 처방전 사진 카드에서만 보이고, 처음에는 켜 둔다(확정 전에 끌 수 있음).
  const [withSchedule, setWithSchedule] = useState(true)
  // 누르고 응답을 기다리는 버튼: 'confirm' · 'cancel'
  const [busy, setBusy] = useState(null)
  const [problem, setProblem] = useState(null)
  // 409 CONFLICT 로 새로 '이미 등록됨'이 된 고른 후보(빨간 테두리)
  const [conflictNos, setConflictNos] = useState([])
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

  if (proposal.status === 'CONFIRMED') {
    return <DoneCard proposal={proposal} titleId={titleId} announce={announce} notice={notice} />
  }
  if (proposal.status === 'CANCELLED' || proposal.status === 'EXPIRED' || expired) {
    return <ClosedCard proposal={proposal} cancelled={proposal.status === 'CANCELLED'} titleId={titleId} announce={announce} />
  }

  const groups = groupsOf(proposal.items)
  // 대화를 다시 불러와 그사이 등록된 후보는 고른 것으로 치지 않는다.
  const selectable = new Set(proposal.items.filter((item) => !item.alreadyRegistered).map((item) => item.itemNo))
  const itemNos = groups.map((group) => picks[group.mention]).filter((no) => selectable.has(no))
  const allRegistered = selectable.size === 0
  // 고른 약 중 처방전 값으로 일정을 넣을 수 있는 약 수
  const rxReady = proposal.items.filter((item) => itemNos.includes(item.itemNo) && schedulable(item.prescription)).length
  const applyPrescription = withSchedule && rxReady > 0

  const choose = (mention, value) => setPicks((current) => ({ ...current, [mention]: value }))

  // 서버 상태가 바뀐 카드를 받았다. 결과 · 닫힌 카드로 바뀌면 제목을 읽어 준다.
  const settle = (next) => {
    if (next.status !== 'PENDING') setAnnounce(true)
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
    const sent = itemNos
    setBusy('confirm')
    setProblem(null)
    setConflictNos([])
    confirmChatProposal(proposal.proposalId, sent, applyPrescription)
      .then((next) => {
        if (!aliveRef.current) return
        setBusy(null)
        settle(next)
      })
      .catch((error) => {
        if (!aliveRef.current) return
        setBusy(null)
        const { status, code, message } = errorOf(error)
        const current = error.response?.data?.proposal
        if (status === 409 && current) {
          if (code === 'CONFLICT') {
            // 카드는 대기 그대로다. 그사이 등록된 고른 후보는 고름을 풀고 빨간 테두리로 알린다.
            const taken = current.items.filter((item) => item.alreadyRegistered && sent.includes(item.itemNo))
            setConflictNos(taken.map((item) => item.itemNo))
            setProblem(taken.length > 0 ? conflictText(taken) : message)
          } else if (code === 'PROPOSAL_ALREADY_CONFIRMED') {
            setNotice('다른 화면에서 이미 넣은 카드예요. 더 넣지 않았어요.')
          }
          settle(current)
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
    setConflictNos([])
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
        const current = error.response?.data?.proposal
        if (status === 409 && current) {
          if (code === 'PROPOSAL_ALREADY_CONFIRMED') setNotice('다른 화면에서 이미 넣은 카드라 취소하지 않았어요.')
          settle(current)
        } else if (status === 404) {
          setProblem(problems.notFound)
        } else if (status !== 401) {
          setProblem(problems.notCancelled)
        }
      })
  }

  const autoNote = `${fromPhoto ? '사진에서 읽은' : '말씀하신'} 함량과 맞는 약이 하나라 미리 골라 뒀어요. 맞는지 확인해 주세요.`

  return (
    <section className="chat-proposal" aria-labelledby={titleId}>
      <div className="chat-proposal__head">
        <h3 className="screen-item" id={titleId}>
          약목록에 넣을 약 고르기
        </h3>
        <span className="screen-caption chat-proposal__until">
          <Icon name="clock" size="s" />
          {hourMinute(proposal.expiresAt)}까지 고를 수 있어요
        </span>
      </div>
      <div className="chat-proposal__groups">
        {groups.map((group, index) => {
          const name = `${groupName}-${index}`
          const value = picks[group.mention]
          return (
            <fieldset className="chat-proposal__group" key={group.mention} disabled={busy !== null}>
              <legend className="screen-label chat-proposal__mention">
                {group.mention ? `‘${group.mention}’에서 찾은 약` : '찾은 약'} · 하나 고르기
              </legend>
              {value !== undefined && value === auto[group.mention] && conflictNos.length === 0 && (
                <p className="screen-caption chat-proposal__note">
                  <Icon name="check" size="s" />
                  <span>{autoNote}</span>
                </p>
              )}
              {group.items.map((item) => {
                const conflict = conflictNos.includes(item.itemNo) ? ' chat-proposal__item--now-registered' : ''
                if (item.alreadyRegistered) {
                  return (
                    <div
                      className={`chat-proposal__item chat-proposal__item--registered chat-proposal__tile${conflict}`}
                      key={item.itemNo}
                      aria-disabled="true"
                    >
                      <span className="chat-proposal__spacer" aria-hidden="true" />
                      <DrugThumb imageUrl={item.imageUrl} size="sm" />
                      <ItemBody item={item} />
                    </div>
                  )
                }
                const checked = value === item.itemNo
                return (
                  <label
                    className={`chat-proposal__item chat-proposal__tile${checked ? ' chat-proposal__item--checked' : ''}`}
                    key={item.itemNo}
                  >
                    <input type="radio" name={name} checked={checked} onChange={() => choose(group.mention, item.itemNo)} />
                    <DrugThumb imageUrl={item.imageUrl} size="sm" />
                    <ItemBody item={item} />
                  </label>
                )
              })}
              <label
                className={`chat-proposal__item chat-proposal__tile chat-proposal__skip${value === 'skip' ? ' chat-proposal__item--checked' : ''}`}
              >
                <input type="radio" name={name} checked={value === 'skip'} onChange={() => choose(group.mention, 'skip')} />
                <span className="chat-proposal__body">
                  <span className="screen-body chat-proposal__name">넣지 않음</span>
                </span>
              </label>
            </fieldset>
          )
        })}
      </div>
      {allRegistered && <p className="screen-caption chat-proposal__muted">카드의 약이 모두 약목록에 있어요.</p>}
      {rxReady > 0 && (
        <label className="chat-proposal__rx">
          <input
            type="checkbox"
            checked={withSchedule}
            disabled={busy !== null}
            onChange={(event) => setWithSchedule(event.target.checked)}
          />
          <span className="screen-body">
            처방전 값으로 일정도 넣기({rxReady}개)
            <span className="screen-caption chat-proposal__muted chat-proposal__rx-help">
              오늘부터 처방 일수만큼, 하루 횟수에 맞춰 07:00 · 12:00 · 17:00 중 앞에서부터 넣어요. 반 알처럼 개수로 넣을 수 없는
              약은 약만 넣어요.
            </span>
          </span>
        </label>
      )}
      {problem && (
        <Notice variant="negative" role="alert">
          {problem}
        </Notice>
      )}
      <div className="chat-proposal__actions">
        <button
          className="mt-btn mt-btn--solid"
          type="button"
          disabled={busy !== null || itemNos.length === 0}
          onClick={confirm}
        >
          <Icon name={busy === 'confirm' ? 'loader' : 'list'} className={busy === 'confirm' ? 'icon--spin' : undefined} />
          {busy === 'confirm' ? '넣는 중…' : itemNos.length > 0 ? `${itemNos.length}개 약목록에 넣기` : '약목록에 넣기'}
        </button>
        <button className="mt-btn mt-btn--assistive" type="button" disabled={busy !== null} onClick={cancel}>
          {busy === 'cancel' && <Icon name="loader" className="icon--spin" />}
          취소
        </button>
      </div>
      <p className="screen-caption chat-proposal__muted">
        {applyPrescription
          ? '넣은 약의 복용 시각·1회 개수는 약목록에서 바꿀 수 있어요.'
          : '넣은 약의 복용 시각·1회 개수는 약목록에서 정해요.'}
      </p>
    </section>
  )
}

export default ChatProposal
