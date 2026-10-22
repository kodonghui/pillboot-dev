import { Fragment } from 'react'
import { colorNames, lineNames, shapeName, stateLabels } from '../pillInfo.js'
import VisuallyHidden from './VisuallyHidden.jsx'
import './PillClueList.css'

const stateBadges = { READABLE: 'mt-badge mt-badge--primary', NONE: 'mt-badge', UNREADABLE: 'mt-badge mt-badge--caution' }

// 각인 · 분할선 상태 표(목업 .mk .st-r · st-n · st-u). 상태가 비어 있으면 서버 규칙처럼 미판독으로 본다.
function StateBadge({ state }) {
  const known = stateLabels[state] ? state : 'UNREADABLE'
  return <span className={stateBadges[known]}>{stateLabels[known]}</span>
}

function NeedBadge() {
  return <span className="mt-badge mt-badge--caution">필요</span>
}

function printValue(clues, side) {
  const state = clues?.[`print${side}State`]
  return (
    <>
      <StateBadge state={state} />
      {state === 'READABLE' && clues[`print${side}`]}
    </>
  )
}

function lineValue(clues, side) {
  const state = clues?.[`line${side}State`]
  const line = lineNames[clues?.[`line${side}`]]
  return (
    <>
      <StateBadge state={state} />
      {state === 'READABLE' && line && (
        <span>
          <span aria-hidden="true">{line.symbol}</span>
          <VisuallyHidden>{line.name}</VisuallyHidden>
        </span>
      )}
    </>
  )
}

// 모양 · 색상은 상태가 없어 값이 없으면 미판독으로 보여 준다.
function plainValue(text) {
  return text || <StateBadge state="UNREADABLE" />
}

// 사진에서 읽은 단서(목업 .cl). grouped: 사진 1 · 사진 2 · 두 면 공통으로 나눠 분할선까지(IMG-05 왼쪽),
// 아니면 각인 · 모양 · 색상 네 줄(IMG-06 상자). missing(서버 missingFields)에 든 항목은 "필요"를 붙인다.
function PillClueList({ clues, grouped, missing = [] }) {
  const shape = plainValue(shapeName(clues?.shape))
  const colors = plainValue(colorNames(clues?.colorClass1, clues?.colorClass2).join(', '))

  if (!grouped) {
    const needPrint = missing.includes('IMPRINT')
    const rows = [
      { key: 'front', label: '사진 1 각인', value: printValue(clues, 'Front'), need: needPrint && clues?.printFrontState !== 'READABLE' },
      { key: 'back', label: '사진 2 각인', value: printValue(clues, 'Back'), need: needPrint && clues?.printBackState !== 'READABLE' },
      { key: 'shape', label: '모양', value: shape, need: missing.includes('SHAPE') },
      { key: 'color', label: '색상', value: colors, need: missing.includes('COLOR') },
    ]
    return (
      <dl className="pill-clues">
        {rows.map((row) => (
          <Fragment key={row.key}>
            <dt className="screen-label">{row.label}</dt>
            <dd>
              {row.value}
              {row.need && <NeedBadge />}
            </dd>
          </Fragment>
        ))}
      </dl>
    )
  }

  return (
    <div className="pill-clues__groups">
      <p className="screen-label pill-clues__face">사진 1</p>
      <dl className="pill-clues">
        <dt className="screen-label">각인</dt>
        <dd>{printValue(clues, 'Front')}</dd>
        <dt className="screen-label">분할선</dt>
        <dd>{lineValue(clues, 'Front')}</dd>
      </dl>
      <p className="screen-label pill-clues__face">사진 2</p>
      <dl className="pill-clues">
        <dt className="screen-label">각인</dt>
        <dd>{printValue(clues, 'Back')}</dd>
        <dt className="screen-label">분할선</dt>
        <dd>{lineValue(clues, 'Back')}</dd>
      </dl>
      <p className="screen-label pill-clues__face">두 면 공통</p>
      <dl className="pill-clues">
        <dt className="screen-label">모양</dt>
        <dd>{shape}</dd>
        <dt className="screen-label">색상</dt>
        <dd>{colors}</dd>
      </dl>
    </div>
  )
}

export default PillClueList
