import './VoiceOrb.css'

// AI 복약도우미 구슬(목업 .orb). onClick 이 있으면 "말로 일정 추가하기" 버튼(SCH-01 머리),
// 없으면 대화 창 머리의 꾸밈이다. small: 48px(목업 .orb-sm). motion: live(듣는 중, 빠르게) · idle(멈춤, 흐리게)
function VoiceOrb({ ref, small, motion, label, onClick }) {
  const className = ['voice-orb', small && 'voice-orb--sm', motion && `voice-orb--${motion}`].filter(Boolean).join(' ')
  const layers = (
    <>
      <span className="voice-orb__layer voice-orb__layer--1" />
      <span className="voice-orb__layer voice-orb__layer--2" />
      <span className="voice-orb__core" />
    </>
  )

  if (!onClick) {
    return (
      <span className={className} aria-hidden="true">
        {layers}
      </span>
    )
  }
  return (
    <button ref={ref} className={className} type="button" aria-label={label} title={label} aria-haspopup="dialog" onClick={onClick}>
      {layers}
    </button>
  )
}

export default VoiceOrb
