import { Fragment, useId, useState } from 'react'
import { answerBlocks, boldParts, durTypes, messageTime, sourceGroups, sourceLabels } from '../chatInfo.js'
import { isDate } from '../dates.js'
import { dotDate } from '../text.js'
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

// 공식 자료의 약 하나(목업 CHT-02-03 · 14). 같은 약의 출처(e약은요 · 의약품 허가정보 · DUR)를 한 묶음에 줄로 적는다.
// 확인한 출처는 자료 갱신일을, 확인하지 못한 출처는 「확인 불가」를 쓴다. 확인하지 못한 근거의 날짜는 조회한 날이라 갱신일로 쓰지 않는다.
// 자료 없음과 조회 실패는 꼬리표에서 나뉘지 않아 둘 다 「확인 불가」다. 허가 취하는 약에 대한 것이라 약 이름 옆에 둔다.
function OfficialDrug({ drug }) {
  const missing = drug.sources.every((source) => source.ok !== true)
  const cancelled = drug.sources.some((source) => source.cancelled === true)
  return (
    <li className={`chat-answer__source${missing ? ' chat-answer__source--missing' : ''}`}>
      <div className="chat-answer__source-head">
        <span className="chat-answer__source-name">{drug.drugName}</span>
        {cancelled && <span className="mt-badge mt-badge--negative">허가 취하</span>}
      </div>
      <dl className="screen-caption chat-answer__facts">
        {drug.sources.map((source, index) => (
          <div className="chat-answer__fact" key={index}>
            <dt>{sourceLabels[source.source]}</dt>{' '}
            <dd>
              {source.ok === true ? (
                <span className="chat-answer__date">자료 갱신일 {dateText(source.updatedAt)}</span>
              ) : (
                <span className="mt-badge mt-badge--caution">확인 불가</span>
              )}
            </dd>
          </div>
        ))}
      </dl>
      {cancelled && (
        <p className="screen-caption chat-answer__cancelled">
          허가가 취하된 약으로 확인됐어요. 계속 드셔도 되는지 의사·약사와 상담해 주세요.
        </p>
      )}
    </li>
  )
}

// 공식 자료 영역. 약은 서버가 붙인 순서대로 둔다.
function OfficialSources({ drugs }) {
  return (
    <div className="chat-answer__group">
      <h3 className="screen-label chat-answer__group-title">공식 자료</h3>
      <ul className="chat-answer__sources">
        {drugs.map((drug) => (
          <OfficialDrug key={drug.key} drug={drug} />
        ))}
      </ul>
    </div>
  )
}

// 내가 입력한 복용 기록 영역. 약 목록에 등록한 정보라 공식 자료와 따로 두고, 그 정보의 변경일만 적는다.
function MySources({ sources, profileName }) {
  return (
    <div className="chat-answer__group">
      <div className="chat-answer__group-head">
        <h3 className="screen-label chat-answer__group-title">내가 입력한 복용 기록</h3>
        <p className="screen-caption chat-answer__muted">{profileName}의 약 목록에 등록한 내용이에요.</p>
      </div>
      <ul className="chat-answer__sources">
        {sources.map((source, index) => (
          <li className="chat-answer__source chat-answer__source--mine" key={index}>
            <span className="chat-answer__source-name">{source.drugName}</span>
            <span className="screen-caption chat-answer__source-meta">변경일 {dateText(source.updatedAt)}</span>
          </li>
        ))}
      </ul>
    </div>
  )
}

// 답변 근거(목업 CHT-02-03). 이 답변을 만들 때 저장된 근거만 펼치고, 지금 약 정보를 새로 불러오지 않는다.
// 펼치면 공식 자료와 내가 입력한 복용 기록을 나눠 보여 준다.
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
  const { official, mine } = sourceGroups(sources)
  const officialSources = official.flatMap((drug) => drug.sources)
  const confirmed = official.map((drug) => ({ ...drug, sources: drug.sources.filter((source) => source.ok === true || source.cancelled === true) })).filter((drug) => drug.sources.length > 0)
  const unverified = official.map((drug) => ({ ...drug, sources: drug.sources.filter((source) => source.ok !== true && source.cancelled !== true) })).filter((drug) => drug.sources.length > 0)
  // 공식 자료를 하나도 확인하지 못한 답변(목업 CHT-02-13): 근거 보기 없이 펼쳐 두고 공식 자료는 한 줄로 묶는다.
  // 꼬리표에는 확인하지 못한 까닭이 없어 목업의 「자료 없음」 · 「조회 실패」 문구는 쓰지 않는다.
  // 허가 취하가 확인된 근거가 있으면 한 줄로 묶지 않고 약별 목록으로 보여 준다.
  if (
    officialSources.length > 0 &&
    officialSources.every((source) => source.ok !== true && source.cancelled !== true)
  ) {
    return (
      <div className="chat-answer__evidence">
        <div className="chat-answer__groups">
          <div className="chat-answer__group">
            <h3 className="screen-label chat-answer__group-title">공식 자료</h3>
            <ul className="chat-answer__sources">
              <li className="chat-answer__source chat-answer__source--missing">
                <div className="chat-answer__source-head">
                  <span className="mt-badge mt-badge--caution">확인 불가</span>
                  <span className="chat-answer__source-name">공식 자료를 확인하지 못했어요.</span>
                </div>
              </li>
            </ul>
          </div>
          {mine.length > 0 && <MySources sources={mine} profileName={profileName} />}
        </div>
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
        {open ? '근거 접기' : '근거 보기'}
        <Icon name={open ? 'chevronUp' : 'chevronDown'} size="s" />
      </button>
      {open && (
        <div className="chat-answer__groups" id={listId}>
          {confirmed.length > 0 && <OfficialSources drugs={confirmed} />}
          {mine.length > 0 && <MySources sources={mine} profileName={profileName} />}
          {unverified.length > 0 && (
            <details className="chat-answer__unverified">
              <summary className="screen-label">확인하지 못한 출처</summary>
              <ul className="chat-answer__sources">
                {unverified.map((drug) => <OfficialDrug key={drug.key} drug={drug} />)}
              </ul>
            </details>
          )}
        </div>
      )}
    </div>
  )
}

// 본문 한 줄. **굵게** 만 <b> 로 바꾸고 나머지는 글자 그대로 넣는다.
function Inline({ text }) {
  return boldParts(text).map((part, index) =>
    part.bold ? <b key={index}>{part.text}</b> : <Fragment key={index}>{part.text}</Fragment>,
  )
}

// 문단 · 목록 항목의 여러 줄. 서버가 저장한 줄바꿈대로 줄을 바꾼다.
function Lines({ lines }) {
  return lines.map((line, index) => (
    <Fragment key={index}>
      {index > 0 && <br />}
      <Inline text={line} />
    </Fragment>
  ))
}

// 글머리 · 번호 목록. 항목 아래 목록도 같은 모양으로 한 번 더 들여 쓴다.
function AnswerList({ list }) {
  const Tag = list.ordered ? 'ol' : 'ul'
  return (
    <Tag
      className={`screen-body chat-answer__text chat-answer__list${list.ordered ? ' chat-answer__list--ordered' : ''}`}
      start={list.ordered && list.start !== 1 ? list.start : undefined}
    >
      {list.items.map((item, index) => (
        <li key={index}>
          <Lines lines={item.lines} />
          {item.lists.map((child, childIndex) => (
            <AnswerList key={childIndex} list={child} />
          ))}
        </li>
      ))}
    </Tag>
  )
}

function AnswerTable({ table }) {
  return (
    <div className="chat-answer__table-wrap" role="region" aria-label="답변 표" tabIndex={0}>
      <table className="screen-body chat-answer__table">
        <thead>
          <tr>{table.header.map((cell, index) => (
            <th scope="col" key={index} style={{ textAlign: table.align[index] }}><Inline text={cell} /></th>
          ))}</tr>
        </thead>
        <tbody>{table.rows.map((row, rowIndex) => (
          <tr key={rowIndex}>{row.map((cell, index) => (
            <td key={index} style={{ textAlign: table.align[index] }}><Inline text={cell} /></td>
          ))}</tr>
        ))}</tbody>
      </table>
    </div>
  )
}

// 근거를 뗀 답변 본문. 표·문단·목록도 React 글자로만 표시하며 HTML을 실행하지 않음.
function AnswerBody({ body }) {
  return answerBlocks(body).map((block, index) =>
    block.kind === 'table' ? (
      <AnswerTable key={index} table={block} />
    ) : block.kind === 'list' ? (
      <AnswerList key={index} list={block} />
    ) : (
      <p className="screen-body chat-answer__text" key={index}>
        <Lines lines={block.lines} />
      </p>
    ),
  )
}

// 필받음의 답변 말풍선(목업 CHT-02). 서버가 저장한 본문을 문단 · 목록 · 굵게로 보여 주고, 금기 정보 카드와 근거를 붙인다.
// message.answer: chatInfo.js splitAnswer 로 나눈 본문과 근거
function ChatAnswer({ message, profileName }) {
  const { body, sources } = message.answer

  return (
    <div className="chat-msg chat-msg--answer">
      <p className="visually-hidden">필받음의 답변</p>
      <div className="chat-msg__answer chat-answer">
        <AnswerBody body={body} />
        {message.durNotice && <DurNotice notice={message.durNotice} />}
        <Evidence sources={sources} profileName={profileName} />
      </div>
      <span className="screen-caption chat-msg__time">{messageTime(message.createdAt)} 답변</span>
    </div>
  )
}

export default ChatAnswer
