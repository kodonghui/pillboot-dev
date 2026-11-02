import { useEffect, useId, useRef } from 'react'
import FieldError from './FieldError.jsx'
import Icon from './Icon.jsx'
import './ConfirmDialog.css'

// 되돌리기 어렵거나 동의가 필요한 행동 앞의 확인창(디자인 시스템 Modal).
// 브라우저 <dialog> 가 포커스 가두기, Esc 닫기, 닫은 뒤 원래 버튼으로 포커스 돌려보내기를 맡는다.
// 선택: danger(삭제처럼 위험한 실행은 빨간 버튼), lead(제목과 설명 사이 요약 블록, 목업 SCH-05 삭제 확인),
// children(설명 아래 안내 블록. description 이 없으면 children 이 설명이다, 목업 NTF-01 알림 수신 동의),
// cancelLabel(닫기 버튼 글자, 기본 "취소"), error(버튼 위 오류 줄, 실행 버튼이 이 줄을 설명으로 읽는다), confirmDisabled(실행 버튼 잠금),
// busy(실행 중: 두 버튼을 잠그고 실행 버튼에 도는 아이콘을 두며 Esc 로도 닫지 않는다, 목업 MED-01 여러 개 삭제)
function ConfirmDialog({
  title,
  lead,
  description,
  cancelLabel = '취소',
  confirmLabel,
  confirmIcon,
  danger,
  confirmDisabled,
  busy = false,
  error,
  onConfirm,
  onClose,
  children,
}) {
  const dialogRef = useRef(null)
  const titleId = useId()
  const descriptionId = useId()
  const errorId = useId()

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
      onCancel={busy ? (event) => event.preventDefault() : undefined}
      onClose={onClose}
    >
      <section className="mt-modal">
        <h2 className="mt-modal__title" id={titleId}>
          {title}
        </h2>
        {lead}
        {children ? (
          <div className="confirm-dialog__description" id={descriptionId}>
            {description && <p className="mt-modal__text">{description}</p>}
            {children}
          </div>
        ) : (
          <p className="mt-modal__text" id={descriptionId}>
            {description}
          </p>
        )}
        {error && <FieldError id={errorId}>{error}</FieldError>}
        <div className={error ? 'mt-modal__actions confirm-dialog__actions--after-error' : 'mt-modal__actions'}>
          <button className="mt-btn mt-btn--assistive" type="button" disabled={busy} onClick={() => dialogRef.current.close()}>
            {cancelLabel}
          </button>
          <button
            className={danger ? 'mt-btn mt-btn--danger' : 'mt-btn mt-btn--solid'}
            type="button"
            disabled={confirmDisabled || busy}
            aria-describedby={error ? errorId : undefined}
            onClick={onConfirm}
          >
            {busy ? <Icon name="loader" size="s" className="icon--spin" /> : confirmIcon && <Icon name={confirmIcon} size="s" />}
            {confirmLabel}
          </button>
        </div>
      </section>
    </dialog>
  )
}

export default ConfirmDialog
