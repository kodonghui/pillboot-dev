import './VisuallyHidden.css'

// 화면에는 숨기고 화면 읽기에만 읽히는 글자(목업 .sr). 예: 값 "-" 옆의 "정보 없음"
function VisuallyHidden({ children }) {
  return <span className="visually-hidden">{children}</span>
}

export default VisuallyHidden
