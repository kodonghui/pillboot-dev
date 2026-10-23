import { useEffect, useState } from 'react'
import { getDrug, getDrugGuide } from '../api/drugs.js'

// 약 공식 정보(GET /api/drugs/{itemSeq}/guide). 서버는 drug 표에 있는 약만 설명을 주므로,
// 아직 등록하지 않은 검색 결과는 sync 를 켜서 약 정보(GET /api/drugs/{itemSeq})를 먼저 받는다.
// 돌려주는 값: guide(불러오기 전 null) · failed · retry
export function useDrugGuide(itemSeq, { sync = false } = {}) {
  const [result, setResult] = useState(null)
  const [attempt, setAttempt] = useState(0)

  useEffect(() => {
    if (!itemSeq) return
    let active = true
    const load = sync ? getDrug(itemSeq).then(() => getDrugGuide(itemSeq)) : getDrugGuide(itemSeq)
    load
      .then((guide) => active && setResult({ itemSeq, attempt, guide }))
      .catch(() => active && setResult({ itemSeq, attempt, failed: true }))
    return () => {
      active = false
    }
  }, [itemSeq, sync, attempt])

  // 다른 약이거나 다시 불러오는 중이면 이전 결과를 쓰지 않는다.
  const current = itemSeq && result?.itemSeq === itemSeq && result.attempt === attempt ? result : null
  return {
    guide: current?.guide ?? null,
    failed: Boolean(current?.failed),
    retry: () => setAttempt((count) => count + 1),
  }
}
