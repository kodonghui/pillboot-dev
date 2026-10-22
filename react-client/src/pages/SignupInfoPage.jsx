import { useId, useRef, useState } from 'react'
import { Link, Navigate, useLocation, useNavigate } from 'react-router'
import { checkEmailAvailability, loginWithProfiles, signup } from '../api/auth.js'
import { errorOf } from '../api/client.js'
import AuthCard from '../components/AuthCard.jsx'
import FieldError from '../components/FieldError.jsx'
import Notice from '../components/Notice.jsx'
import PasswordField from '../components/PasswordField.jsx'
import SignupSteps from '../components/SignupSteps.jsx'
import { paths } from '../paths.js'
import { useAuthStore } from '../store/auth.js'

// 서버(SignupRequest)와 같은 조건만 검사한다.
const checks = {
  email: (value) => {
    if (!value) return '이메일을 입력해 주세요.'
    if (value.length > 100) return '이메일은 100자 이하로 입력해 주세요.'
    return /^[^\s@]+@[^\s@]+$/.test(value) ? null : '이메일 형식이 올바르지 않아요.'
  },
  password: (value) => (value.length >= 8 && value.length <= 20 ? null : '비밀번호는 8–20자로 입력해 주세요.'),
  nickname: (value) => (value.length >= 2 && value.length <= 10 ? null : '닉네임은 2–10자로 입력해 주세요.'),
}

// MEM-03 회원가입 정보 입력. 가입하면 같은 이메일 · 비밀번호로 한 번 로그인한다(docs/auth-logout.md).
// 비밀번호는 이 화면 안에서만 쓰고 저장하거나 다른 화면에 넘기지 않는다.
function SignupInfoPage() {
  const { state } = useLocation()
  const navigate = useNavigate()
  const signIn = useAuthStore((store) => store.signIn)
  const titleId = useId()
  const fieldId = useId()
  const emailRef = useRef(null)
  const passwordRef = useRef(null)
  const nicknameRef = useRef(null)
  const [values, setValues] = useState({ email: '', password: '', nickname: '' })
  const [errors, setErrors] = useState({})
  const [takenEmail, setTakenEmail] = useState(null)
  const [failure, setFailure] = useState(null)
  const [submitting, setSubmitting] = useState(false)

  // 약관 동의(MEM-02)를 거치지 않고 들어오면 약관 동의로 돌려보낸다.
  const agreedTermsIds = state?.agreedTermsIds
  if (!agreedTermsIds) return <Navigate to={paths.signup} replace />

  const email = values.email.trim()
  const emailTaken = takenEmail === email

  const change = (key) => (event) => {
    setValues((current) => ({ ...current, [key]: event.target.value }))
    setErrors((current) => ({ ...current, [key]: null }))
  }

  const checkEmail = async () => {
    if (checks.email(email)) return
    try {
      if (!(await checkEmailAvailability(email))) setTakenEmail(email)
    } catch {
      // 확인하지 못해도 가입할 때 서버가 한 번 더 확인한다.
    }
  }

  const submit = async (event) => {
    event.preventDefault()
    const request = { email, password: values.password, nickname: values.nickname.trim() }
    const nextErrors = {
      email: checks.email(request.email),
      password: checks.password(request.password),
      nickname: checks.nickname(request.nickname),
    }
    setErrors(nextErrors)
    setFailure(null)
    if (nextErrors.email || emailTaken) {
      emailRef.current.focus()
      return
    }
    if (nextErrors.password) {
      passwordRef.current.focus()
      return
    }
    if (nextErrors.nickname) {
      nicknameRef.current.focus()
      return
    }

    setSubmitting(true)
    try {
      await signup({ ...request, agreedTermsIds })
    } catch (error) {
      const { status, message } = errorOf(error)
      setSubmitting(false)
      if (status === 409) {
        setTakenEmail(request.email)
        emailRef.current.focus()
      } else {
        setFailure(message ?? '가입하지 못했어요. 잠시 후 다시 시도해 주세요.')
      }
      return
    }
    // 가입(201) 뒤 자동 로그인에 실패하면 가입 완료 안내와 함께 로그인 화면으로 간다.
    try {
      const session = await loginWithProfiles({ email: request.email, password: request.password })
      signIn(session)
      navigate(session.profiles.length > 0 ? paths.home : paths.profileNew, { replace: true })
    } catch {
      navigate(paths.login, { replace: true, state: { signupDone: true, email: request.email } })
    }
  }

  return (
    <AuthCard titleId={titleId}>
      <SignupSteps current={2} />
      <h1 className="screen-title" id={titleId}>
        가입 정보를 입력해 주세요
      </h1>
      {failure && (
        <Notice variant="negative" role="alert">
          {failure}
        </Notice>
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
              value={values.email}
              onChange={change('email')}
              onBlur={checkEmail}
              autoComplete="email"
              aria-invalid={errors.email || emailTaken ? true : undefined}
              aria-describedby={errors.email || emailTaken ? `${fieldId}-email-error` : undefined}
            />
            {errors.email && <FieldError id={`${fieldId}-email-error`}>{errors.email}</FieldError>}
            {!errors.email && emailTaken && (
              <FieldError id={`${fieldId}-email-error`}>
                이미 가입한 이메일입니다.{' '}
                <Link className="auth-card__link" to={paths.login}>
                  로그인하기
                </Link>
              </FieldError>
            )}
          </div>
          <div className="mt-field">
            <label className="mt-field__label" htmlFor={`${fieldId}-password`}>
              비밀번호
            </label>
            <PasswordField
              ref={passwordRef}
              id={`${fieldId}-password`}
              value={values.password}
              onChange={change('password')}
              autoComplete="new-password"
              invalid={Boolean(errors.password)}
              describedBy={`${fieldId}-password-help`}
            />
            {errors.password ? (
              <FieldError id={`${fieldId}-password-help`}>{errors.password}</FieldError>
            ) : (
              <p className="mt-field__help" id={`${fieldId}-password-help`}>
                8–20자로 입력해 주세요.
              </p>
            )}
          </div>
          <div className="mt-field">
            <label className="mt-field__label" htmlFor={`${fieldId}-nickname`}>
              닉네임
            </label>
            <input
              ref={nicknameRef}
              className="mt-input"
              id={`${fieldId}-nickname`}
              type="text"
              value={values.nickname}
              onChange={change('nickname')}
              autoComplete="nickname"
              aria-invalid={errors.nickname ? true : undefined}
              aria-describedby={errors.nickname ? `${fieldId}-nickname-error` : undefined}
            />
            {errors.nickname && <FieldError id={`${fieldId}-nickname-error`}>{errors.nickname}</FieldError>}
          </div>
        </div>
        <div className="auth-card__actions">
          <button
            className="mt-btn mt-btn--assistive auth-card__back"
            type="button"
            onClick={() => navigate(paths.signup, { state: { agreedTermsIds } })}
          >
            이전
          </button>
          <button className="mt-btn mt-btn--solid auth-card__primary" type="submit" disabled={submitting}>
            가입하기
          </button>
        </div>
      </form>
    </AuthCard>
  )
}

export default SignupInfoPage
