import { useEffect, useId, useLayoutEffect, useRef } from 'react'
import { flushSync } from 'react-dom'
import { doseSlots, normalizeTime, stepTime } from '../scheduleInfo.js'
import FieldError from './FieldError.jsx'
import Icon from './Icon.jsx'
import ScheduleSection from './ScheduleSection.jsx'
import './DoseTimesField.css'

const holdDelay = 500
const repeatEvery = 100

// 시각 +/− 버튼(V3-SCHED-001, 44px). 누를 때마다 한 번 바꾸고, 0.5초 넘게 누르고 있으면 0.1초마다 되풀이한다.
// 손을 떼거나 포인터가 버튼 밖으로 나가면 멈춘다. 되풀이한 뒤 손을 뗄 때 오는 click 은 한 번 더 세지 않는다.
// 터치는 손을 뗄 때 pointerup → pointerleave → click 순서로 오므로, 그 표시는 나갈 때 지우지 않고 다음 click 이나 새 누름에서 지운다.
// 키보드 click(detail 0)은 늘 한 번 바꾼다.
function StepButton({ icon, label, onStep }) {
  const timers = useRef({ hold: null, repeat: null })
  const repeated = useRef(false)

  const stop = () => {
    clearTimeout(timers.current.hold)
    clearInterval(timers.current.repeat)
    timers.current.hold = null
    timers.current.repeat = null
  }

  useEffect(() => {
    const box = timers.current
    return () => {
      clearTimeout(box.hold)
      clearInterval(box.repeat)
    }
  }, [])

  return (
    <button
      className="mt-btn mt-btn--assistive mt-btn--sm dose-times__step"
      type="button"
      aria-label={label}
      onPointerDown={(event) => {
        if (event.button !== 0) return
        stop()
        repeated.current = false
        timers.current.hold = setTimeout(() => {
          repeated.current = true
          onStep()
          timers.current.repeat = setInterval(onStep, repeatEvery)
        }, holdDelay)
      }}
      onPointerUp={stop}
      onPointerLeave={stop}
      onPointerCancel={stop}
      onClick={(event) => {
        const afterRepeat = repeated.current && event.detail > 0
        repeated.current = false
        if (!afterRepeat) onStep()
      }}
    >
      <Icon name={icon} size="s" />
    </button>
  )
}

// 복용 시간(목업 SCH-03 · 06 "복용 시간"): 아침 · 점심 · 저녁 칸에 시각을 쓰고, 빼거나 다시 더한다(하루 1~3번).
// 더하면 빠진 칸이 아침 → 점심 → 저녁 순서로 기본 시각과 함께 돌아온다.
// 칸 옆 시 [−][+] · 분 [−][+] 로 1시간 · 5분씩 바꾸고, 830 처럼 숫자만 써도 칸을 벗어날 때 08:30 으로 맞춘다(v3-1-time-input A안).
// 칸을 벗어나면 그 칸에 checked 를 붙여, 모양이 틀린 동안 화면이 오류와 저장 잠금을 보이게 한다(typedTimesError).
// intro: 맨 위 안내(없으면 숨김). addHint: 'count'(지금 N번) · 'order'(더하면 들어갈 시각, 수정 화면).
// error: { message, slots(표시할 칸 번호) }. 칸이 하나도 없어 생긴 오류면 "시간 추가"에 data-invalid 를 붙여 포커스 대상으로 삼는다.
function DoseTimesField({ slots, intro, addHint, error, onChange }) {
  const titleId = useId()
  const baseId = useId()
  const helpId = useId()
  const errorId = useId()
  const addHintId = useId()
  const addRef = useRef(null)
  // 길게 눌러 되풀이할 때 마지막으로 바꾼 값에서 이어 가도록 최신 칸 값을 둔다.
  const latest = useRef(slots)
  const count = slots.filter((slot) => slot.on).length
  const full = count === doseSlots.length
  const noTimes = Boolean(error) && count === 0

  useLayoutEffect(() => {
    latest.current = slots
  })

  const setSlot = (index, patch) => onChange(slots.map((slot, at) => (at === index ? { ...slot, ...patch } : slot)))

  // 바뀐 시각은 칸 값으로만 보인다(따로 읽어 주지 않음).
  const step = (index, unit, direction) => {
    const next = latest.current.map((slot, at) =>
      at === index ? { ...slot, time: stepTime(slot.time, unit, direction, doseSlots[index].time) } : slot,
    )
    latest.current = next
    onChange(next)
  }

  // 뺀 칸에 있던 포커스는 "시간 추가"로, 더한 칸은 그 시각 칸으로 옮긴다.
  const remove = (index) => {
    flushSync(() => setSlot(index, { on: false }))
    addRef.current.focus()
  }

  const add = () => {
    const index = slots.findIndex((slot) => !slot.on)
    flushSync(() => setSlot(index, { on: true, time: doseSlots[index].time, checked: false }))
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
      <p className="mt-field__help dose-times__help" id={helpId}>
        830처럼 숫자만 적어도 08:30으로 바뀌어요. <span className="dose-times__nowrap">+/− 버튼은</span> 시는 1시간, 분은 5분씩 바꿔요.
      </p>
      {count > 0 && (
        <ul className="dose-times__list">
          {slots.map((slot, index) => {
            if (!slot.on) return null
            const name = doseSlots[index].name
            const invalid = error?.slots.includes(index)
            const inputId = `${baseId}-${index}`
            return (
              <li key={name} className="dose-times__row">
                <label className="mt-field__label dose-times__label" htmlFor={inputId}>
                  {name}
                </label>
                <input
                  className="mt-input dose-times__input"
                  id={inputId}
                  type="text"
                  inputMode="numeric"
                  autoComplete="off"
                  value={slot.time}
                  aria-invalid={invalid ? true : undefined}
                  aria-describedby={invalid ? `${errorId} ${helpId}` : helpId}
                  onChange={(event) => setSlot(index, { time: event.target.value })}
                  onBlur={() => {
                    const time = normalizeTime(slot.time)
                    if ((time && time !== slot.time) || !slot.checked) setSlot(index, { time: time ?? slot.time, checked: true })
                  }}
                />
                <div className="dose-times__steppers">
                  <div className="dose-times__stepper" role="group" aria-label={`${name} 시`}>
                    <span className="screen-caption dose-times__unit" aria-hidden="true">
                      시
                    </span>
                    <StepButton icon="minus" label={`${name} 1시간 빼기`} onStep={() => step(index, 'hour', -1)} />
                    <StepButton icon="plus" label={`${name} 1시간 더하기`} onStep={() => step(index, 'hour', 1)} />
                  </div>
                  <div className="dose-times__stepper" role="group" aria-label={`${name} 분`}>
                    <span className="screen-caption dose-times__unit" aria-hidden="true">
                      분
                    </span>
                    <StepButton icon="minus" label={`${name} 5분 빼기`} onStep={() => step(index, 'minute', -1)} />
                    <StepButton icon="plus" label={`${name} 5분 더하기`} onStep={() => step(index, 'minute', 1)} />
                  </div>
                </div>
                <button
                  className="mt-btn mt-btn--assistive mt-btn--sm"
                  type="button"
                  aria-label={`${name} 복용 시간 빼기`}
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
