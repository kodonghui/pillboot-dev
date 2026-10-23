import './AuthCard.css'

// 회원가입 · 로그인 · 로그인 만료 화면의 가운데 카드(목업 .auth · .auth-card). 제목 h1 의 id 를 titleId 로 넘긴다.
function AuthCard({ titleId, className, children }) {
  return (
    <div className="auth-page">
      <section className={['mt-card mt-card--wide auth-card', className].filter(Boolean).join(' ')} aria-labelledby={titleId}>
        {children}
      </section>
    </div>
  )
}

export default AuthCard
