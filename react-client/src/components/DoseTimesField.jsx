import { useId, useRef } from 'react'
import { flushSync } from 'react-dom'
import { doseSlots, normalizeTime } from '../scheduleInfo.js'
import FieldError from './FieldError.jsx'
import Icon from './Icon.jsx'
import ScheduleSection from './ScheduleSection.jsx'
import './DoseTimesField.css'

// 복용 시간(목업 SCH-03 · 06 "복용 시간"): 아침 · 점심 · 저녁 칸에 시각을 쓰고, 빼거나 다시 더한다(하루 1~3번).
// 더하면 빠진 칸이 아침 → 점심 → 저녁 순서로 기본 시각과 함께 돌아온다.
// intro: 맨 위 안내(없으면 숨김). addHint: 'count'(지금 N번) · 'order'(더하면 들어갈 시각, 수정 화면).
// error: { message, slots(표시할 칸 번호) }. 칸이 하나도 없어 생긴 오류면 "시간 추가"에 data-invalid 를 붙여 포커스 대상으로 삼는다.
function DoseTimesField({ slots, intro, addHint, error, onChange }) {
  const titleId = useId()
  const baseId = useId()
  const errorId = useId()
  const addHintId = useId()
  const addRef = useRef(null)
  const count = slots.filter((slot) => slot.on).length
  const full = count === doseSlots.length
  const noTimes = Boolean(error) && count === 0

  const setSlot = (index, patch) => onChange(slots.map((slot, at) => (at === index ? { ...slot, ...patch } : slot)))

  // 뺀 칸에 있던 포커스는 "시간 추가"로, 더한 칸은 그 시각 칸으로 옮긴다.
  const remove = (index) => {
    flushSync(() => setSlot(index, { on: false }))
    addRef.current.focus()
  }

  const add = () => {
    const index = slots.findIndex((slot) => !slot.on)
    flushSync(() => setSlot(index, { on: true, time: doseSlots[index].time }))
    document.getElementById(`${baseId}-${index}`).focus()
  }

  let hint = '하루 3번까지 정할 수 있어요.'
  if (!full && addHint === 'count') hint += ` 지금 ${count}번이에요.`
  if (!full && addHint === 'order') {
    const next = doseSlots.filter((_, index) => !slots[index].on).map((slot) => `${slot.name} ${slot.time}`)
    hint = `더하면 ${next.join(', ')}이 차례로 들어가요.`
  }

  return (
    <ScheduleSection titleId={titleId} title="복용 시간" mark="하루 1–3번">
      {intro && <p className="mt-field__help">{intro}</p>}
      {count > 0 && (
        <ul className="dose-times__list">
          {slots.map((slot, index) => {
            if (!slot.on) return null
            const invalid = error?.slots.includes(index)
            const inputId = `${baseId}-${index}`
            return (
              <li key={doseSlots[index].name} className="dose-times__row">
                <label className="mt-field__label dose-times__label" htmlFor={inputId}>
                  {doseSlots[index].name}
                </label>
                <input
                  className="mt-input dose-times__input"
                  id={inputId}
                  type="text"
                  inputMode="numeric"
                  autoComplete="off"
                  value={slot.time}
                  aria-invalid={invalid ? true : undefined}
                  aria-describedby={invalid ? errorId : undefined}
                  onChange={(event) => setSlot(index, { time: event.target.value })}
                  onBlur={() => {
                    const time = normalizeTime(slot.time)
                    if (time && time !== slot.time) setSlot(index, { time })
                  }}
                />
                <button
                  className="mt-btn mt-btn--assistive mt-btn--sm"
                  type="button"
                  aria-label={`${doseSlots[index].name} 시간 빼기`}
                  onClick={() => remove(index)}
                >
                  빼기
                </button>
              </li>
            )
          })}
        </ul>
      )}
      {error && <FieldError id={errorId}>{error.message}</FieldError>}
      <div className="dose-times__add">
        <button
          ref={addRef}
          className="mt-btn mt-btn--assistive mt-btn--sm"
          type="button"
          disabled={full}
          data-invalid={noTimes || undefined}
          aria-describedby={noTimes ? `${errorId} ${addHintId}` : addHintId}
          onClick={add}
        >
          <Icon name="plus" size="s" />
          시간 추가
        </button>
        <p className="mt-field__help" id={addHintId}>
          {hint}
        </p>
      </div>
    </ScheduleSection>
  )
}

export default DoseTimesField
