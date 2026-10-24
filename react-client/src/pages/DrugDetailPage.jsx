import { useEffect, useId, useState } from 'react'
import { Link, Navigate, useNavigate, useParams } from 'react-router'
import { errorOf } from '../api/client.js'
import { deleteMyDrug, getMyDrug } from '../api/drugs.js'
import ConfirmDialog from '../components/ConfirmDialog.jsx'
import DrugThumb from '../components/DrugThumb.jsx'
import ErrorScreen from '../components/ErrorScreen.jsx'
import GuideFailed from '../components/GuideFailed.jsx'
import Icon from '../components/Icon.jsx'
import NoValue from '../components/NoValue.jsx'
import Notice from '../components/Notice.jsx'
import ProfileTag from '../components/ProfileTag.jsx'
import { useDrugGuide } from '../components/useDrugGuide.js'
import { useManagedProfile } from '../components/useManagedProfile.js'
import { drugSourceLabel, guideProvider, guideSections, hasSchedule } from '../drugInfo.js'
import { paths, scheduleLink } from '../paths.js'
import { dotDate, eulAfter, monthDay, monthDayTime } from '../text.js'
import './DrugDetailPage.css'

// MED-04 약 상세. 관리할 사람의 약 한 건(복용정보 + 약 정보)과 공공데이터 공식 정보를 보여 준다.
// 일정이 없는 약은 "복용 정보 등록"(SCH-03), 있는 약은 "복용 정보 수정"(SCH-06) · "복약 일정 보기"(SCH-01)로 간다.
function DrugDetailPage() {
  const navigate = useNavigate()
  const myDrugId = Number(useParams().myDrugId)
  const { status, profile, color, retry: retryProfiles } = useManagedProfile()
  const titleId = useId()
  const profileId = profile?.profileId
  const [loaded, setLoaded] = useState(null)
  const [attempt, setAttempt] = useState(0)
  const [confirming, setConfirming] = useState(false)
  const [deleting, setDeleting] = useState(false)
  const [deleteFailed, setDeleteFailed] = useState(false)

  // 어느 사람의 어느 약(target)을 몇 번째로(attempt) 불러온 결과인지 함께 적어 둔다.
  const target = `${profileId}|${myDrugId}`
  const key = `${target}|${attempt}`

  useEffect(() => {
    if (!profileId || !Number.isInteger(myDrugId)) return
    let active = true
    getMyDrug(profileId, myDrugId)
      .then((drug) => active && setLoaded({ key, target, drug }))
      .catch((error) => active && setLoaded({ key, target, status: errorOf(error).status ?? 0 }))
    return () => {
      active = false
    }
  }, [profileId, myDrugId, key, target])

  const current = loaded?.key === key ? loaded : null
  // 다시 불러오는 중: 같은 약의 앞선 실패 안내를 그대로 두고 버튼만 잠근다(목업 CHT-01-03 다시 시도하는 중).
  const retrying = !current && loaded?.target === target && !loaded.drug
  const drug = current?.drug ?? null
  const itemSeq = drug?.drug?.itemSeq
  const { guide, failed: guideFailed, retry: retryGuide } = useDrugGuide(itemSeq)

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
  if (!Number.isInteger(myDrugId) || current?.status === 404) return <ErrorScreen kind="notFound" />
  if (current?.status === 403) return <ErrorScreen kind="forbidden" />
  // 약 한 건을 불러오지 못함. 다시 불러오기는 이 요청만 다시 보낸다(공식 정보는 약을 받은 뒤에 따로 불러온다).
  if ((current && !drug) || retrying) {
    return (
      <Notice
        variant="negative"
        role="alert"
        action={
          <button
            className="mt-btn mt-btn--assistive mt-btn--md"
            type="button"
            disabled={retrying}
            onClick={() => setAttempt((count) => count + 1)}
          >
            <Icon name={retrying ? 'loader' : 'refresh'} size="s" className={retrying ? 'icon--spin' : undefined} />
            {retrying ? '다시 불러오는 중…' : '다시 불러오기'}
          </button>
        }
      >
        약 정보를 불러오지 못했어요. 잠시 후 다시 시도해 주세요.
      </Notice>
    )
  }
  if (!profile || !drug) return null

  const name = profile.profileName
  const scheduled = hasSchedule(drug)
  const material = drug.drug?.material
  const sections = guide ? guideSections(guide) : []

  const remove = async () => {
    setDeleting(true)
    setDeleteFailed(false)
    try {
      await deleteMyDrug(profileId, myDrugId)
      navigate(paths.drugs, { replace: true })
    } catch {
      setDeleteFailed(true)
      setDeleting(false)
    }
  }

  const times = (
    <span className="drug-detail__times">
      {drug.doseTimes?.map((time) => (
        <span className="mt-badge drug-detail__number" key={time}>
          {time}
        </span>
      ))}
    </span>
  )
  const period = scheduled && (
    <span className="screen-caption drug-detail__muted">
      {monthDay(drug.startDate)}부터 {monthDay(drug.endDate)}까지
    </span>
  )

  return (
    <div className="drug-detail">
      <div className="drug-detail__head">
        <Link className="mt-btn mt-btn--ghost drug-detail__back" to={paths.drugs}>
          <Icon name="chevronLeft" size="s" />약 목록으로
        </Link>
        <div className="drug-detail__title">
          <h1 className="screen-title">{drug.name}</h1>
          <div className="drug-detail__meta">
            <ProfileTag color={color}>{name}</ProfileTag>
            {drug.drug?.maker && <span className="screen-body drug-detail__neutral">{drug.drug.maker}</span>}
            {material && <span className="screen-caption drug-detail__muted">성분 {material}</span>}
          </div>
        </div>
      </div>

      <div className="drug-detail__columns">
        <section className="mt-card drug-detail__official" aria-labelledby={`${titleId}-official`}>
          <div className="drug-detail__box-head">
            <div className="drug-detail__box-title">
              <Icon name="book" size="l" />
              <h2 className="screen-section" id={`${titleId}-official`}>
                공식 정보
              </h2>
            </div>
            <span className="mt-badge mt-badge--info">공공데이터</span>
          </div>
          <div className="drug-detail__official-body">
            <figure className="drug-detail__figure">
              <DrugThumb imageUrl={drug.drug?.imageUrl} size="lg" alt={`${drug.name} 공식 사진`} />
              <figcaption className="screen-caption drug-detail__muted">공식 사진</figcaption>
            </figure>
            <div className="drug-detail__guide">
              {guideFailed && <GuideFailed onRetry={retryGuide} />}
              {itemSeq && !guideFailed && !guide && (
                <p className="screen-caption drug-detail__muted" role="status">
                  공식 정보를 불러오고 있어요.
                </p>
              )}
              {guide && sections.length > 0 && (
                <>
                  <div className="drug-detail__sections">
                    {sections.map((section) => (
                      <details className="drug-detail__section" key={section.key}>
                        <summary className="screen-label drug-detail__neutral">{section.title}</summary>
                        <p>{section.content}</p>
                      </details>
                    ))}
                  </div>
                  <p className="screen-caption drug-detail__muted">
                    출처 {guide.sourceName}
                    {guide.referenceDate && (
                      <>
                        {' · '}
                        <span className="drug-detail__number">{dotDate(guide.referenceDate)}</span> 기준
                      </>
                    )}
                  </p>
                </>
              )}
              {guide && sections.length === 0 && (
                <p className="screen-body drug-detail__neutral">공공데이터에서 이 약의 효능 · 사용법 · 주의사항을 찾지 못했어요.</p>
              )}
            </div>
          </div>
          <div className="drug-detail__source">
            <p className="screen-label drug-detail__neutral">출처</p>
            <dl className="drug-detail__source-grid">
              <div>
                <dt className="screen-caption drug-detail__muted">제공 기관</dt>
                <dd>{guideProvider}</dd>
              </div>
              <div>
                <dt className="screen-caption drug-detail__muted">서비스</dt>
                <dd>{guide?.sourceName ?? <NoValue />}</dd>
              </div>
              <div>
                <dt className="screen-caption drug-detail__muted">품목기준코드</dt>
                <dd>{itemSeq ?? <NoValue />}</dd>
              </div>
              <div>
                <dt className="screen-caption drug-detail__muted">조회 시각</dt>
                <dd className="drug-detail__number">{guide ? monthDayTime(guide.retrievedAt) : <NoValue />}</dd>
              </div>
            </dl>
          </div>
        </section>

        <section className="mt-card drug-detail__mine" aria-labelledby={`${titleId}-mine`}>
          <div className="drug-detail__box-head">
            <div className="drug-detail__box-title">
              <Icon name="user" size="l" />
              <h2 className="screen-section" id={`${titleId}-mine`}>
                내 복용정보
              </h2>
            </div>
            <ProfileTag color={color}>{name}</ProfileTag>
          </div>
          <dl className="drug-detail__kv">
            {scheduled && (
              <>
                <div>
                  <dt className="screen-label">1회 개수</dt>
                  <dd>{drug.dosePerTime != null ? `${drug.dosePerTime}개` : <NoValue />}</dd>
                </div>
                <div>
                  <dt className="screen-label">복용 때</dt>
                  <dd>{drug.timing || <NoValue />}</dd>
                </div>
              </>
            )}
            <div>
              <dt className="screen-label">복약 일정</dt>
              <dd>
                {scheduled ? (
                  <>
                    {times}
                    {period}
                  </>
                ) : (
                  <>
                    <span className="mt-badge">일정 없음</span>
                    <span className="screen-caption drug-detail__muted">일정을 등록하면 복용 달력에서 복용 시간을 볼 수 있어요.</span>
                  </>
                )}
              </dd>
            </div>
            <div>
              <dt className="screen-label">등록 방법</dt>
              <dd>
                <span>{drugSourceLabel(drug)}</span>
                <span className="screen-caption drug-detail__muted">{monthDay(drug.registeredAt)} 등록</span>
              </dd>
            </div>
          </dl>
          {scheduled ? (
            <div className="drug-detail__mine-actions">
              <Link className="mt-btn mt-btn--assistive" to={scheduleLink(paths.scheduleEdit, { profileId, myDrugId, from: 'drug' })}>
                <Icon name="pencil" />
                복용 정보 수정
              </Link>
              <Link className="mt-btn mt-btn--solid" to={paths.calendar}>
                <Icon name="calendar" />
                복약 일정 보기
              </Link>
            </div>
          ) : (
            <Link className="mt-btn mt-btn--solid" to={scheduleLink(paths.scheduleNew, { profileId, myDrugId, from: 'drug' })}>
              <Icon name="plus" />
              복용 정보 등록
            </Link>
          )}
        </section>
      </div>

      <section className="drug-detail__delete" aria-label="약 삭제">
        <div className="drug-detail__delete-text">
          {scheduled ? (
            <>
              <p className="screen-body drug-detail__neutral">그만 먹을 때는 일정의 &apos;그만 먹기&apos;를 쓰세요.</p>
              <p className="screen-caption drug-detail__muted">약을 삭제하면 이 약의 복약 일정도 함께 지워져요.</p>
            </>
          ) : (
            <p className="screen-caption drug-detail__muted">약을 삭제하면 {name}의 약 목록에서 빠져요.</p>
          )}
        </div>
        <button className="mt-btn mt-btn--danger-outlined" type="button" aria-haspopup="dialog" onClick={() => setConfirming(true)}>
          <Icon name="trash" />약 삭제
        </button>
      </section>

      <Notice variant="info">필받음은 진단이나 처방을 대신하지 않아요. 약을 바꾸거나 멈추기 전에 의사·약사와 상담하세요.</Notice>

      {confirming && (
        <ConfirmDialog
          title={`${drug.name}${eulAfter(drug.name)} 삭제할까요?`}
          description={
            scheduled ? (
              <>
                이 약의 복약 일정도 함께 지워져요.
                <br />
                {name}의 다른 약과 일정은 그대로예요.
              </>
            ) : (
              `${name}의 다른 약과 일정은 그대로예요.`
            )
          }
          danger
          confirmLabel="삭제"
          confirmDisabled={deleting}
          error={deleteFailed ? '삭제하지 못했어요. 잠시 후 다시 시도해 주세요.' : null}
          onConfirm={remove}
          onClose={() => {
            setConfirming(false)
            setDeleteFailed(false)
          }}
        >
          <div className="drug-detail__delete-target">
            <DrugThumb imageUrl={drug.drug?.imageUrl} />
            <div className="drug-detail__delete-target-text">
              <p className="drug-detail__strong">{drug.name}</p>
              {scheduled ? (
                <div className="drug-detail__delete-schedule">
                  <span className="screen-label drug-detail__neutral">지워지는 일정</span>
                  {times}
                  {period}
                </div>
              ) : (
                drug.drug?.maker && <p className="screen-caption drug-detail__muted">{drug.drug.maker}</p>
              )}
            </div>
          </div>
        </ConfirmDialog>
      )}
    </div>
  )
}

export default DrugDetailPage
