import Icon from './Icon.jsx'
import './SignupSteps.css'

const steps = ['약관 동의', '정보 입력']

// 회원가입 단계 표시(MEM-02 · MEM-03). current: 1 · 2
function SignupSteps({ current }) {
  return (
    <ol className="signup-steps" aria-label="회원가입 단계">
      {steps.map((label, index) => {
        const step = index + 1
        const done = step < current
        return (
          <li key={label} className={done ? 'signup-steps__done' : undefined} aria-current={step === current ? 'step' : undefined}>
            <span className="screen-label signup-steps__number">{done ? <Icon name="check" size="s" /> : step}</span>
            {label}
            {done && <span className="signup-steps__hidden">완료</span>}
          </li>
        )
      })}
    </ol>
  )
}

export default SignupSteps
