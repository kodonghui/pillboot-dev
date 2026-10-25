import { useEffect, useId, useState } from 'react'
import { Link, Navigate } from 'react-router'
import { getMyDrugs } from '../api/drugs.js'
import { getNotificationSettings } from '../api/notifications.js'
import { getDoses } from '../api/schedules.js'
import Icon from '../components/Icon.jsx'
import Notice from '../components/Notice.jsx'
import ProfileTag from '../components/ProfileTag.jsx'
import { useManagedProfile } from '../components/useManagedProfile.js'
import { todayKst } from '../dates.js'
import { hasSchedule } from '../drugInfo.js'
import { paths, scheduleLink } from '../paths.js'
import { profileColor } from '../profileInfo.js'
import { useAuthStore } from '../store/auth.js'
import { dayName } from '../text.js'
import './HomePage.css'

// 시각별로 묶고 시각마다 사람별 약 개수를 센다. 서버가 시각 · 프로필 · 약 번호 순서로 주므로 처음 나온 순서를 지킨다.
function groupByTime(doses) {
  const slots = new Map()
  for (const dose of doses) {
    if (!slots.has(dose.time)) slots.set(dose.time, { time: dose.time, count: 0, people: new Map() })
    const slot = slots.get(dose.time)
    slot.count += 1
    const person = slot.people.get(dose.profileId) ?? { profileId: dose.profileId, profileName: dose.profileName, count: 0 }
    person.count += 1
    slot.people.set(dose.profileId, person)
  }
  return [...slots.values()].map((slot) => ({ ...slot, people: [...slot.people.values()] }))
}

// 바로가기 한 줄(목업 .sc): 아이콘 · 제목 · 설명. 설명이 없으면 제목만 둔다.
function Shortcut({ to, icon, title, description, chevron = false }) {
  return (
    <Link className="home-page__shortcut" to={to}>
      <span className="home-page__shortcut-icon">
        <Icon name={icon} />
      </span>
      <span className="home-page__shortcut-text">
        <span className="screen-item">{title}</span>
        {description && <span className="screen-caption home-page__muted">{description}</span>}
      </span>
      {chevron && <Icon name="chevronRight" size="s" className="home-page__chevron" />}
    </Link>
  )
}

// 오늘 먹을 약(목업 CMN-01 왼쪽 카드). 모든 프로필의 오늘 복용 시각을 시각별 줄로 보여 준다.
// doses: 아직 모르면 null. 불러오는 중 · 불러오지 못함 · 약 없음은 목업에 없어 한 줄 안내로 둔다.
function TodayDoses({ today, profiles, doses, failed, onRetry }) {
  const titleId = useId()

  let body
  if (failed) {
    body = (
      <div className="home-page__state">
        <Notice
          variant="negative"
          role="alert"
          action={
            <button className="mt-btn mt-btn--assistive mt-btn--md" type="button" onClick={onRetry}>
              다시 시도
            </button>
          }
        >
          오늘 먹을 약을 불러오지 못했어요. 잠시 후 다시 시도해 주세요.
        </Notice>
      </div>
    )
  } else if (!doses) {
    body = (
      <p className="screen-body home-page__state home-page__neutral" role="status">
        <Icon name="loader" className="icon--spin" />
        오늘 먹을 약을 불러오는 중이에요.
      </p>
    )
  } else if (doses.length === 0) {
    body = <p className="screen-body home-page__state home-page__neutral">오늘 먹을 약이 없어요.</p>
  } else {
    body = (
      <ul className="home-page__slots">
        {groupByTime(doses).map((slot) => (
          <li className="home-page__slot" key={slot.time}>
            <div className="home-page__slot-main">
              <div className="home-page__slot-title">
                <span className="screen-time home-page__time">{slot.time}</span>
                <span className="screen-item">{slot.count}개</span>
              </div>
              <div className="home-page__tags">
                {slot.people.map((person) => (
                  <ProfileTag color={profileColor(profiles, person.profileId)} key={person.profileId}>
                    {person.profileName} {person.count}개
                  </ProfileTag>
                ))}
              </div>
            </div>
            <Link
              className="mt-btn mt-btn--assistive mt-btn--md"
              to={`${paths.doses}?date=${today}&time=${slot.time}`}
              aria-label={`${slot.time}에 먹을 약 보기`}
            >
              약 보기
            </Link>
          </li>
        ))}
      </ul>
    )
  }

  return (
    <section className="mt-card mt-card--flush home-page__today" aria-labelledby={titleId}>
      <div className="home-page__today-head">
        <h2 className="screen-section" id={titleId}>
          오늘 먹을 약
        </h2>
        <p className="screen-caption home-page__muted">모든 프로필의 오늘 복용 시각이에요</p>
      </div>
      {body}
      <div className="home-page__today-foot">
        <Shortcut
          to={paths.calendar}
          icon="calendarCheck"
          title="복용 달력 보기"
          description="모든 프로필의 복용 일정을 한 달력에서 봐요"
          chevron
        />
      </div>
    </section>
  )
}

// 관리할 사람의 할 일(목업 CMN-01 오른쪽 카드). drugs: 관리할 사람의 약 목록, 아직 모르거나 불러오지 못했으면 null
// 목업은 일정이 없는 약이 있고 약 개수를 알 때만 보여 준다. 그 밖의 설명은 개수를 빼거나(챗봇) 두지 않는다(일정 추가).
function Todo({ profile, drugs }) {
  const titleId = useId()
  const name = profile.profileName
  const unscheduled = drugs?.filter((drug) => !hasSchedule(drug)).length ?? 0
  let chatText = `${name}의 약을 근거로 답해요`
  if (drugs?.length === 0) chatText = '약을 등록하면 내 약 기준으로 답해요'
  else if (drugs) chatText = `${name}의 약 ${drugs.length}개를 근거로 답해요`

  return (
    <section className="mt-card home-page__todo" aria-labelledby={titleId}>
      <div className="home-page__todo-head">
        <h2 className="screen-section" id={titleId}>
          {name}의 할 일
        </h2>
        <p className="screen-caption home-page__muted">관리할 사람을 바꾸면 그 사람 기준으로 바뀌어요.</p>
      </div>
      <ul className="home-page__shortcuts">
        <li>
          <Shortcut to={paths.drugNew} icon="plus" title="약 등록" description={`사진이나 이름으로 ${name}의 약을 등록해요`} />
        </li>
        <li>
          <Shortcut
            to={scheduleLink(paths.scheduleNew, { profileId: profile.profileId, from: 'home' })}
            icon="calendar"
            title="일정 추가"
            description={unscheduled > 0 ? `일정이 없는 약이 ${unscheduled}개 있어요` : null}
          />
        </li>
        <li>
          <Shortcut to={paths.chat} icon="chat" title="약봉이에게 묻기" description={chatText} />
        </li>
      </ul>
    </section>
  )
}

// CMN-01 홈. 모든 프로필의 오늘 복용 시각(GET /api/calendar/doses, 시각 없이 하루 전체)과
// 관리할 사람의 할 일, 복약 알림이 꺼져 있을 때의 안내를 보여 준다.
// 프로필이 하나도 없으면 프로필 만들기로 보낸다(docs/auth-logout.md 의 로그인 직후 규칙과 같은 기준).
function HomePage() {
  const nickname = useAuthStore((state) => state.member?.nickname)
  const today = todayKst()
  const { status, profiles, profile, retry: retryProfiles } = useManagedProfile()
  const profileId = profile?.profileId
  const [dosesAttempt, setDosesAttempt] = useState(0)
  const [doses, setDoses] = useState(null)
  // 받은 약 목록에 프로필 번호를 함께 적어 두어, 사람을 바꾸면 이전 사람의 개수를 보이지 않는다.
  const [drugs, setDrugs] = useState(null)
  const [settings, setSettings] = useState(null)

  const dosesKey = `${today}|${dosesAttempt}`

  useEffect(() => {
    let active = true
    getDoses(today)
      .then((data) => active && setDoses({ key: dosesKey, doses: data.doses }))
      .catch(() => active && setDoses({ key: dosesKey, failed: true }))
    return () => {
      active = false
    }
  }, [today, dosesKey])

  // 할 일 설명(일정이 없는 약 · 약 개수)에만 쓴다. 불러오지 못하면 개수 없이 둔다.
  useEffect(() => {
    if (!profileId) return
    let active = true
    getMyDrugs(profileId)
      .then((list) => active && setDrugs({ profileId, list }))
      .catch(() => {})
    return () => {
      active = false
    }
  }, [profileId])

  // 복약 알림이 꺼져 있으면 아래에 안내를 둔다. 불러오지 못하면 안내 없이 둔다(복용 달력과 같다).
  useEffect(() => {
    let active = true
    getNotificationSettings()
      .then((data) => active && setSettings(data))
      .catch(() => {})
    return () => {
      active = false
    }
  }, [])

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
  if (!profile) return null

  const currentDoses = doses?.key === dosesKey ? doses : null

  return (
    <>
      <div className="home-page__head">
        <h1 className="screen-title">{nickname ? `${nickname}님, 안녕하세요` : '안녕하세요'}</h1>
        <p className="screen-body home-page__neutral">{dayName(today)}</p>
      </div>

      <div className="home-page__row">
        <TodayDoses
          today={today}
          profiles={profiles}
          doses={currentDoses?.doses ?? null}
          failed={Boolean(currentDoses?.failed)}
          onRetry={() => setDosesAttempt((count) => count + 1)}
        />
        <Todo profile={profile} drugs={drugs?.profileId === profileId ? drugs.list : null} />
      </div>

      {settings?.pushEnabled === false && (
        <Notice
          variant="caution"
          icon="bellOff"
          action={
            <Link className="mt-btn mt-btn--assistive mt-btn--md" to={paths.notifications}>
              알림 켜기
            </Link>
          }
        >
          <b>복약 알림이 꺼져 있어요.</b> 알림을 켜면 복용 시간마다 크롬으로 알려 드려요.
        </Notice>
      )}
    </>
  )
}

export default HomePage
