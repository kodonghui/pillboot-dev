import { useEffect, useState } from 'react'
import { getProfiles } from '../api/profiles.js'
import { profileColor } from '../profileInfo.js'
import { useAuthStore } from '../store/auth.js'

// 관리할 사람(프로필)과 그 색. 프로필 목록은 이 훅을 쓰는 화면이 받아서 갖는다(전역 스토어에 넣지 않음).
// 저장된 관리할 사람이 목록에 없으면 docs/auth-logout.md 규칙(본인 → 맨 위)으로 다시 정한다.
// status: 'loading' · 'ready' · 'none'(프로필이 하나도 없음) · 'failed'
export function useManagedProfile() {
  const profileId = useAuthStore((state) => state.profileId)
  const syncProfileId = useAuthStore((state) => state.syncProfileId)
  const [profiles, setProfiles] = useState(null)
  const [failed, setFailed] = useState(false)
  const [attempt, setAttempt] = useState(0)

  useEffect(() => {
    let active = true
    getProfiles()
      .then((data) => {
        if (!active) return
        setProfiles(data)
        syncProfileId(data)
      })
      .catch(() => active && setFailed(true))
    return () => {
      active = false
    }
  }, [attempt, syncProfileId])

  const retry = () => {
    setFailed(false)
    setAttempt((count) => count + 1)
  }

  const profile = profiles?.find((item) => item.profileId === profileId) ?? null
  let status = 'loading'
  if (failed) status = 'failed'
  else if (profiles) status = profiles.length === 0 ? 'none' : 'ready'

  return { status, profiles, profile, color: profile ? profileColor(profiles, profile.profileId) : null, retry }
}
