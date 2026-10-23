import { useEffect, useId, useState } from 'react'
import { searchDrugs } from '../api/drugs.js'
import { pickName } from '../scheduleForm.js'
import DrugName from './DrugName.jsx'
import DrugThumb from './DrugThumb.jsx'
import FieldError from './FieldError.jsx'
import Icon from './Icon.jsx'
import Notice from './Notice.jsx'
import { closeOnFocusOut } from './useDismiss.js'
import './DrugPicker.css'

// 공식 약 정보 찾기: 서버 검색 규칙(2글자 이상)과 목업 후보 수
const minLength = 2
const searchSize = 5
const searchDelay = 300

// 후보 이름에서 쓴 글자와 같은 부분을 굵게(목업 .lb-o b)
function Match({ text, query }) {
  const at = text.toLowerCase().indexOf(query.toLowerCase())
  if (at < 0) return text
  return (
    <>
      {text.slice(0, at)}
      <b>{text.slice(at, at + query.length)}</b>
      {text.slice(at + query.length)}
    </>
  )
}

// 일정 추가의 약 고르기(목업 SCH-03 "약"). 이름을 쓰면 아래로 후보 목록이 펼쳐진다(콤보박스).
// 후보는 이 사람의 약(choices 에서 이름으로 거름)과 공식 약 정보 검색 결과(이미 등록한 약은 뺌)다.
// 목록을 닫으면 "{이름}의 약에서 고르기" 칩으로도 고를 수 있다.
// picked: { kind: 'mine', drug: 일정 고르기 응답 } · { kind: 'official', drug: 검색 결과 } · null.
// 이름을 고쳐 쓰면 고른 약을 놓는다(onPick(null)). choices 가 null 이면 불러오는 중이고,
// choicesFailed 면 안내와 함께 onRetryChoices 로 다시 부른다.
function DrugPicker({ profileId, profileName, choices, choicesFailed, picked, error, onRetryChoices, onPick }) {
  const inputId = useId()
  const listId = useId()
  const hintId = useId()
  const errorId = useId()
  const chipsLabelId = useId()
  const pickedName = picked ? pickName(picked) : ''
  const [shownName, setShownName] = useState(pickedName)
  const [text, setText] = useState(pickedName)
  const [open, setOpen] = useState(false)
  const [activeId, setActiveId] = useState(null)
  const [search, setSearch] = useState(null)

  // 바깥에서 약을 고르면(주소로 들어온 약) 입력칸도 따라간다. 바깥에서 놓으면(저장 실패) 쓴 이름은 남긴다(목업 save-failed).
  // 사람을 바꾸면 화면이 key 로 새로 그려 비운다.
  if (pickedName !== shownName) {
    setShownName(pickedName)
    if (pickedName) setText(pickedName)
  }

  const query = text.trim()
  const showList = open && query.length > 0
  const key = `${profileId}|${query}`
  const searching = showList && query.length >= minLength

  useEffect(() => {
    if (!searching) return
    let active = true
    const timer = setTimeout(() => {
      searchDrugs({ name: query, profileId, page: 1, size: searchSize })
        .then((data) => active && setSearch({ key, items: data.content.filter((item) => !item.registered) }))
        .catch(() => active && setSearch({ key, failed: true }))
    }, searchDelay)
    return () => {
      active = false
      clearTimeout(timer)
    }
  }, [searching, query, profileId, key])

  const found = searching && search?.key === key ? search : null
  const mine = showList && Array.isArray(choices) ? choices.filter((drug) => drug.drugName.toLowerCase().includes(query.toLowerCase())) : []
  const official = found?.items ?? []
  const options = [
    ...mine.map((drug) => ({ id: `${listId}-mine-${drug.myDrugId}`, pick: { kind: 'mine', drug } })),
    ...official.map((drug) => ({ id: `${listId}-official-${drug.itemSeq}`, pick: { kind: 'official', drug } })),
  ]
  const active = options.find((option) => option.id === activeId) ?? null

  const choose = (pick) => {
    setShownName(pickName(pick))
    setText(pickName(pick))
    setOpen(false)
    setActiveId(null)
    onPick(pick)
  }

  const type = (event) => {
    setText(event.target.value)
    setOpen(true)
    setActiveId(null)
    if (picked) {
      setShownName('')
      onPick(null)
    }
  }

  const keyDown = (event) => {
    if (event.key === 'ArrowDown' || event.key === 'ArrowUp') {
      event.preventDefault()
      if (!showList) {
        setOpen(true)
        return
      }
      if (options.length === 0) return
      const at = options.indexOf(active)
      const step = event.key === 'ArrowDown' ? 1 : -1
      const next = at < 0 ? (step > 0 ? 0 : options.length - 1) : (at + step + options.length) % options.length
      setActiveId(options[next].id)
      document.getElementById(options[next].id)?.scrollIntoView({ block: 'nearest' })
    } else if (event.key === 'Enter' && showList) {
      event.preventDefault()
      if (active) choose(active.pick)
    } else if (event.key === 'Escape' && showList) {
      event.preventDefault()
      setOpen(false)
      setActiveId(null)
    }
  }

  const option = ({ id, pick }) => {
    const { drug } = pick
    const official = pick.kind === 'official'
    return (
      <li
        key={id}
        id={id}
        className={id === active?.id ? 'drug-picker__option drug-picker__option--active' : 'drug-picker__option'}
        role="option"
        aria-selected={id === active?.id}
        onMouseDown={(event) => event.preventDefault()}
        onMouseMove={() => id !== activeId && setActiveId(id)}
        onClick={() => choose(pick)}
      >
        <DrugThumb imageUrl={official ? drug.imageUrl : null} size="sm" />
        <span className="drug-picker__option-text">
          <span className="drug-picker__option-name">
            <Match text={official ? drug.name : drug.drugName} query={query} />
          </span>
          <span className="screen-caption drug-picker__muted">
            {official ? drug.maker : drug.hasSchedule ? '일정 있음' : '일정 없음'}
          </span>
        </span>
        {official && <span className="mt-badge mt-badge--info">추가하고 고르기</span>}
      </li>
    )
  }

  let officialState = null
  if (query.length < minLength) officialState = '2글자 이상 쓰면 공식 약 정보에서도 찾아요.'
  else if (!found) officialState = '공식 약 정보에서 찾고 있어요.'
  else if (found.failed) officialState = '공식 약 정보를 찾지 못했어요. 잠시 후 다시 써 주세요.'
  else if (official.length === 0) officialState = '공식 약 정보에서 맞는 약이 없어요.'

  const describedBy = [error && errorId, picked?.kind === 'official' && hintId].filter(Boolean).join(' ') || undefined

  return (
    <div className="drug-picker" onBlur={closeOnFocusOut(setOpen)}>
      <div className="mt-field">
        <label className="mt-field__label" htmlFor={inputId}>
          약 이름
        </label>
        <div className="drug-picker__control">
          <Icon name="search" className="drug-picker__icon" />
          <input
            className="mt-input drug-picker__input"
            id={inputId}
            type="text"
            role="combobox"
            autoComplete="off"
            placeholder="약 이름으로 찾기"
            value={text}
            aria-autocomplete="list"
            aria-expanded={showList}
            aria-controls={showList ? listId : undefined}
            aria-activedescendant={active?.id}
            aria-invalid={error ? true : undefined}
            aria-describedby={describedBy}
            onChange={type}
            onKeyDown={keyDown}
          />
        </div>
        {error && <FieldError id={errorId}>{error.message}</FieldError>}
        {picked?.kind === 'official' && (
          <p className="mt-field__help" id={hintId}>
            저장하면 이 약이 {profileName}의 약에 추가돼요.
          </p>
        )}
      </div>

      {showList && (
        <div className="drug-picker__list" id={listId} role="listbox" aria-label="약 이름 후보">
          {Array.isArray(choices) && (
            <ul className="drug-picker__group" role="group" aria-labelledby={`${listId}-mine`}>
              <li className="screen-label drug-picker__group-label" id={`${listId}-mine`} role="presentation">
                {profileName}의 약
              </li>
              {mine.length === 0 ? (
                <li className="screen-caption drug-picker__state" role="presentation">
                  {profileName}의 약에는 맞는 이름이 없어요.
                </li>
              ) : (
                options.filter((item) => item.pick.kind === 'mine').map(option)
              )}
            </ul>
          )}
          <ul className="drug-picker__group" role="group" aria-labelledby={`${listId}-official`}>
            <li className="screen-label drug-picker__group-label" id={`${listId}-official`} role="presentation">
              공식 약 정보에서 찾음 · 고르면 {profileName}의 약에 추가돼요
            </li>
            {officialState ? (
              <li className="screen-caption drug-picker__state" role="presentation">
                {officialState}
              </li>
            ) : (
              options.filter((item) => item.pick.kind === 'official').map(option)
            )}
          </ul>
        </div>
      )}

      {!showList && choicesFailed && (
        <Notice
          variant="negative"
          role="alert"
          action={
            <button className="mt-btn mt-btn--assistive mt-btn--md" type="button" onClick={onRetryChoices}>
              다시 시도
            </button>
          }
        >
          {profileName}의 약을 불러오지 못했어요. 잠시 후 다시 시도해 주세요.
        </Notice>
      )}
      {!showList && Array.isArray(choices) && choices.length === 0 && (
        <p className="mt-field__help">{profileName}의 약이 아직 없어요. 약 이름을 써서 공식 약 정보에서 찾아 주세요.</p>
      )}
      {!showList && Array.isArray(choices) && choices.length > 0 && (
        <div className="mt-field">
          <p className="mt-field__label" id={chipsLabelId}>
            {profileName}의 약에서 고르기
          </p>
          <div className="drug-picker__chips" role="group" aria-labelledby={chipsLabelId}>
            {choices.map((drug) => {
              const pressed = picked?.kind === 'mine' && picked.drug.myDrugId === drug.myDrugId
              return (
                <button key={drug.myDrugId} className="mt-chip drug-picker__chip" type="button" aria-pressed={pressed} onClick={() => choose({ kind: 'mine', drug })}>
                  <DrugName>{drug.drugName}</DrugName>
                  <span className="screen-caption drug-picker__chip-note">{drug.hasSchedule ? '일정 있음' : '일정 없음'}</span>
                </button>
              )
            })}
          </div>
        </div>
      )}
    </div>
  )
}

export default DrugPicker
