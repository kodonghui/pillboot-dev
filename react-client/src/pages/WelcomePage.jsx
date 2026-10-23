import { useId } from 'react'
import { Link } from 'react-router'
import Icon from '../components/Icon.jsx'
import Notice from '../components/Notice.jsx'
import { paths } from '../paths.js'
import './WelcomePage.css'

// 목업의 "화면 예시" 그림. 실제 회원 데이터가 아니며 화면에도 예시라고 적혀 있다.
const previewRows = [
  { profile: 2, name: '엄마', drug: '노바스크정5밀리그램', amount: '1정' },
  { profile: 2, name: '엄마', drug: '다이아벡스정500밀리그램', amount: '1정' },
  { profile: 2, name: '엄마', drug: '스포라녹스캡슐', amount: '1캡슐' },
  { profile: 1, name: '김하나', drug: '로아큐탄연질캡슐10밀리그램', amount: '1캡슐' },
]

const features = [
  {
    icon: 'image',
    title: '사진으로 약 등록',
    text: '처방전과 약봉투는 적힌 약 이름을, 알약은 앞뒤 사진을 읽어 공공데이터에서 후보를 찾아요. 확인한 약만 목록에 저장하고, 올린 사진은 등록이 끝나면 지워요.',
  },
  {
    icon: 'calendar',
    title: '가족별 약과 복용 일정',
    text: '프로필마다 약 목록을 나누고, 복용 달력 하나에 가족의 일정을 색으로 함께 보여 줘요. 복용 시간이 되면 크롬 알림으로 알려 드려요.',
  },
  {
    icon: 'chat',
    title: '내 약 기준으로 묻기',
    text: '등록한 약과 공공데이터를 근거로 답해요. 같이 먹으면 안 되는 약, 나이나 임신 때문에 조심할 약도 알려 드려요.',
  },
]

// MEM-01 시작 화면
function WelcomePage() {
  const featuresId = useId()

  return (
    <div className="welcome">
      <section className="welcome__hero">
        <div className="welcome__intro">
          <h1 className="screen-hero">
            약 이름을 몰라도
            <br />
            사진 한 장으로 복약 관리를 시작해요
          </h1>
          <p className="screen-item welcome__lead">
            처방전이나 약봉투, 알약 사진을 올리면 공공데이터에서 약을 찾아 드려요. 본인과 가족의 약, 복용 시간, 궁금한
            점까지 한곳에서 챙길 수 있어요.
          </p>
          <div className="welcome__actions">
            <Link className="mt-btn mt-btn--solid" to={paths.signup}>
              시작하기
            </Link>
            <Link className="mt-btn mt-btn--assistive" to={paths.login}>
              로그인
            </Link>
          </div>
          <p className="screen-caption welcome__muted">크롬에서 쓰는 서비스예요. 복약 알림도 크롬으로 받아요.</p>
        </div>

        <figure className="mt-card mt-card--wide welcome__preview" aria-label="필받음 화면 예시">
          <div className="welcome__preview-head">
            <p className="screen-label welcome__preview-label">오늘 아침에 먹을 약</p>
            <span className="mt-badge">화면 예시</span>
          </div>
          <div className="welcome__preview-time">
            <span className="screen-time">07:00</span>
            <span className="screen-item">4개</span>
          </div>
          <ul>
            {previewRows.map((row) => (
              <li className="welcome__preview-row" key={row.drug}>
                <span className={row.profile === 1 ? 'mt-ptag' : `mt-ptag mt-ptag--p${row.profile}`}>{row.name}</span>
                <span className="welcome__preview-drug">{row.drug}</span>
                <span className="screen-caption welcome__muted">{row.amount}</span>
              </li>
            ))}
          </ul>
          <figcaption className="screen-caption welcome__muted">
            가족마다 색이 달라요. 이름과 약은 설명을 위한 예시예요.
          </figcaption>
        </figure>
      </section>

      <section className="welcome__features" aria-labelledby={featuresId}>
        <h2 className="screen-section" id={featuresId}>
          필받음으로 할 수 있는 일
        </h2>
        <div className="welcome__feature-grid">
          {features.map((feature) => (
            <div className="welcome__feature" key={feature.title}>
              <span className="welcome__feature-icon">
                <Icon name={feature.icon} size="l" />
              </span>
              <h3 className="screen-item">{feature.title}</h3>
              <p className="welcome__sub">{feature.text}</p>
            </div>
          ))}
        </div>
      </section>

      <section className="welcome__closing">
        <Notice variant="info">
          필받음은 진단이나 처방을 대신하지 않아요. 약을 바꾸거나 멈추기 전에 의사·약사와 상담하세요.
        </Notice>
        <div className="mt-card welcome__cta">
          <div className="welcome__cta-text">
            <p className="screen-item">가입하고 프로필을 만들면 바로 시작할 수 있어요</p>
            <p className="welcome__sub">본인이나 가족의 프로필을 만들면 약을 등록하고 복용 일정을 정할 수 있어요.</p>
          </div>
          <Link className="mt-btn mt-btn--solid" to={paths.signup}>
            시작하기
          </Link>
        </div>
      </section>
    </div>
  )
}

export default WelcomePage
