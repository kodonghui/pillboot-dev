import Icon from './Icon.jsx'
import { slowWaitText, useElapsedSeconds, waitShowAfter, waitSlowAfter } from './useElapsedSeconds.js'
import WaitSeconds from './WaitSeconds.jsx'
import './WaitLine.css'

// 버튼 아래 한 줄 기다림 표시(v3-1-wait-indicator A안, MED-03 검색). 2초부터 "[도는 아이콘] 단계 · N초",
// 15초부터 오래 걸린다는 안내를 더한다. 읽어 주기는 이 부품을 감싼 role="status" 가 맡는다.
// 요청마다 새로 세도록 부르는 쪽이 요청 key 를 붙여 기다리는 동안만 그린다.
function WaitLine({ label }) {
  const seconds = useElapsedSeconds()
  if (seconds < waitShowAfter) return null

  return (
    <>
      <p className="screen-caption wait-line">
        <Icon name="loader" size="s" className="icon--spin" />
        <span>{label}</span>
        <WaitSeconds seconds={seconds} />
      </p>
      {seconds >= waitSlowAfter && <p className="screen-caption wait-slow">{slowWaitText}</p>}
    </>
  )
}

export default WaitLine
