import { useCallback, useRef } from 'react'
import { photoAccept } from '../photoFile.js'
import Icon from './Icon.jsx'
import './PhotoPick.css'
import './VisuallyHidden.css'

// 파일 고르기 창에서 고른 사진 한 장. 같은 사진을 다시 골라도 change 가 일어나게 값을 비운다.
function pickedFile(event) {
  const file = event.target.files?.[0] ?? null
  event.target.value = ''
  return file
}

// 사진 고르기 칸(목업 .drop). 칸 전체가 파일 입력의 label 이라 누르거나 끌어 놓아 고른다.
// size: 없음(IMG-01 큰 칸, "사진 고르기" 버튼 모양 포함) · sm(IMG-02 사진 1 · 2 칸)
export function PhotoDrop({ id, size, title, invalid, describedBy, onPick }) {
  const small = size === 'sm'
  const classes = ['photo-drop', small && 'photo-drop--sm', invalid && 'photo-drop--invalid'].filter(Boolean).join(' ')
  const valueClass = small ? 'screen-caption photo-pick__muted' : 'screen-body photo-pick__neutral'

  const drop = (event) => {
    event.preventDefault()
    const file = event.dataTransfer.files?.[0]
    if (file) onPick(file)
  }

  return (
    <label className={classes} onDragOver={(event) => event.preventDefault()} onDrop={drop}>
      <input
        className="visually-hidden"
        id={id}
        type="file"
        accept={photoAccept}
        aria-invalid={invalid ? true : undefined}
        aria-describedby={describedBy}
        onChange={(event) => {
          const file = pickedFile(event)
          if (file) onPick(file)
        }}
      />
      <span className="photo-drop__icon">
        <Icon name="upload" size="l" />
      </span>
      <span className={small ? 'screen-body photo-drop__title' : 'screen-item'}>{title}</span>
      <span className="photo-drop__facts">
        <span className="photo-drop__fact">
          <span className="screen-label photo-pick__neutral">올릴 수 있는 사진</span>
          <span className={valueClass}>JPG, PNG, WEBP</span>
        </span>
        <span className="photo-drop__fact">
          <span className="screen-label photo-pick__neutral">크기</span>
          <span className={valueClass}>10MB 이하</span>
        </span>
      </span>
      {!small && <span className="mt-btn mt-btn--assistive mt-btn--sm">사진 고르기</span>}
    </label>
  )
}

// 이미 고른 사진을 바꾸는 "다른 사진 고르기" 버튼. 숨긴 파일 입력을 대신 누른다.
export function PhotoChange({ onPick }) {
  const inputRef = useRef(null)

  return (
    <>
      <button className="mt-btn mt-btn--assistive mt-btn--sm" type="button" onClick={() => inputRef.current.click()}>
        <Icon name="refresh" size="s" />
        다른 사진 고르기
      </button>
      <input
        ref={inputRef}
        type="file"
        accept={photoAccept}
        hidden
        onChange={(event) => {
          const file = pickedFile(event)
          if (file) onPick(file)
        }}
      />
    </>
  )
}

// 고른 사진(File) · 서버에서 받은 사진(Blob) 미리보기. 주소는 이 img 가 화면에 있는 동안만 쓰고 지운다.
export function PhotoPreview({ file, alt, className }) {
  const attach = useCallback(
    (img) => {
      if (!img) return undefined
      const url = URL.createObjectURL(file)
      img.src = url
      // 아직 그리는 중에 사라지면 지운 주소를 읽으려다 오류가 나므로, 주소를 먼저 떼고 지운다.
      return () => {
        img.removeAttribute('src')
        URL.revokeObjectURL(url)
      }
    },
    [file],
  )

  return (
    <span className={['photo-preview', className].filter(Boolean).join(' ')}>
      <img ref={attach} alt={alt} />
    </span>
  )
}
