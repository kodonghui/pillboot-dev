import { useState } from 'react'
import Icon from './Icon.jsx'
import './DrugThumb.css'

// 약 공식 사진 칸(목업 .thumb). 사진이 없거나 불러오지 못하면 "공식 사진 없음"을 쓴다.
// size: 없음(64px) · sm(40px, 목록 후보 · 목업 .thumb-s) · md(88px) · lg(136px). sm 은 글자 대신 알약 아이콘을 둔다.
// custom: 직접 쓴 이름의 약(공식 정보 없음). 사진 대신 알약 아이콘과 그 까닭을 둔다(목업 SCH-05).
// 이름이 옆에 있는 목록에서는 alt 를 비워 둔다.
function DrugThumb({ imageUrl, alt = '', size, dim, custom }) {
  // 실패한 주소를 기억해 두면 다른 사진으로 바뀔 때 저절로 다시 시도한다.
  const [brokenUrl, setBrokenUrl] = useState(null)
  const classes = ['drug-thumb', size && `drug-thumb--${size}`, dim && 'drug-thumb--dim'].filter(Boolean).join(' ')

  let content
  if (custom) {
    content = (
      <span className="drug-thumb__icon" role="img" aria-label="직접 쓴 약이라 사진이 없어요">
        <Icon name="pill" size="l" />
      </span>
    )
  } else if (imageUrl && brokenUrl !== imageUrl) {
    content = <img src={imageUrl} alt={alt} onError={() => setBrokenUrl(imageUrl)} />
  } else if (size === 'sm') {
    content = <Icon name="pill" className="drug-thumb__icon" />
  } else {
    content = (
      <span className="screen-caption drug-thumb__none">
        공식 사진
        <br />
        없음
      </span>
    )
  }

  return <span className={classes}>{content}</span>
}

export default DrugThumb
