import { useEffect, useState } from 'react'

// 기다림 표시(V3-COMMON-001, design/v3-1-wait-indicator.md). 2초 전에는 기존 기다림 문장만, 2초부터 단계 이름과 경과 초,
// 15초부터 오래 걸린다는 안내를 더한다. 완료 예상 시간 · 퍼센트는 보이지 않는다.
export const waitShowAfter = 2
export const waitSlowAfter = 15
export const slowWaitText = '평소보다 오래 걸리고 있어요. 이 화면에서 그대로 기다려 주세요.'

// 이 부품이 화면에 나온 때(요청을 보낸 때)부터 지난 초. 기다리는 동안만 그리는 부품에서 부른다.
// 0.5초마다 다시 재어 초가 밀리지 않게 하고, 화면을 떠나거나 기다림이 끝나 부품이 사라지면 타이머를 지운다.
export function useElapsedSeconds() {
  const [seconds, setSeconds] = useState(0)

  useEffect(() => {
    const start = Date.now()
    const timer = setInterval(() => setSeconds(Math.floor((Date.now() - start) / 1000)), 500)
    return () => clearInterval(timer)
  }, [])

  return seconds
}
