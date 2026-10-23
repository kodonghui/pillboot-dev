import { useEffect } from 'react'

// 펼친 메뉴 · 목록을 바깥 누르기와 Esc 로 닫는다. Esc 로 닫으면 여는 버튼으로 포커스를 돌려준다.
// setOpen 은 useState 의 setter 를 넘긴다. 포커스가 밖으로 나갈 때 닫기는 감싸는 요소의 onBlur 에서 한다.
export function useDismiss(open, setOpen, wrapperRef, toggleRef) {
  useEffect(() => {
    if (!open) return
    const closeOnOutsidePress = (event) => {
      if (!wrapperRef.current.contains(event.target)) setOpen(false)
    }
    const closeOnEscape = (event) => {
      if (event.key !== 'Escape') return
      setOpen(false)
      toggleRef.current.focus()
    }
    document.addEventListener('pointerdown', closeOnOutsidePress)
    document.addEventListener('keydown', closeOnEscape)
    return () => {
      document.removeEventListener('pointerdown', closeOnOutsidePress)
      document.removeEventListener('keydown', closeOnEscape)
    }
  }, [open, setOpen, wrapperRef, toggleRef])
}

// 감싸는 요소의 onBlur 에 붙인다. 포커스가 감싸는 요소 밖으로 나가면 닫는다.
export function closeOnFocusOut(setOpen) {
  return (event) => {
    if (!event.currentTarget.contains(event.relatedTarget)) setOpen(false)
  }
}
