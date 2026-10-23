import { useEffect, useId, useState } from 'react'
import { Link, Navigate, useSearchParams } from 'react-router'
import { getDoses } from '../api/schedules.js'
import DrugName from '../components/DrugName.jsx'
import ErrorScreen from '../components/ErrorScreen.jsx'
import Icon from '../components/Icon.jsx'
import Notice from '../components/Notice.jsx'
import ProfileTag from '../components/ProfileTag.jsx'
import { useManagedProfile } from '../components/useManagedProfile.js'
import VisuallyHidden from '../components/VisuallyHidden.jsx'
import { isDate, monthOf } from '../dates.js'
import { paths } from '../paths.js'
import { profileColor } from '../profileInfo.js'
import { doseText } from '../scheduleInfo.js'
import { dayName } from '../text.js'
import './DosesPage.css'

// 'HH:mm'(00:00 ~ 23:59)
function isTime(value) {
  return typeof value === 'string' && /^([01]\d|2[0-3]):[0-5]\d$/.test(value)
}

// 사람별로 묶는다. 서버가 프로필 · 약 번호 순서로 주므로 처음 나온 순서를 지킨다.
function groupByProfile(doses) {
  const groups = new Map()
  for (const dose of doses) {
    if (!groups.has(dose.profileId)) groups.set(dose.profileId, { profileId: dose.profileId, profileName: dose.profileName, doses: [] })
    groups.get(dose.profileId).doses.push(dose)
  }
  return [...groups.values()]
}

// 날짜 한 줄과 시각 제목(목업 .tnum)
function DosesHead({ date, children }) {
  return (
    <div className="doses-page__head">
      <p className="screen-body doses-page__neutral">{dayName(date)}</p>
      <h1 className="doses-page__title">{children}</h1>
    </div>
  )
}

// NTF-02 그 시각에 먹을 약. 복약 알림(NTF-03)을 누르면 열린다(?date=YYYY-MM-DD&time=HH:mm).
// 모든 프로필의 약을 사람별 카드로 보여 준다(GET /api/calendar/doses). 응답에 약 사진이 없어 사진 칸은 두지 않는다.
function DosesPage() {
  const [searchParams] = useSearchParams()
  const date = searchParams.get('date')
  const time = searchParams.get('time')
  const valid = isDate(date) && isTime(time)
  const { status, profiles, retry: retryProfiles } = useManagedProfile()
  const titleId = useId()
  const [attempt, setAttempt] = useState(0)
  const [loaded, setLoaded] = useState(null)

  const key = `${date}|${time}|${attempt}`

  useEffect(() => {
    if (!valid) return
    let active = true
    getDoses(date, time)
      .then((data) => active && setLoaded({ key, doses: data.doses }))
      .catch(() => active && setLoaded({ key, failed: true }))
    return () => {
      active = false
    }
  }, [valid, date, time, key])

  if (!valid) return <ErrorScreen kind="notFound" />
  if (status === 'none') return <Navigate to={paths.profileNew} replace />
  if (status === 'failed') {
    return (
      <Notice
        variant="negative"
        role="alert"
        action={
          <button className="mt-btn mt-btn--assistive mt-btn--md" type="button" onClick={retryProfiles}>
            다시 시도
          </button>
        }
      >
        프로필을 불러오지 못했어요. 잠시 후 다시 시도해 주세요.
      </Notice>
    )
  }

  const current = loaded?.key === key ? loaded : null
  // 약이 없거나 불러오지 못한 화면의 제목은 시각만 보이고 "에 먹을 약"은 화면 읽기로만 읽힌다(목업 NTF-02 빈 화면).
  const timeOnly = (
    <DosesHead date={date}>
      <span className="screen-time doses-page__time">{time}</span>
      <VisuallyHidden>에 먹을 약</VisuallyHidden>
    </DosesHead>
  )

  if (current?.failed) {
    return (
      <>
        {timeOnly}
        <Notice
          variant="negative"
          role="alert"
          action={
            <button className="mt-btn mt-btn--assistive mt-btn--md" type="button" onClick={() => setAttempt((count) => count + 1)}>
              다시 시도
            </button>
          }
        >
          먹을 약을 불러오지 못했어요. 잠시 후 다시 시도해 주세요.
        </Notice>
      </>
    )
  }
  if (!profiles || !current) return null

  // 목업은 복용 달력 첫 화면으로 간다. 알림 날짜가 다른 달일 수 있어 그 날짜의 달을 연다.
  const calendarLink = (
    <Link className="mt-btn mt-btn--assistive" to={`${paths.calendar}?month=${monthOf(date)}`}>
      <Icon name="calendar" />
      복용 달력 보기
    </Link>
  )

  if (current.doses.length === 0) {
    return (
      <>
        {timeOnly}
        <section className="mt-card doses-page__empty" aria-labelledby={titleId}>
          <span className="doses-page__empty-icon">
            <Icon name="clock" size="l" />
          </span>
          <h2 className="screen-section" id={titleId}>
            이 시각에 먹을 약이 없어요
          </h2>
          <p className="screen-body doses-page__neutral">일정이 끝났거나 바뀌었을 수 있으니 복용 달력에서 확인해 보세요.</p>
          {calendarLink}
        </section>
      </>
    )
  }

  return (
    <>
      <DosesHead date={date}>
        <span className="screen-time doses-page__time">{time}</span>
        <span className="screen-section">먹을 약 {current.doses.length}개</span>
      </DosesHead>
      <div className="doses-page__list">
        {groupByProfile(current.doses).map((group) => (
          <section className="mt-card doses-page__group" key={group.profileId} aria-labelledby={`${titleId}-${group.profileId}`}>
            <h2 className="screen-body doses-page__group-title" id={`${titleId}-${group.profileId}`}>
              <ProfileTag color={profileColor(profiles, group.profileId)}>{group.profileName}</ProfileTag>
              <span>{group.doses.length}개</span>
            </h2>
            <ul className="doses-page__rows">
              {group.doses.map((dose) => (
                <li key={dose.myDrugId}>
                  <p className="screen-item doses-page__name">
                    <DrugName>{dose.drugName}</DrugName>
                  </p>
                  <dl className="doses-page__facts">
                    <div>
                      <dt className="screen-caption doses-page__muted">1회</dt>
                      <dd>{doseText(dose.dosePerTime)}</dd>
                    </div>
                    <div>
                      <dt className="screen-caption doses-page__muted">복용 때</dt>
                      <dd className={dose.timing ? undefined : 'doses-page__none'}>{dose.timing || '정하지 않음'}</dd>
                    </div>
                  </dl>
                </li>
              ))}
            </ul>
          </section>
        ))}
        <div className="doses-page__actions">{calendarLink}</div>
      </div>
    </>
  )
}

export default DosesPage
