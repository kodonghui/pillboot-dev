import { useId, useRef, useState } from 'react'
import Icon from './Icon.jsx'
import { closeOnFocusOut, useDismiss } from './useDismiss.js'
import './SelectMenu.css'

// 열린 목록이 스크롤 영역(페이지 · 패널 본문) 아래에 가려지지 않게, 보이는 곳까지만 스크롤한다.
function revealList(list) {
  list?.scrollIntoView({ block: 'nearest' })
}

// 목록에서 하나를 고르는 펼침 칸(목업 PRF-02 "상세 관계"의 .sel · .lbx). 디자인 시스템에 없어 목업 마크업을 토큰으로 옮겼다.
// options: [{ value, label }]. ref 를 넘기면 여는 버튼에 붙는다(오류 때 포커스).
function SelectMenu({ ref, id, value, options, placeholder, listLabel, invalid, describedBy, onChange }) {
  const [open, setOpen] = useState(false)
  const ownRef = useRef(null)
  const toggleRef = ref ?? ownRef
  const wrapperRef = useRef(null)
  const listId = useId()
  const selected = options.find((option) => option.value === value)

  useDismiss(open, setOpen, wrapperRef, toggleRef)

  const pick = (next) => {
    onChange(next)
    setOpen(false)
    toggleRef.current.focus()
  }

  return (
    <div className="select-menu" ref={wrapperRef} onBlur={closeOnFocusOut(setOpen)}>
      <button
        ref={toggleRef}
        className="mt-input select-menu__toggle"
        id={id}
        type="button"
        aria-expanded={open}
        aria-controls={open ? listId : undefined}
        aria-invalid={invalid ? true : undefined}
        aria-describedby={describedBy}
        onClick={() => setOpen(!open)}
      >
        {selected ? <span>{selected.label}</span> : <span className="select-menu__placeholder">{placeholder}</span>}
        <Icon name={open ? 'chevronUp' : 'chevronDown'} />
      </button>
      {open && (
        <ul ref={revealList} className="select-menu__list" id={listId} aria-label={listLabel}>
          {options.map((option) => (
            <li key={option.value}>
              <button
                className="select-menu__option"
                type="button"
                aria-pressed={option.value === value}
                onClick={() => pick(option.value)}
              >
                {option.label}
              </button>
            </li>
          ))}
        </ul>
      )}
    </div>
  )
}

export default SelectMenu
