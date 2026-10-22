import { useEffect, useId, useRef, useState } from 'react'
import { addDays, addMonths, daysBetween, monthOf, weekdayOf } from '../dates.js'
import { dayName, fullDate, monthDay, monthTitle } from '../text.js'
import Icon from './Icon.jsx'
import IconButton from './IconButton.jsx'
import { closeOnFocusOut, useDismiss } from './useDismiss.js'
import './DatePicker.css'

const weekdays = ['일', '월', '화', '수', '목', '금', '토']
const moves = { ArrowLeft: -1, ArrowRight: 1, ArrowUp: -7, ArrowDown: 7 }

function daysOf(month) {
  const first = `${month}-01`
  return Array.from({ length: daysBetween(first, `${addMonths(month, 1)}-01`) }, (_, index) => addDays(first, index))
}

// 날짜를 달력에서 고르는 칸(목업 SCH-03 종료일 .dp-field · .dpick). 디자인 시스템에 없어 목업 마크업을 토큰으로 옮겼다.
// 여는 버튼은 labelId(바깥 라벨)와 고른 날짜를 이름으로 읽고, 오류면 aria-invalid 가 붙는다.
// min: 고를 수 있는 첫날(없으면 제한 없음). start: 종료일 고르기에서 시작일 칸과 범례 · 안내를 보인다.
// 방향키로 하루 · 한 주씩 옮기고, Esc · 바깥 누르기 · 포커스가 밖으로 나가면 닫는다.
function DatePicker({ id, labelId, value, min, start, today, placeholder, pickerLabel, invalid, describedBy, onChange }) {
  const [open, setOpen] = useState(false)
  const [month, setMonth] = useState(null)
  const [focusRequest, setFocusRequest] = useState(null)
  const toggleRef = useRef(null)
  const wrapperRef = useRef(null)
  const gridRef = useRef(null)
  const valueId = useId()
  const popupId = useId()

  useDismiss(open, setOpen, wrapperRef, toggleRef)

  // 방향키 · 처음 열기로 옮긴 날짜에 포커스를 준다(달이 바뀌면 그린 뒤에).
  useEffect(() => {
    if (open && focusRequest) gridRef.current.querySelector(`[data-date="${focusRequest.date}"]`)?.focus()
  }, [open, focusRequest])

  const selectable = (date) => !min || date >= min
  const firstMonth = min ? monthOf(min) : null
  const visibleMonth = month ?? monthOf(value || min || today)
  const days = daysOf(visibleMonth)

  // 이 달에서 Tab 으로 들어가는 날: 방향키로 옮긴 날 → 고른 날 → 고를 수 있는 첫날
  const activeOf = (target) => {
    const inMonth = daysOf(target)
    if (focusRequest && monthOf(focusRequest.date) === target && selectable(focusRequest.date)) return focusRequest.date
    if (value && monthOf(value) === target && selectable(value)) return value
    return inMonth.find(selectable) ?? null
  }
  const active = activeOf(visibleMonth)

  const toggle = () => {
    if (open) {
      setOpen(false)
      return
    }
    const opening = monthOf(value || min || today)
    setMonth(opening)
    setOpen(true)
    const target = value && selectable(value) ? value : (daysOf(opening).find(selectable) ?? null)
    setFocusRequest(target ? { date: target } : null)
  }

  const showMonth = (next) => {
    setMonth(next)
    // 첫 달에 오면 "이전 달"이 잠기므로 포커스를 그 달의 날짜로 옮긴다.
    if (firstMonth && next <= firstMonth) {
      const target = daysOf(next).find(selectable)
      if (target) setFocusRequest({ date: target })
    }
  }

  const moveFocus = (event) => {
    const step = moves[event.key]
    const from = event.target.dataset.date
    if (!step || !from) return
    event.preventDefault()
    const next = addDays(from, step)
    if (!selectable(next)) return
    setMonth(monthOf(next))
    setFocusRequest({ date: next })
  }

  const pick = (date) => {
    onChange(date)
    setOpen(false)
    toggleRef.current.focus()
  }

  return (
    <div className="date-picker" ref={wrapperRef} onBlur={closeOnFocusOut(setOpen)}>
      <button
        ref={toggleRef}
        className="mt-input date-picker__toggle"
        id={id}
        type="button"
        aria-labelledby={`${labelId} ${valueId}`}
        aria-haspopup="dialog"
        aria-expanded={open}
        aria-controls={open ? popupId : undefined}
        aria-invalid={invalid ? true : undefined}
        aria-describedby={describedBy}
        onClick={toggle}
      >
        <Icon name="calendar" className="date-picker__icon" />
        <span id={valueId} className={value ? 'date-picker__value' : 'date-picker__placeholder'}>
          {value ? fullDate(value) : placeholder}
        </span>
      </button>

      {open && (
        <div className="date-picker__popup" id={popupId} role="dialog" aria-label={pickerLabel}>
          <div className="date-picker__head">
            <IconButton
              icon="chevronLeft"
              label="이전 달"
              disabled={Boolean(firstMonth) && visibleMonth <= firstMonth}
              onClick={() => showMonth(addMonths(visibleMonth, -1))}
            />
            <p className="screen-item date-picker__month" aria-live="polite">
              {monthTitle(visibleMonth)}
            </p>
            <IconButton icon="chevronRight" label="다음 달" onClick={() => showMonth(addMonths(visibleMonth, 1))} />
          </div>

          <div className="date-picker__grid" aria-hidden="true">
            {weekdays.map((name, index) => (
              <span key={name} className={index === 0 ? 'screen-label date-picker__dow date-picker__dow--sunday' : 'screen-label date-picker__dow'}>
                {name}
              </span>
            ))}
          </div>

          <div className="date-picker__grid" ref={gridRef} onKeyDown={moveFocus}>
            {Array.from({ length: weekdayOf(days[0]) }, (_, index) => (
              <span key={`empty-${index}`} className="date-picker__empty" aria-hidden="true" />
            ))}
            {days.map((date) => {
              const disabled = !selectable(date)
              const isStart = date === start
              let label = dayName(date)
              if (isStart) label += ', 시작일이라 고를 수 없음'
              else if (disabled) label += ', 고를 수 없음'
              if (date === today) label += ' (오늘)'
              const classes = [
                'date-picker__day',
                isStart && 'date-picker__day--start',
                date === value && 'date-picker__day--selected',
              ]
              return (
                <button
                  key={date}
                  className={classes.filter(Boolean).join(' ')}
                  type="button"
                  data-date={date}
                  tabIndex={date === active ? 0 : -1}
                  disabled={disabled}
                  aria-label={label}
                  aria-pressed={date === value}
                  onClick={() => pick(date)}
                >
                  {Number(date.slice(8))}
                </button>
              )
            })}
          </div>

          {start && (
            <>
              <div className="screen-caption date-picker__legend">
                <span>
                  <span className="date-picker__swatch date-picker__swatch--start" aria-hidden="true" />
                  시작일 {monthDay(start)}
                </span>
                <span>
                  <span className="date-picker__swatch" aria-hidden="true" />
                  고를 수 있는 날
                </span>
              </div>
              <p className="screen-caption date-picker__hint">
                시작일과 같은 날이나 그 전 날짜는 고를 수 없어요. {Number(min.slice(8))}일부터 골라 주세요.
              </p>
            </>
          )}
        </div>
      )}
    </div>
  )
}

export default DatePicker
