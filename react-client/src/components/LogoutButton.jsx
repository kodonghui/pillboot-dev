import { useState } from 'react'
import ConfirmDialog from './ConfirmDialog.jsx'
import Icon from './Icon.jsx'
import { useAuthStore } from '../store/auth.js'

// 헤더 "로그아웃"과 확인창(CMN-01 로그아웃 확인). 서버는 부르지 않는다(docs/auth-logout.md).
// 저장된 로그인 정보를 지우면 로그인 후 레이아웃이 로그인 화면으로 보낸다.
function LogoutButton() {
  const [confirming, setConfirming] = useState(false)
  const signOut = useAuthStore((state) => state.signOut)

  return (
    <>
      <button
        className="mt-btn mt-btn--assistive mt-btn--md"
        type="button"
        aria-haspopup="dialog"
        aria-expanded={confirming}
        onClick={() => setConfirming(true)}
      >
        <Icon name="logout" size="s" />
        로그아웃
      </button>
      {confirming && (
        <ConfirmDialog
          title="로그아웃할까요?"
          description="로그아웃하면 로그인 화면으로 돌아가요. 내 약 · 복용 달력 · 약봉이는 다시 로그인하면 볼 수 있어요."
          confirmLabel="로그아웃"
          confirmIcon="logout"
          onConfirm={signOut}
          onClose={() => setConfirming(false)}
        />
      )}
    </>
  )
}

export default LogoutButton
