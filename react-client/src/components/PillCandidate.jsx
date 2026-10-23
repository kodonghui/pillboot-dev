import { useId } from 'react'
import { matchRows } from '../pillInfo.js'
import DrugIngredient from './DrugIngredient.jsx'
import DrugThumb from './DrugThumb.jsx'
import Icon from './Icon.jsx'
import './PillCandidate.css'

// 알약 후보 한 개(목업 IMG-05 .cand): 공식 사진 · 이름 · 일치 개수 · 성분 · 제조사와 "내 사진 | 공식 정보 | 결과" 비교 표.
// 고를 수 있으면 카드 전체가 라디오의 label 이다. preview: 고를 수 없는 미리보기(후보가 많을 때)
// 라디오 이름은 약 이름만 읽고, 일치 개수와 비교 표는 설명으로 읽는다.
function PillCandidate({ candidate, clues, name, checked, onChange, preview }) {
  const id = useId()
  const rows = matchRows(candidate, clues)
  const matches = rows.filter((row) => row.matched).length
  const registered = candidate.alreadyRegistered
  const selectable = !preview && !registered

  // 표는 이름 영역 밖에 두어, 좁은 화면에서는 라디오 · 사진 아래까지 넓게 편다.
  const body = (
    <>
      <span className="pill-candidate__body">
        <span className="pill-candidate__title">
          <span className="screen-item pill-candidate__name" id={`${id}-name`}>
            {candidate.name}
          </span>
          <span className="screen-caption pill-candidate__muted" id={`${id}-count`}>
            일치 {matches}개
          </span>
        </span>
        <DrugIngredient value={candidate.ingredientName} />
        {candidate.maker && <span className="screen-caption pill-candidate__muted">{candidate.maker}</span>}
        {registered && (
          <span className="screen-caption pill-candidate__registered">
            <Icon name="check" size="s" />
            이미 등록한 약이에요
          </span>
        )}
      </span>
      <span className="pill-candidate__table" id={`${id}-table`}>
        <span className="screen-label pill-candidate__th">항목</span>
        <span className="screen-label pill-candidate__th pill-candidate__mine">내 사진</span>
        <span className="screen-label pill-candidate__th">공식 정보</span>
        <span className="screen-label pill-candidate__th">결과</span>
        {rows.map((row) => (
          <span className="pill-candidate__row" key={row.field}>
            <span>{row.label}</span>
            <span className="pill-candidate__mine">{row.mine}</span>
            <span className={row.official ? undefined : 'pill-candidate__none'}>{row.official ?? '공식 정보 없음'}</span>
            {row.matched ? (
              <span className="mt-badge mt-badge--positive pill-candidate__match">
                <Icon name="check" size="s" />
                일치
              </span>
            ) : (
              <span className="mt-badge pill-candidate__unknown">확인 못 함</span>
            )}
          </span>
        ))}
      </span>
    </>
  )

  if (!selectable) {
    return (
      <div
        className={registered ? 'pill-candidate pill-candidate--registered' : 'pill-candidate pill-candidate--preview'}
        aria-disabled={registered || undefined}
      >
        {registered && <span className="pill-candidate__spacer" aria-hidden="true" />}
        <DrugThumb imageUrl={candidate.imageUrl} dim={registered} />
        {body}
      </div>
    )
  }

  return (
    <label className={checked ? 'pill-candidate pill-candidate--checked' : 'pill-candidate'}>
      <input
        type="radio"
        name={name}
        value={candidate.itemSeq}
        checked={checked}
        onChange={onChange}
        aria-labelledby={`${id}-name`}
        aria-describedby={`${id}-count ${id}-table`}
      />
      <DrugThumb imageUrl={candidate.imageUrl} />
      {body}
    </label>
  )
}

export default PillCandidate
