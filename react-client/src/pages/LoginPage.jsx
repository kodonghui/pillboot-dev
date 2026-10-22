import { useId, useRef, useState } from 'react'
import { Link, useLocation, useNavigate } from 'react-router'
import { loginWithProfiles } from '../api/auth.js'
import { errorOf } from '../api/client.js'
import AuthCard from '../components/AuthCard.jsx'
import FieldError from '../components/FieldError.jsx'
import Notice from '../components/Notice.jsx'
import PasswordField from '../components/PasswordField.jsx'
import { paths } from '../paths.js'
import { useAuthStore } from '../store/auth.js'

// MEM-04 로그인. 로그인하면 프로필이 없으면 프로필 만들기, 있으면 홈으로 간다(docs/auth-logout.md).
// 가입 직후 자동 로그인에 실패해 들어오면 가입 완료 안내와 이메일을 받아 보여 준다.
function LoginPage() {
  const { state } = useLocation()
  const navigate = useNavigate()
  const signIn = useAuthStore((store) => store.signIn)
  const titleId = useId()
  const fieldId = useId()
  const emailRef = useRef(null)
  const passwordRef = useRef(null)
  const [email, setEmail] = useState(state?.email ?? '')
  const [password, setPassword] = useState('')
  const [errors, setErrors] = useState({})
  const [failure, setFailure] = useState(null)
  const [submitting, setSubmitting] = useState(false)

  const submit = async (event) => {
    event.preventDefault()
    const request = { email: email.trim(), password }
    const nextErrors = {
      email: request.email ? null : '이메일을 입력해 주세요.',
      password: request.password ? null : '비밀번호를 입력해 주세요.',
    }
    setErrors(nextErrors)
    if (nextErrors.email) {
      emailRef.current.focus()
      return
    }
    if (nextErrors.password) {
      passwordRef.current.focus()
      return
    }

    setSubmitting(true)
    setFailure(null)
    try {
      const session = await loginWithProfiles(request)
      signIn(session)
      navigate(session.profiles.length > 0 ? paths.home : paths.profileNew, { replace: true })
    } catch (error) {
      const wrong = errorOf(error).code === 'LOGIN_FAILED'
      setFailure(wrong ? '이메일 또는 비밀번호가 맞지 않습니다.' : '로그인하지 못했어요. 잠시 후 다시 시도해 주세요.')
      if (wrong) {
        setPassword('')
        passwordRef.current.focus()
      }
      setSubmitting(false)
    }
  }

  return (
    <AuthCard titleId={titleId}>
      <h1 className="screen-title" id={titleId}>
        로그인
      </h1>
      {failure ? (
        <Notice variant="negative" role="alert">
          {failure}
        </Notice>
      ) : (
        state?.signupDone && (
          <Notice variant="positive" role="status">
            가입이 완료됐어요. 로그인해 주세요.
          </Notice>
        )
      )}
      <form className="auth-card__form" noValidate onSubmit={submit}>
        <div className="auth-card__fields">
          <div className="mt-field">
            <label className="mt-field__label" htmlFor={`${fieldId}-email`}>
              이메일
            </label>
            <input
              ref={emailRef}
              className="mt-input"
              id={`${fieldId}-email`}
              type="email"
              value={email}
              onChange={(event) => setEmail(event.target.value)}
              autoComplete="email"
              aria-invalid={errors.email ? true : undefined}
              aria-describedby={errors.email ? `${fieldId}-email-error` : undefined}
            />
            {errors.email && <FieldError id={`${fieldId}-email-error`}>{errors.email}</FieldError>}
          </div>
          <div className="mt-field">
            <label className="mt-field__label" htmlFor={`${fieldId}-password`}>
              비밀번호
            </label>
            <PasswordField
              ref={passwordRef}
              id={`${fieldId}-password`}
              value={password}
              onChange={(event) => setPassword(event.target.value)}
              autoComplete="current-password"
              invalid={Boolean(errors.password)}
              describedBy={errors.password ? `${fieldId}-password-error` : undefined}
            />
            {errors.password && <FieldError id={`${fieldId}-password-error`}>{errors.password}</FieldError>}
          </div>
        </div>
        <button className="mt-btn mt-btn--solid mt-btn--block" type="submit" disabled={submitting}>
          로그인
        </button>
      </form>
      <div className="auth-card__footer">
        <span>아직 계정이 없나요?</span>
        <Link className="mt-btn mt-btn--ghost mt-btn--md" to={paths.signup}>
          회원가입
        </Link>
      </div>
    </AuthCard>
  )
}

export default LoginPage
