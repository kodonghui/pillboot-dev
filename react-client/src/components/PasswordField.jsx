import { useState } from 'react'
import Icon from './Icon.jsx'
import './PasswordField.css'

// 비밀번호 입력칸과 보기 버튼(목업 .pw). 버튼은 눌림 상태(aria-pressed)로 보기 · 숨기기를 알린다.
function PasswordField({ id, value, onChange, autoComplete, invalid, describedBy, ref }) {
  const [visible, setVisible] = useState(false)

  return (
    <div className="password-field">
      <input
        ref={ref}
        className="mt-input"
        id={id}
        type={visible ? 'text' : 'password'}
        value={value}
        onChange={onChange}
        autoComplete={autoComplete}
        aria-invalid={invalid || undefined}
        aria-describedby={describedBy}
      />
      <button
        className="password-field__toggle"
        type="button"
        aria-label="비밀번호 보기"
        aria-pressed={visible}
        onClick={() => setVisible((current) => !current)}
      >
        <Icon name={visible ? 'eyeOff' : 'eye'} />
      </button>
    </div>
  )
}

export default PasswordField
