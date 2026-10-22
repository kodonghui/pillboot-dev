import { useEffect, useId, useState } from 'react'
import { generatePath, Link, Navigate, useLocation } from 'react-router'
import { errorOf } from '../api/client.js'
import { getMyDrugs } from '../api/drugs.js'
import DrugThumb from '../components/DrugThumb.jsx'
import ErrorScreen from '../components/ErrorScreen.jsx'
import Icon from '../components/Icon.jsx'
import Notice from '../components/Notice.jsx'
import ProfileTag from '../components/ProfileTag.jsx'
import { useManagedProfile } from '../components/useManagedProfile.js'
import { drugSourceLabel, hasSchedule } from '../drugInfo.js'
import { paths, scheduleLink } from '../paths.js'
import { monthDay } from '../text.js'
import './DrugsPage.css'

// MED-01 약 목록. 관리할 사람의 약을 최근 등록순으로 보여 준다.
// 약을 저장하고 돌아오면(state.saved) 저장 안내와 "새로 저장" 표시를 붙인다(MED-01 등록 직후).
// 프로필이 하나도 없으면 프로필 만들기로 보낸다(docs/auth-logout.md 의 로그인 직후 규칙과 같은 기준).
function DrugsPage() {
  const { state } = useLocation()
  const { status, profile, color, retry: retryProfiles } = useManagedProfile()
  const profileId = profile?.profileId
  const emptyId = useId()
  // 받은 목록 · 오류에 프로필 번호를 함께 적어 두어, 사람을 바꾸면 이전 사람의 목록을 보이지 않는다.
  const [loaded, setLoaded] = useState(null)
  const [attempt, setAttempt] = useState(0)

  useEffect(() => {
    if (!profileId) return
    let active = true
    getMyDrugs(profileId)
      .then((list) => active && setLoaded({ profileId, attempt, list }))
      .catch((error) => active && setLoaded({ profileId, attempt, status: errorOf(error).status ?? 0 }))
    return () => {
      active = false
    }
  }, [profileId, attempt])

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

  const current = loaded?.profileId === profileId && loaded.attempt === attempt ? loaded : null
  if (current?.status === 403) return <ErrorScreen kind="forbidden" />
  if (current?.status === 404) return <ErrorScreen kind="notFound" />
  const list = current?.list ?? null
  const saved = state?.profileId === profileId ? (state.saved ?? []) : []
  const savedIds = new Set(saved.map((drug) => drug.myDrugId))
  const name = profile.profileName

  return (
    <div className="drugs-page">
      {saved.length > 0 && (
        <Notice
          variant="positive"
          role="status"
          action={
            <Link
              className="mt-btn mt-btn--assistive mt-btn--md"
              to={scheduleLink(paths.scheduleNew, { profileId, myDrugId: saved.length === 1 ? saved[0].myDrugId : null, from: 'drugs' })}
            >
              <Icon name="calendar" size="s" />
              일정 추가
            </Link>
          }
        >
          <b>약 {saved.length}개를 저장했어요.</b>
          <br />
          새로 저장한 약은 아직 복약 일정이 없어요. 일정을 등록하면 복용 달력에서 복용 시간을 볼 수 있어요.
        </Notice>
      )}

      <div className="drugs-page__head">
        <div className="drugs-page__title">
          <h1 className="screen-title">약 목록</h1>
          <div className="drugs-page__meta">
            <ProfileTag color={color}>{name}</ProfileTag>
            {list && <span className="screen-body drugs-page__lead">약 {list.length}개</span>}
          </div>
        </div>
        {list?.length > 0 && (
          <Link className="mt-btn mt-btn--solid" to={paths.drugNew}>
            <Icon name="plus" />약 등록
          </Link>
        )}
      </div>

      {current && !list && (
        <Notice
          variant="negative"
          role="alert"
          action={
            <button className="mt-btn mt-btn--assistive mt-btn--md" type="button" onClick={() => setAttempt((count) => count + 1)}>
              다시 시도
            </button>
          }
        >
          약 목록을 불러오지 못했어요. 잠시 후 다시 시도해 주세요.
        </Notice>
      )}

      {list?.length === 0 && (
        <section className="mt-card drugs-page__empty" aria-labelledby={emptyId}>
          <span className="drugs-page__empty-icon">
            <Icon name="pill" size="l" />
          </span>
          <div className="drugs-page__empty-text">
            <h2 className="screen-section" id={emptyId}>
              등록된 약이 없어요
            </h2>
            <p className="screen-body drugs-page__lead">처방전이나 약봉투 사진, 알약 사진, 약 이름으로 {name}의 약을 등록할 수 있어요.</p>
          </div>
          <Link className="mt-btn mt-btn--solid drugs-page__empty-action" to={paths.drugNew}>
            <Icon name="plus" />약 등록
          </Link>
        </section>
      )}

      {list?.length > 0 && (
        <>
          <section className="mt-card mt-card--flush drugs-page__card" aria-label={`${name}의 약`}>
            <div className="drugs-page__scroll">
              <table className="drugs-page__table">
                <thead>
                  <tr>
                    <th className="screen-label drugs-page__col-drug" scope="col">
                      약
                    </th>
                    <th className="screen-label drugs-page__col-source" scope="col">
                      등록 방법
                    </th>
                    <th className="screen-label" scope="col">
                      복약 일정
                    </th>
                  </tr>
                </thead>
                <tbody>
                  {list.map((drug) => (
                    <tr key={drug.myDrugId}>
                      <td>
                        <div className="drugs-page__drug">
                          <DrugThumb imageUrl={drug.imageUrl} />
                          <div className="drugs-page__stack">
                            <div className="drugs-page__name-line">
                              <Link
                                className="screen-item drugs-page__name"
                                to={generatePath(paths.drugDetail, { myDrugId: String(drug.myDrugId) })}
                              >
                                {drug.name}
                              </Link>
                              {savedIds.has(drug.myDrugId) && <span className="mt-badge mt-badge--primary">새로 저장</span>}
                            </div>
                            {drug.maker && <span className="screen-caption drugs-page__muted">{drug.maker}</span>}
                          </div>
                        </div>
                      </td>
                      <td>
                        <div className="drugs-page__stack">
                          <span>{drugSourceLabel(drug)}</span>
                          <span className="screen-caption drugs-page__muted">{monthDay(drug.registeredAt)} 등록</span>
                        </div>
                      </td>
                      <td>
                        {hasSchedule(drug) ? (
                          <div className="drugs-page__schedule">
                            <div className="drugs-page__times">
                              {drug.doseTimes.map((time) => (
                                <span className="mt-badge drugs-page__time" key={time}>
                                  {time}
                                </span>
                              ))}
                            </div>
                            {drug.dosePerTime != null && <span className="drugs-page__dose">1회 {drug.dosePerTime}개</span>}
                            <span className="screen-caption drugs-page__muted">
                              {monthDay(drug.startDate)}부터 {monthDay(drug.endDate)}까지
                            </span>
                          </div>
                        ) : (
                          <div className="drugs-page__no-schedule">
                            <span className="mt-badge">일정 없음</span>
                            <Link
                              className="mt-btn mt-btn--assistive mt-btn--md"
                              to={scheduleLink(paths.scheduleNew, { profileId, myDrugId: drug.myDrugId, from: 'drugs' })}
                              aria-label={`${drug.name} 일정 등록`}
                            >
                              <Icon name="calendar" size="s" />
                              일정 등록
                            </Link>
                          </div>
                        )}
                      </td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
          </section>
          <p className="screen-caption drugs-page__muted">약 이름을 누르면 공식 정보와 {name}의 복용정보를 볼 수 있어요.</p>
        </>
      )}
    </div>
  )
}

export default DrugsPage
