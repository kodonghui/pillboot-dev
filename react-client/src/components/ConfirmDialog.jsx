import { useEffect, useId, useRef } from 'react'
import Icon from './Icon.jsx'
import './ConfirmDialog.css'

// 되돌리기 어렵거나 동의가 필요한 행동 앞의 확인창(디자인 시스템 Modal).
// 브라우저 <dialog> 가 포커스 가두기, Esc 닫기, 닫은 뒤 원래 버튼으로 포커스 돌려보내기를 맡는다.
function ConfirmDialog({ title, description, confirmLabel, confirmIcon, onConfirm, onClose }) {
  const dialogRef = useRef(null)
  const titleId = useId()
  const descriptionId = useId()

  useEffect(() => {
    const dialog = dialogRef.current
    if (!dialog.open) dialog.showModal()
  }, [])

  return (
    <dialog
      ref={dialogRef}
      className="mt-dim confirm-dialog"
      aria-labelledby={titleId}
      aria-describedby={descriptionId}
      onClose={onClose}
    >
      <section className="mt-modal">
        <h2 className="mt-modal__title" id={titleId}>
          {title}
        </h2>
        <p className="mt-modal__text" id={descriptionId}>
          {description}
        </p>
        <div className="mt-modal__actions">
          <button className="mt-btn mt-btn--assistive" type="button" onClick={() => dialogRef.current.close()}>
            취소
          </button>
          <button className="mt-btn mt-btn--solid" type="button" onClick={onConfirm}>
            {confirmIcon && <Icon name={confirmIcon} size="s" />}
            {confirmLabel}
          </button>
        </div>
      </section>
    </dialog>
  )
}

export default ConfirmDialog
