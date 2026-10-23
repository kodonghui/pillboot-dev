import Icon from './Icon.jsx'
import './ChipGroup.css'

// 하나만 고르는 선택 버튼 묶음(디자인 시스템 Chip, 목업 .seg). 묶음은 fieldset · legend 로 읽힌다.
// options: [{ value, label, disabled, describedBy }]. firstRef 는 고를 수 있는 첫 버튼에 붙는다(오류 때 포커스).
// children 에는 묶음 아래 도움말 · 오류 줄을 넣는다.
function ChipGroup({ legend, mark, options, value, onChange, describedBy, firstRef, children }) {
  const firstEnabled = options.findIndex((option) => !option.disabled)

  return (
    <fieldset className="chip-group" aria-describedby={describedBy}>
      <legend className="mt-field__label chip-group__legend">
        {legend}
        {mark && <span className="mt-field__req">{mark}</span>}
      </legend>
      <div className="chip-group__options">
        {options.map((option, index) => {
          const pressed = option.value === value
          return (
            <button
              key={String(option.value)}
              ref={index === firstEnabled ? firstRef : undefined}
              className="mt-chip"
              type="button"
              aria-pressed={pressed}
              aria-describedby={option.describedBy}
              disabled={option.disabled}
              onClick={() => onChange(option.value)}
            >
              {pressed && <Icon name="check" size="s" />}
              {option.label}
            </button>
          )
        })}
      </div>
      {children}
    </fieldset>
  )
}

export default ChipGroup
