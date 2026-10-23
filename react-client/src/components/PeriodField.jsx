import { useId } from 'react'
import { addDays, daysBetween } from '../dates.js'
import DatePicker from './DatePicker.jsx'
import FieldError from './FieldError.jsx'
import ScheduleSection from './ScheduleSection.jsx'
import './PeriodField.css'

// 일정 기간(목업 SCH-03 · 06 "기간"). 종료일은 시작일 다음 날부터 고르고, 기간 칩은 시작일부터 N일(시작일 포함)이다.
// 시작일을 종료일과 같거나 뒤로 옮기면 종료일을 비운다. startHint: 시작일 아래 안내(수정은 없음).
// quickDays · quickLabel: 기간 칩. error: 종료일 오류. children: 묶음 맨 아래 안내.
function PeriodField({ startDate, endDate, today, startHint, quickDays, quickLabel, error, onChange, children }) {
  const titleId = useId()
  const startId = useId()
  const startLabelId = useId()
  const startHintId = useId()
  const endId = useId()
  const endLabelId = useId()
  const endHintId = useId()
  const errorId = useId()
  const endOf = (days) => addDays(startDate, days - 1)

  const changeStart = (date) => onChange({ startDate: date, endDate: endDate && endDate <= date ? '' : endDate })

  return (
    <ScheduleSection titleId={titleId} title="기간">
      <div className="period-field__dates">
        <div className="mt-field">
          <label className="mt-field__label" id={startLabelId} htmlFor={startId}>
            시작일
          </label>
          <DatePicker
            id={startId}
            labelId={startLabelId}
            value={startDate}
            today={today}
            pickerLabel="시작일 고르기"
            describedBy={startHint ? startHintId : undefined}
            onChange={changeStart}
          />
          {startHint && (
            <p className="mt-field__help" id={startHintId}>
              {startHint}
            </p>
          )}
        </div>
        <div className="mt-field">
          <label className="mt-field__label" id={endLabelId} htmlFor={endId}>
            종료일<span className="mt-field__req">(필수)</span>
          </label>
          <DatePicker
            id={endId}
            labelId={endLabelId}
            value={endDate}
            min={addDays(startDate, 1)}
            start={startDate}
            today={today}
            placeholder="날짜 고르기"
            pickerLabel="종료일 고르기"
            invalid={Boolean(error)}
            describedBy={error ? `${errorId} ${endHintId}` : endHintId}
            onChange={(date) => onChange({ endDate: date })}
          />
          {error && <FieldError id={errorId}>{error.message}</FieldError>}
          <p className="mt-field__help" id={endHintId}>
            시작일 다음 날부터 고를 수 있어요.
          </p>
        </div>
      </div>

      <div className="period-field__quick" role="group" aria-label={quickLabel}>
        {quickDays.map((days) => (
          <button
            key={days}
            className="mt-chip mt-chip--sm"
            type="button"
            aria-pressed={endDate === endOf(days)}
            onClick={() => onChange({ endDate: endOf(days) })}
          >
            {days}일
          </button>
        ))}
      </div>
      <p className="mt-field__help">
        시작일과 종료일을 모두 포함해요.
        {endDate && (
          <>
            {' '}
            고른 기간 <b className="period-field__days">{daysBetween(startDate, endDate) + 1}일</b>
          </>
        )}
      </p>
      {children}
    </ScheduleSection>
  )
}

export default PeriodField
