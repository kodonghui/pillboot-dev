import './WaitSeconds.css'

// 기다림 경과 초 "· N초". 매초 읽지 않게 timer(aria-live off)로 둔다. 단계 이름은 둘레의 status 가 읽는다.
function WaitSeconds({ seconds }) {
  return (
    <span className="wait-seconds" role="timer" aria-live="off">
      · {seconds}초
    </span>
  )
}

export default WaitSeconds
