import { useId } from 'react'
import { doseMax, doseMin } from '../scheduleForm.js'
import FieldError from './FieldError.jsx'
import IconButton from './IconButton.jsx'
import ScheduleSection from './ScheduleSection.jsx'
import './DoseCountField.css'

// 1회 개수(목업 SCH-03 · 06 "1회 개수"): 줄이기 · 늘리기 버튼과 숫자 칸(1~10). 1 이하 · 10 이상이면 그쪽 버튼을 잠근다.
// 서버에 약 단위가 없어 목업의 "정" · "캡슐" 대신 "개"를 쓴다. hint: 칸 아래 안내.
function DoseCountField({ value, hint, error, onChange }) {
  const titleId = useId()
  const hintId = useId()
  const errorId = useId()
  const numeric = /^\d+$/.test(value.trim())
  const count = numeric ? Number(value) : null

  return (
    <ScheduleSection titleId={titleId} title="1회 개수">
      <div className="dose-count">
        <IconButton
          icon="minus"
          label="1개 줄이기"
          disabled={count === null || count <= doseMin}
          onClick={() => onChange(String(Math.min(count, doseMax + 1) - 1))}
        />
        <input
          className="mt-input dose-count__input"
          type="text"
          inputMode="numeric"
          autoComplete="off"
          value={value}
          aria-labelledby={titleId}
          aria-invalid={error ? true : undefined}
          aria-describedby={error ? `${errorId} ${hintId}` : hintId}
          onChange={(event) => onChange(event.target.value)}
        />
        <IconButton
          icon="plus"
          label="1개 늘리기"
          disabled={count !== null && count >= doseMax}
          onClick={() => onChange(String(count === null ? doseMin : Math.max(count + 1, doseMin)))}
        />
        <span className="screen-body dose-count__unit">개</span>
      </div>
      {error && <FieldError id={errorId}>{error.message}</FieldError>}
      <p className="mt-field__help" id={hintId}>
        {hint}
      </p>
    </ScheduleSection>
  )
}

export default DoseCountField
