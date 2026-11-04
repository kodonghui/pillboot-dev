import { useCallback, useEffect, useState } from 'react'
import { getMyDrugs } from '../api/drugs.js'

// 약 후보(품목 번호만 있음)에서 이 사람의 약을 찾는 표. '이미 등록한 약'의 일정으로 잇는 데 쓴다.
// 못 불러오면 빈 표다(그 화면은 링크 없이 지금 글만 보인다). refresh: 그사이 등록된 약을 다시 읽는다.
export function useMyDrugIndex(profileId) {
  const [attempt, setAttempt] = useState(0)
  const [index, setIndex] = useState(() => new Map())

  useEffect(() => {
    if (!profileId) return
    let active = true
    getMyDrugs(profileId)
      .then((drugs) => active && setIndex(new Map(drugs.map((drug) => [drug.itemSeq, drug]))))
      .catch(() => {})
    return () => {
      active = false
    }
  }, [profileId, attempt])

  const refresh = useCallback(() => setAttempt((count) => count + 1), [])
  return { index, refresh }
}
