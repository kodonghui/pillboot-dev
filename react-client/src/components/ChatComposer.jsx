import { useId } from 'react'
import { questionLimit } from '../chatInfo.js'
import FieldError from './FieldError.jsx'
import Icon from './Icon.jsx'
import Notice from './Notice.jsx'
import './ChatComposer.css'

// 질문 입력창과 보내기(목업 CHT-02 .composer). 1,000자를 넘거나 빈칸뿐이면 보내기를 잠그고, 쓴 질문은 지우지 않는다.
// locked: 답을 기다리거나 대화 상태를 확인하는 동안 보내기 잠금 · hint: 입력창 아래 안내 · error: 보내지 못한 까닭
function ChatComposer({ value, onChange, onSend, locked, hint, error }) {
  const inputId = useId()
  const countId = useId()
  const messageId = useId()
  const tooLong = value.length > questionLimit
  const message = tooLong ? '질문이 너무 길어요. 1,000자까지 보낼 수 있어요.' : error
  const canSend = !locked && !tooLong && value.trim() !== ''

  const send = () => {
    if (canSend) onSend()
  }

  return (
    <div className="chat-composer">
      <div className="chat-composer__row">
        <div className="chat-composer__field">
          <label className="visually-hidden" htmlFor={inputId}>
            질문
          </label>
          <input
            className="mt-input"
            id={inputId}
            type="text"
            value={value}
            placeholder="질문을 입력해 주세요"
            autoComplete="off"
            aria-invalid={message ? true : undefined}
            aria-describedby={message || hint ? `${countId} ${messageId}` : countId}
            onChange={(event) => onChange(event.target.value)}
            onKeyDown={(event) => {
              // 한글 조합 중에 누른 Enter 는 글자를 마치는 데 쓰이므로 보내지 않는다.
              if (event.key === 'Enter' && !event.nativeEvent.isComposing) {
                event.preventDefault()
                send()
              }
            }}
          />
          <div className="chat-composer__meta">
            {message && <FieldError id={messageId}>{message}</FieldError>}
            {!message && hint && (
              <p className="mt-field__help" id={messageId}>
                {hint}
              </p>
            )}
            <p className="mt-field__help chat-composer__count" id={countId}>
              {value.length.toLocaleString('ko-KR')} / {questionLimit.toLocaleString('ko-KR')}자
            </p>
          </div>
        </div>
        <button className="mt-btn mt-btn--solid chat-composer__send" type="button" disabled={!canSend} onClick={send}>
          <Icon name="send" />
          보내기
        </button>
      </div>
      <Notice variant="info">
        필받음은 진단이나 처방을 대신하지 않아요. 약을 바꾸거나 멈추기 전에 의사·약사와 상담하세요.
      </Notice>
    </div>
  )
}

export default ChatComposer
