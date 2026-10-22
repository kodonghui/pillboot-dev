import { useEffect, useId, useState } from 'react'
import { useLocation, useNavigate } from 'react-router'
import { getConsentPolicies } from '../api/auth.js'
import AuthCard from '../components/AuthCard.jsx'
import Notice from '../components/Notice.jsx'
import SignupSteps from '../components/SignupSteps.jsx'
import { paths } from '../paths.js'
import './SignupTermsPage.css'

// MEM-02 회원가입 약관 동의. 약관은 서버(GET /api/auth/consent-policies) 목록을 그대로 보여 준다.
function SignupTermsPage() {
  const location = useLocation()
  const navigate = useNavigate()
  const titleId = useId()
  const termId = useId()
  const [policies, setPolicies] = useState(null)
  const [failed, setFailed] = useState(false)
  const [attempt, setAttempt] = useState(0)
  // 정보 입력(MEM-03)의 "이전"으로 돌아오면 고른 약관을 그대로 둔다.
  const [agreed, setAgreed] = useState(() => new Set(location.state?.agreedTermsIds ?? []))
  const [openedId, setOpenedId] = useState(null)

  useEffect(() => {
    let active = true
    getConsentPolicies()
      .then((data) => active && setPolicies(data))
      .catch(() => active && setFailed(true))
    return () => {
      active = false
    }
  }, [attempt])

  const requiredDone = policies?.every((policy) => !policy.required || agreed.has(policy.termsId)) ?? false
  const allAgreed = policies?.every((policy) => agreed.has(policy.termsId)) ?? false

  const toggle = (termsId) =>
    setAgreed((current) => {
      const next = new Set(current)
      if (next.has(termsId)) next.delete(termsId)
      else next.add(termsId)
      return next
    })

  const retry = () => {
    setFailed(false)
    setAttempt((count) => count + 1)
  }

  const goNext = () => {
    const agreedTermsIds = policies.filter((policy) => agreed.has(policy.termsId)).map((policy) => policy.termsId)
    navigate(paths.signupInfo, { state: { agreedTermsIds } })
  }

  return (
    <AuthCard titleId={titleId}>
      <SignupSteps current={1} />
      <h1 className="screen-title" id={titleId}>
        약관에 동의해 주세요
      </h1>
      {failed && (
        <Notice
          variant="negative"
          role="alert"
          action={
            <button className="mt-btn mt-btn--assistive mt-btn--md" type="button" onClick={retry}>
              다시 시도
            </button>
          }
        >
          약관을 불러오지 못했어요.
        </Notice>
      )}
      {policies && (
        <>
          <div className="terms">
            <div className="terms__all">
              <label className="terms__check">
                <input
                  type="checkbox"
                  checked={allAgreed}
                  onChange={() => setAgreed(allAgreed ? new Set() : new Set(policies.map((policy) => policy.termsId)))}
                />
                전체 동의
              </label>
            </div>
            <ul>
              {policies.map((policy) => {
                const contentId = `${termId}-${policy.termsId}-content`
                const hintId = `${termId}-${policy.termsId}-hint`
                const opened = openedId === policy.termsId
                return (
                  <li className="terms__item" key={policy.termsId}>
                    <div className="terms__row">
                      <label className="terms__check">
                        <input
                          type="checkbox"
                          checked={agreed.has(policy.termsId)}
                          onChange={() => toggle(policy.termsId)}
                          aria-describedby={policy.required ? undefined : hintId}
                        />
                        <span className="terms__title">
                          <span className={policy.required ? 'mt-badge mt-badge--primary' : 'mt-badge'}>
                            {policy.required ? '필수' : '선택'}
                          </span>
                          {policy.termsTitle}
                        </span>
                      </label>
                      <button
                        className="mt-btn mt-btn--ghost mt-btn--md"
                        type="button"
                        aria-label={`${policy.termsTitle} 약관 보기`}
                        aria-expanded={opened}
                        aria-controls={opened ? contentId : undefined}
                        onClick={() => setOpenedId(opened ? null : policy.termsId)}
                      >
                        보기
                      </button>
                    </div>
                    {opened && (
                      <p className="screen-caption terms__content" id={contentId}>
                        {policy.termsContent}
                      </p>
                    )}
                    {!policy.required && (
                      <p className="screen-caption terms__hint" id={hintId}>
                        동의하지 않아도 가입할 수 있어요.
                      </p>
                    )}
                  </li>
                )
              })}
            </ul>
          </div>
          <div className="terms__next">
            {!requiredDone && (
              <Notice variant="caution" role="status" id={`${termId}-next-help`}>
                필수 항목에 모두 동의해야 다음으로 갈 수 있어요.
              </Notice>
            )}
            <button
              className="mt-btn mt-btn--solid mt-btn--block"
              type="button"
              disabled={!requiredDone}
              aria-describedby={requiredDone ? undefined : `${termId}-next-help`}
              onClick={goNext}
            >
              다음
            </button>
          </div>
        </>
      )}
    </AuthCard>
  )
}

export default SignupTermsPage
