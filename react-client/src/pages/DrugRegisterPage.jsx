import { useId } from 'react'
import { Link, Navigate } from 'react-router'
import Icon from '../components/Icon.jsx'
import Notice from '../components/Notice.jsx'
import ProfileDot from '../components/ProfileDot.jsx'
import { useManagedProfile } from '../components/useManagedProfile.js'
import { paths } from '../paths.js'
import './DrugRegisterPage.css'

const methods = [
  {
    to: paths.prescriptionPhoto,
    icon: 'fileText',
    title: '처방전 · 약봉투 사진',
    text: '약 이름이 보이는 사진 1장을 올리면 적힌 약을 찾아요.',
  },
  { to: paths.pillPhoto, icon: 'pill', title: '알약 사진', text: '알약의 앞면과 뒷면 사진 2장으로 약을 찾아요.' },
  { to: paths.drugSearch, icon: 'search', title: '이름으로 검색', text: '약 이름을 알면 공공데이터에서 바로 찾아요.' },
]

// MED-02 약 등록 방법 고르기. 관리할 사람의 약 목록에 넣을 방법 세 가지로 보낸다.
function DrugRegisterPage() {
  const { status, profile, color, retry } = useManagedProfile()
  const pickId = useId()

  if (status === 'none') return <Navigate to={paths.profileNew} replace />
  if (status === 'failed') {
    return (
      <Notice
        variant="negative"
        role="alert"
        action={
          <button className="mt-btn mt-btn--assistive mt-btn--md" type="button" onClick={retry}>
            다시 시도
          </button>
        }
      >
        프로필을 불러오지 못했어요. 잠시 후 다시 시도해 주세요.
      </Notice>
    )
  }
  if (!profile) return null

  return (
    <div className="drug-register">
      <div className="drug-register__head">
        <Link className="mt-btn mt-btn--ghost drug-register__back" to={paths.drugs}>
          <Icon name="chevronLeft" size="s" />약 목록으로
        </Link>
        <h1 className="screen-title">약 등록</h1>
        <p className="screen-body drug-register__who">
          <ProfileDot color={color} />
          <span>
            <b>{profile.profileName}</b>의 약 목록에 넣어요
          </span>
        </p>
      </div>
      <div className="drug-register__body">
        <h2 className="screen-label drug-register__label" id={pickId}>
          등록 방법을 골라 주세요
        </h2>
        <section className="mt-card mt-card--flush drug-register__card" aria-labelledby={pickId}>
          <ul>
            {methods.map((method) => (
              <li key={method.to}>
                <Link className="drug-register__pick" to={method.to}>
                  <span className="drug-register__pick-icon">
                    <Icon name={method.icon} size="l" />
                  </span>
                  <span className="drug-register__pick-text">
                    <span className="screen-item drug-register__pick-title">{method.title}</span>
                    <span className="screen-body drug-register__pick-note">{method.text}</span>
                  </span>
                  <Icon name="chevronRight" className="drug-register__go" />
                </Link>
              </li>
            ))}
          </ul>
        </section>
        <Notice variant="info">사진만으로 약을 정하지 않아요. 찾은 후보 가운데 확인한 약만 저장해요.</Notice>
      </div>
    </div>
  )
}

export default DrugRegisterPage
