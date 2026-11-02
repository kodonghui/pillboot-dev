import './StrengthName.css'

// 약 이름을 기본 이름과 함량 배지로 나눠 보여 준다(V3-DRUG-004, 화면정의서 v3-1-drug-strength A안).
// 함량이 없거나(함량 없는 약 · 직접 입력) 기본 이름이 아직 없는 옛 응답이면 지금처럼 이름 하나만 보여 준다.
// 함량은 서버가 준 원문을 그대로 쓰고 단위를 바꾸지 않는다.
function StrengthName({ name, baseName, strengthText }) {
  if (!baseName || !strengthText) return name
  return (
    <>
      {baseName} <span className="mt-badge strength-name">{strengthText}</span>
    </>
  )
}

export default StrengthName
