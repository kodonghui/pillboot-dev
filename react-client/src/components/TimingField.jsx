import { useId } from 'react'
import { timingLimit, timingWords } from '../scheduleForm.js'
import ScheduleSection from './ScheduleSection.jsx'
import './TimingField.css'

// 복용 때(목업 SCH-03 · 06 "복용 때", 선택): 20자까지 쓰는 칸과 자주 쓰는 말 칩. 칩을 누르면 그 말로 바꾼다.
// hint: 칸 아래 안내. 20자를 다 쓰면 더 써지지 않는다는 말을 덧붙인다.
function TimingField({ value, hint, onChange }) {
  const titleId = useId()
  const hintId = useId()
  const full = value.length >= timingLimit

  return (
    <ScheduleSection titleId={titleId} title="복용 때" mark="선택">
      <div className="mt-field">
        <input
          className="mt-input"
          type="text"
          maxLength={timingLimit}
          placeholder="예: 식후 30분"
          autoComplete="off"
          value={value}
          aria-labelledby={titleId}
          aria-describedby={hintId}
          onChange={(event) => onChange(event.target.value)}
        />
        <div className="timing-field__meta">
          <p className="mt-field__help" id={hintId}>
            {hint}
            {full && ' 20자가 되면 더 써지지 않아요.'}
          </p>
          <span className="mt-field__help timing-field__count">
            {value.length}/{timingLimit}
          </span>
        </div>
      </div>
      <div className="timing-field__words" role="group" aria-label="자주 쓰는 말">
        {timingWords.map((word) => (
          <button key={word} className="mt-chip mt-chip--sm" type="button" aria-pressed={value === word} onClick={() => onChange(word)}>
            {word}
          </button>
        ))}
      </div>
    </ScheduleSection>
  )
}

export default TimingField
