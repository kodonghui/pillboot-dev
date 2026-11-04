import { useEffect, useId, useState } from 'react'
import { Link } from 'react-router'
import { getMyDrugs } from '../api/drugs.js'
import { hasSchedule } from '../drugInfo.js'
import { profileColor } from '../profileInfo.js'
import { scheduleState } from '../scheduleInfo.js'
import { dotDate } from '../text.js'
import DrugName from './DrugName.jsx'
import Notice from './Notice.jsx'
import ProfileTag from './ProfileTag.jsx'
import './ScheduleHistory.css'

const stateBadges = {
  active: { label: '먹는 중', className: 'mt-badge mt-badge--primary' },
  notStarted: { label: '시작 전', className: 'mt-badge' },
  ended: { label: '끝남', className: 'mt-badge' },
}

// 프로필 순서대로, 일정이 있는 약을 시작일이 늦은 것부터. 일정이 없는 프로필은 뺀다.
function groupsOf(profiles, lists) {
  return profiles
    .map((profile, index) => ({
      profile,
      drugs: lists[index].filter(hasSchedule).sort((a, b) => b.startDate.localeCompare(a.startDate) || a.myDrugId - b.myDrugId),
    }))
    .filter((group) => group.drugs.length > 0)
}

// SCH-01 아래 약별 복용 기간(design/v3-dec-034-web-ux.md). 달력 범위 밖의 지난 일정도 보인다.
// 약 하나에 일정 하나라 일정을 바꾸면 앞 기간은 남지 않는다. reloadKey 가 바뀌면 다시 불러온다.
// rowLink(profileId, drug): 줄을 눌렀을 때 갈 주소 · onRowClick: 그때 달력이 할 일
function ScheduleHistory({ profiles, today, reloadKey, rowLink, onRowClick }) {
  const titleId = useId()
  const [attempt, setAttempt] = useState(0)
  const [result, setResult] = useState(null)

  useEffect(() => {
    let active = true
    Promise.all(profiles.map((profile) => getMyDrugs(profile.profileId)))
      .then((lists) => active && setResult({ groups: groupsOf(profiles, lists) }))
      .catch(() => active && setResult({ failed: true }))
    return () => {
      active = false
    }
  }, [profiles, reloadKey, attempt])

  if (!result) return null
  if (result.failed) {
    return (
      <Notice
        variant="negative"
        role="alert"
        action={
          <button className="mt-btn mt-btn--assistive mt-btn--md" type="button" onClick={() => setAttempt((count) => count + 1)}>
            다시 시도
          </button>
        }
      >
        약별 복용 기간을 불러오지 못했어요. 달력의 일정은 그대로 있어요.
      </Notice>
    )
  }
  if (result.groups.length === 0) return null

  return (
    <section className="mt-card schedule-history" aria-labelledby={titleId}>
      <div className="schedule-history__head">
        <h2 className="screen-section" id={titleId}>
          약별 복용 기간
        </h2>
        <p className="screen-body schedule-history__neutral">지금 일정의 시작일과 끝일이에요. 일정을 바꾸면 바뀐 기간으로 보여요.</p>
      </div>
      {result.groups.map(({ profile, drugs }) => (
        <div className="schedule-history__group" key={profile.profileId}>
          <ProfileTag color={profileColor(profiles, profile.profileId)}>{profile.profileName}</ProfileTag>
          <ul>
            {drugs.map((drug) => {
              const badge = stateBadges[scheduleState(drug, today)]
              const period = `${dotDate(drug.startDate)} ~ ${dotDate(drug.endDate)}`
              return (
                <li key={drug.myDrugId}>
                  <Link
                    className="schedule-history__row"
                    to={rowLink(profile.profileId, drug)}
                    replace
                    aria-label={`${profile.profileName}, ${drug.name}, ${period}, ${badge.label}. 일정 상세 보기`}
                    onClick={onRowClick}
                  >
                    <span className="schedule-history__name">
                      <DrugName>{drug.name}</DrugName>
                    </span>
                    <span className="screen-body schedule-history__period">{period}</span>
                    <span className={badge.className}>{badge.label}</span>
                  </Link>
                </li>
              )
            })}
          </ul>
        </div>
      ))}
    </section>
  )
}

export default ScheduleHistory
