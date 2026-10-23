import { Fragment, useId, useState } from 'react'
import { durTypes, messageTime, sourceLabels } from '../chatInfo.js'
import { isDate } from '../dates.js'
import { dotDate, iAfter } from '../text.js'
import Icon from './Icon.jsx'
import './ChatAnswer.css'

// 자료 갱신일 · 변경일. 날짜가 없거나 읽을 수 없으면 "날짜 확인 불가"다(목업 CHT-02-03).
function dateText(date) {
  return isDate(date) ? dotDate(date) : '날짜 확인 불가'
}

// 약 이름들을 굵게 이어 쓴다.
function DrugNames({ names, separator }) {
  return names.map((name, index) => (
    <Fragment key={`${index}-${name}`}>
      {index > 0 && separator}
      <b>{name}</b>
    </Fragment>
  ))
}

// 금기 정보 경고 카드 하나(목업 CHT-02-04 · 15). 병용금기는 두 약을 + 로 묶고, 나머지는 경고마다 카드를 나눈다.
function DurCard({ item, type }) {
  return (
    <div className={`mt-notice mt-notice--${type.tone} chat-answer__dur`}>
      <div className="chat-answer__dur-head">
        <Icon name="alertTriangle" size="l" />
        <h3 className="screen-item">{type.title}</h3>
        <span className={`mt-badge chat-answer__dur-badge chat-answer__dur-badge--${type.tone}`}>{type.label}</span>
      </div>
      <p className="screen-body chat-answer__dur-names">
        <DrugNames names={item.drugNames} separator=" + " />
      </p>
      <p>{item.content}</p>
      <p className="screen-caption">자료 갱신일 {dateText(item.updatedAt)}</p>
      <p>
        <b>{type.closing}</b>
      </p>
    </div>
  )
}

// 금기 정보(목업 CHT-02-04 · 15 · 16). 서버가 보낸 결과만 보여 주고 「안전」 · 「금기 없음」으로 바꿔 쓰지 않는다.
// 약이 없을 때(NO_DRUG)는 대화방의 "약을 등록하면…" 안내가 대신하므로 카드를 만들지 않는다.
function DurNotice({ notice }) {
  const unchecked = notice.uncheckedDrugNames ?? []
  const blocks = []

  if (notice.lookup === 'FAILED') {
    blocks.push(
      <div className="mt-notice mt-notice--caution chat-answer__dur" key="failed">
        <div className="chat-answer__dur-head">
          <Icon name="alertTriangle" size="l" />
          <h3 className="screen-item">지금은 금기 정보를 확인하지 못했어요.</h3>
        </div>
        <p>금기가 없다는 뜻은 아니니 약사에게 확인해 주세요.</p>
        {unchecked.length > 0 && (
          <p className="chat-answer__dur-names">
            <span className="screen-label">확인하지 못한 약</span> <DrugNames names={unchecked} separator=" · " />
          </p>
        )}
      </div>,
    )
  } else if (notice.lookup !== 'NO_DRUG') {
    for (const [index, item] of (notice.items ?? []).entries()) {
      const type = durTypes[item.type]
      if (type) blocks.push(<DurCard key={index} item={item} type={type} />)
    }
    if (unchecked.length > 0) {
      blocks.push(
        <div className="mt-notice mt-notice--caution" key="partial">
          <Icon name="alertTriangle" className="mt-notice__icon" />
          <div className="mt-notice__body chat-answer__note">
            <p className="mt-notice__text">
              <b>금기 정보를 확인한 약 {notice.checkedCount}개</b> · 확인하지 못한 약:{' '}
              <DrugNames names={unchecked} separator=" · " />
            </p>
            <p className="mt-notice__text">확인하지 못했다는 것은 금기가 없다는 뜻이 아니에요.</p>
          </div>
        </div>,
      )
    } else if (notice.result === 'NONE') {
      blocks.push(
        <div className="mt-notice" key="none">
          <Icon name="info" className="mt-notice__icon" />
          <div className="mt-notice__body chat-answer__note">
            <p className="mt-notice__text">
              <b>금기 정보를 확인한 약 {notice.checkedCount}개</b>에서 함께 먹을 때의 경고는 나오지 않았어요.
            </p>
            <p className="mt-notice__text">궁금한 점은 의사·약사에게 확인해 주세요.</p>
          </div>
        </div>,
      )
    }
  }

  if (blocks.length === 0) return null
  return (
    <>
      {blocks}
      <p className="screen-caption chat-answer__muted">출처 DUR 공공데이터</p>
    </>
  )
}

// 근거 한 줄. 공식 정보(e약은요 · 의약품 허가정보 · DUR)와 내가 입력한 복용 정보를 나눠 보여 준다.
function SourceRow({ source, profileName }) {
  if (source.source === 'USER_INPUT') {
    return (
      <li className="chat-answer__source">
        <div className="chat-answer__source-head">
          <span className="mt-badge mt-badge--primary">내가 입력한 정보</span>
          <span className="chat-answer__source-name">{source.drugName} 복용 정보</span>
        </div>
        <p className="screen-caption chat-answer__source-meta">
          <span>
            출처 {profileName}
            {iAfter(profileName)} 등록한 복용 정보
          </span>
          <span>변경일 {dateText(source.updatedAt)}</span>
        </p>
      </li>
    )
  }
  if (!source.ok) {
    return (
      <li className="chat-answer__source chat-answer__source--missing">
        <div className="chat-answer__source-head">
          <span className="mt-badge mt-badge--caution">확인 불가</span>
          <span className="chat-answer__source-name">{source.drugName}</span>
        </div>
        <p className="screen-caption chat-answer__source-meta">
          <span>출처 {sourceLabels[source.source]}</span>
          <span>이 정보는 확인하지 못했어요.</span>
        </p>
      </li>
    )
  }
  return (
    <li className="chat-answer__source">
      <div className="chat-answer__source-head">
        <span className="mt-badge mt-badge--info">공식 정보</span>
        <span className="chat-answer__source-name">{source.drugName}</span>
        {source.cancelled && <span className="mt-badge mt-badge--negative">허가 취하</span>}
      </div>
      <p className="screen-caption chat-answer__source-meta">
        <span>출처 {sourceLabels[source.source]}</span>
        <span>자료 갱신일 {dateText(source.updatedAt)}</span>
      </p>
      {source.cancelled && (
        <p className="screen-caption chat-answer__cancelled">
          허가가 취하된 약으로 확인됐어요. 계속 드셔도 되는지 의사·약사와 상담해 주세요.
        </p>
      )}
    </li>
  )
}

// 답변 근거(목업 CHT-02-03). 이 답변을 만들 때 저장된 근거만 펼치고, 지금 약 정보를 새로 불러오지 않는다.
function Evidence({ sources, profileName }) {
  const [open, setOpen] = useState(false)
  const listId = useId()

  if (sources === 'broken') {
    return (
      <div className="chat-answer__evidence">
        <p className="screen-caption chat-answer__muted chat-answer__broken">
          <Icon name="alertCircle" size="s" />
          근거를 표시하지 못했어요. 답변은 그대로 볼 수 있어요.
        </p>
      </div>
    )
  }
  if (sources.length === 0) {
    return <p className="screen-caption chat-answer__muted">이 답변에는 함께 저장된 근거가 없어요.</p>
  }
  // 공식 정보를 하나도 확인하지 못한 답변(목업 CHT-02-13): 근거 보기 없이 펼쳐 두고 공식 정보는 한 줄로 묶는다.
  // 꼬리표에는 확인하지 못한 까닭이 없어 목업의 「자료 없음」 · 「조회 실패」 문구는 쓰지 않는다.
  const official = sources.filter((source) => source.source !== 'USER_INPUT')
  if (official.length > 0 && official.every((source) => !source.ok)) {
    return (
      <div className="chat-answer__evidence">
        <ul className="chat-answer__sources">
          <li className="chat-answer__source chat-answer__source--missing">
            <div className="chat-answer__source-head">
              <span className="mt-badge mt-badge--caution">공식 정보</span>
              <span className="chat-answer__source-name">공식 정보를 확인하지 못했어요.</span>
            </div>
          </li>
          {sources
            .filter((source) => source.source === 'USER_INPUT')
            .map((source, index) => (
              <SourceRow key={index} source={source} profileName={profileName} />
            ))}
        </ul>
      </div>
    )
  }
  return (
    <div className="chat-answer__evidence">
      <button
        className="mt-btn mt-btn--ghost mt-btn--sm chat-answer__toggle"
        type="button"
        aria-expanded={open}
        aria-controls={open ? listId : undefined}
        onClick={() => setOpen(!open)}
      >
        {open ? '근거 접기' : '근거 보기'} ({sources.length})
        <Icon name={open ? 'chevronUp' : 'chevronDown'} size="s" />
      </button>
      {open && (
        <ul className="chat-answer__sources" id={listId}>
          {sources.map((source, index) => (
            <SourceRow key={index} source={source} profileName={profileName} />
          ))}
        </ul>
      )}
    </div>
  )
}

// 필받음의 답변 말풍선(목업 CHT-02). 서버가 저장한 본문을 그대로 보여 주고, 금기 정보 카드와 근거를 붙인다.
// message.answer: chatInfo.js splitAnswer 로 나눈 본문과 근거
function ChatAnswer({ message, profileName }) {
  const { body, sources } = message.answer

  return (
    <div className="chat-msg chat-msg--answer">
      <p className="visually-hidden">필받음의 답변</p>
      <div className="chat-msg__answer chat-answer">
        {body.split(/\n\s*\n/).map((paragraph, index) => (
          <p className="screen-body chat-answer__text" key={index}>
            {paragraph}
          </p>
        ))}
        {message.durNotice && <DurNotice notice={message.durNotice} />}
        <Evidence sources={sources} profileName={profileName} />
      </div>
      <span className="screen-caption chat-msg__time">{messageTime(message.createdAt)} 답변</span>
    </div>
  )
}

export default ChatAnswer
