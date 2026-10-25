import './PhotoSaveBar.css'

// 찾은 약 확인 화면(IMG-04 · 05) 아래 저장 줄. 왼쪽은 고른 상태와 도움말, 오른쪽은 다시 찍기 · 저장 버튼(children)이다.
// helpId 는 저장 버튼의 aria-describedby 로 잇는다. notes 는 도움말 아래 덧붙이는 회색 줄이다.
function PhotoSaveBar({ status, help, helpId, notes, error, busy = false, children }) {
  return (
    <>
      <div className="photo-save-bar__help">
        <p className="screen-caption photo-save-bar__muted" id={helpId}>
          {help}
        </p>
        {notes}
      </div>
      <div className="mt-card photo-save-bar" aria-busy={busy}>
        <div className="photo-save-bar__text">
          <p className="screen-item" role="status">{status}</p>
          {error && <p className="screen-caption" role="alert">{error}</p>}
        </div>
        <fieldset className="photo-save-bar__actions" disabled={busy}>{children}</fieldset>
      </div>
    </>
  )
}

export default PhotoSaveBar
