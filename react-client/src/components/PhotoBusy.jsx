import { useId } from 'react'
import Icon from './Icon.jsx'
import { PhotoPreview } from './PhotoPick.jsx'
import { slowWaitText, useElapsedSeconds, waitShowAfter, waitSlowAfter } from './useElapsedSeconds.js'
import WaitSeconds from './WaitSeconds.jsx'
import './PhotoBusy.css'

// 사진으로 약을 찾는 동안(IMG-03). 올린 사진과 찾는 순서를 보여 주고, 같은 화면에서 기다리게 한다.
// 서버는 판독 · 공공데이터 조회를 합쳐 85초까지 기다리므로 화면에서 시간을 끊거나 사진을 다시 보내지 않는다.
// 기다림 표시(v3-1-wait-indicator A안): 2초부터 제목을 지금 단계와 경과 초로, 시계를 도는 아이콘으로 바꾸고,
// 15초부터 오래 걸린다는 안내를 쓴다. 단계는 화면이 아는 것만: 업로드가 끝나기 전 "사진 올리는 중", 뒤 "사진 읽고 약 찾는 중".
// photos: [{ file, caption }] · steps: 찾는 순서 문장 · size: 없음(처방전 사진 1장) · sm(알약 사진 2장)
// uploaded: 사진을 다 올렸음(axios 업로드 진행)
function PhotoBusy({ photos, steps, size, uploaded }) {
  const titleId = useId()
  const seconds = useElapsedSeconds()
  const shown = seconds >= waitShowAfter
  const slow = seconds >= waitSlowAfter

  return (
    <>
      <section className="mt-card photo-busy" aria-labelledby={titleId}>
        <div className="photo-busy__photos">
          {photos.map((photo) => (
            <figure className="photo-busy__figure" key={photo.caption}>
              <PhotoPreview file={photo.file} alt="" className={size === 'sm' ? 'photo-busy__photo--sm' : 'photo-busy__photo'} />
              <figcaption className="screen-caption photo-busy__muted">{photo.caption}</figcaption>
            </figure>
          ))}
        </div>
        <div className="photo-busy__status" role="status">
          <div className="photo-busy__title">
            <Icon name={shown ? 'loader' : 'clock'} size="l" className={shown ? 'photo-busy__clock icon--spin' : 'photo-busy__clock'} />
            <h2 className="screen-section" id={titleId}>
              {shown ? (
                <>
                  {uploaded ? '사진 읽고 약 찾는 중' : '사진 올리는 중'} <WaitSeconds seconds={seconds} />
                </>
              ) : (
                '사진을 확인하고 있어요'
              )}
            </h2>
          </div>
          {slow ? (
            <p className="screen-body wait-slow">{slowWaitText} 사진에 따라 1분 넘게 걸릴 수 있어요.</p>
          ) : (
            <p className="screen-body photo-busy__neutral">잠시만 기다려 주세요. 사진에 따라 시간이 더 걸릴 수 있어요.</p>
          )}
          <div className="photo-busy__how">
            <p className="screen-label photo-busy__neutral">이렇게 찾아요</p>
            <ul className="photo-busy__steps">
              {steps.map((step) => (
                <li key={step}>{step}</li>
              ))}
            </ul>
          </div>
        </div>
      </section>
      <div className="photo-busy__actions">
        <button className="mt-btn mt-btn--solid" type="button" disabled>
          <Icon name="search" />약 찾기
        </button>
      </div>
      <div className="mt-notice mt-notice--caution">
        <Icon name="alertTriangle" className="mt-notice__icon" />
        <p className="mt-notice__text">다른 화면으로 가면 결과는 저장되지 않아요.</p>
      </div>
    </>
  )
}

export default PhotoBusy
