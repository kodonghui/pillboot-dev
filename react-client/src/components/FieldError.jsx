import Icon from './Icon.jsx'
import './FieldError.css'

// 입력칸 아래 오류 문구(디자인 시스템 TextField 의 mt-field__help--error). 입력칸의 aria-describedby 로 이 id 를 잇는다.
function FieldError({ id, children }) {
  return (
    <p className="mt-field__help mt-field__help--error" id={id}>
      <Icon name="alertCircle" size="s" />
      <span>{children}</span>
    </p>
  )
}

export default FieldError
