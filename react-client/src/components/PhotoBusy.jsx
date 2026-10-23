import { useId } from 'react'
import Icon from './Icon.jsx'
import { PhotoPreview } from './PhotoPick.jsx'
import './PhotoBusy.css'

// 사진으로 약을 찾는 동안(IMG-03). 올린 사진과 찾는 순서를 보여 주고, 같은 화면에서 기다리게 한다.
// photos: [{ file, caption }] · steps: 찾는 순서 문장 · size: 없음(처방전 사진 1장) · sm(알약 사진 2장)
function PhotoBusy({ photos, steps, size }) {
  const titleId = useId()

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
            <Icon name="clock" size="l" className="photo-busy__clock" />
            <h2 className="screen-section" id={titleId}>
              약을 찾고 있어요
            </h2>
          </div>
          <p className="screen-body photo-busy__neutral">
            약을 찾고 있어요. 사진에 따라 시간이 더 걸릴 수 있으니 이 화면에서 기다려 주세요.
          </p>
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
