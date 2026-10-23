import { useEffect, useId, useRef, useState } from 'react'
import { flushSync } from 'react-dom'
import { generatePath, Link, Navigate, useNavigate, useSearchParams } from 'react-router'
import { errorOf } from '../api/client.js'
import { getMyDrug } from '../api/drugs.js'
import { saveSchedule } from '../api/schedules.js'
import DoseCountField from '../components/DoseCountField.jsx'
import DoseTimesField from '../components/DoseTimesField.jsx'
import DrugName from '../components/DrugName.jsx'
import DrugThumb from '../components/DrugThumb.jsx'
import ErrorScreen from '../components/ErrorScreen.jsx'
import Icon from '../components/Icon.jsx'
import Notice from '../components/Notice.jsx'
import PeriodField from '../components/PeriodField.jsx'
import ProfileTag from '../components/ProfileTag.jsx'
import ScheduleSection from '../components/ScheduleSection.jsx'
import TimingField from '../components/TimingField.jsx'
import { useManagedProfile } from '../components/useManagedProfile.js'
import { addDays, addMonths, monthOf, todayKst } from '../dates.js'
import { paths } from '../paths.js'
import { profileColor } from '../profileInfo.js'
import { checkForm, errorNames, initialForm, requestOf } from '../scheduleForm.js'
import './ScheduleFormPage.css'

// 복용 달력에서 열 달: 일정이 이번 달에 걸치면 이번 달(달력을 처음 여는 달), 아니면 시작한 달
function calendarMonth(startDate, endDate, today) {
  const month = monthOf(today)
  const lastDay = addDays(`${addMonths(month, 1)}-01`, -1)
  return startDate <= lastDay && endDate >= `${month}-01` ? month : monthOf(startDate)
}

// SCH-06 일정 수정. 약과 프로필은 그대로 두고 기간 · 복용 시간 · 1회 개수 · 복용 때를 바꿔 저장한다(PUT .../schedule).
// 주소: ?profile · drug(바꿀 일정의 사람 · 약) · from(calendar: 일정 상세에서, drug: 약 상세에서)
function ScheduleEditPage() {
  const navigate = useNavigate()
  const [searchParams] = useSearchParams()
  const today = todayKst()
  const from = searchParams.get('from') === 'drug' ? 'drug' : 'calendar'
  const profileId = Number(searchParams.get('profile')) || null
  const myDrugId = Number(searchParams.get('drug')) || null
  const { status, profiles, retry: retryProfiles } = useManagedProfile()
  const targetId = useId()
  const formRef = useRef(null)
  const [attempt, setAttempt] = useState(0)
  const [loaded, setLoaded] = useState(null)
  const [form, setForm] = useState(null)
  const [errors, setErrors] = useState({})
  const [saving, setSaving] = useState(false)
  const [saveError, setSaveError] = useState(false)

  const key = `${profileId}|${myDrugId}|${attempt}`

  useEffect(() => {
    if (!profileId || !myDrugId) return
    let active = true
    getMyDrug(profileId, myDrugId)
      .then((drug) => active && setLoaded({ key, drug }))
      .catch((error) => active && setLoaded({ key, status: errorOf(error).status ?? 0 }))
    return () => {
      active = false
    }
  }, [profileId, myDrugId, key])

  const current = loaded?.key === key ? loaded : null
  const drug = current?.drug ?? null

  // 받은 일정으로 입력값을 채운다(처음 한 번).
  if (drug?.startDate && !form) setForm({ ...initialForm(drug.startDate, drug), endDate: drug.endDate })

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
  const owner = profiles?.find((item) => item.profileId === profileId)
  // 없는 사람 · 약이거나 일정이 없는 약이면 고칠 일정이 없다.
  if (!profileId || !myDrugId || (profiles && !owner) || [403, 404].includes(current?.status) || (drug && !drug.startDate)) {
    return <ErrorScreen kind="notFound" />
  }
  if (current && !drug) {
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
        일정을 불러오지 못했어요. 잠시 후 다시 시도해 주세요.
      </Notice>
    )
  }
  if (!profiles || !drug || !form) return null

  const backTo =
    from === 'drug'
      ? generatePath(paths.drugDetail, { myDrugId })
      : `${paths.calendar}?month=${calendarMonth(drug.startDate, drug.endDate, today)}&profile=${profileId}&drug=${myDrugId}`

  const change = (field, patch) => {
    setForm((previous) => ({ ...previous, ...patch }))
    setErrors((previous) => ({ ...previous, [field]: null }))
  }

  const submit = async (event) => {
    event.preventDefault()
    const nextErrors = checkForm(form, { drugMissing: false })
    flushSync(() => {
      setErrors(nextErrors)
      setSaveError(false)
    })
    if (Object.keys(nextErrors).length) {
      formRef.current.querySelector('[aria-invalid="true"], [data-invalid]')?.focus()
      return
    }

    setSaving(true)
    try {
      await saveSchedule(profileId, myDrugId, requestOf(form))
      navigate(from === 'drug' ? backTo : `${paths.calendar}?month=${calendarMonth(form.startDate, form.endDate, today)}`, { replace: true })
    } catch {
      setSaving(false)
      setSaveError(true)
    }
  }

  const invalidNames = Object.keys(errorNames).filter((name) => errors[name])

  return (
    <>
      <div className="schedule-form-page__head">
        <Link className="mt-btn mt-btn--ghost schedule-form-page__back" to={backTo}>
          <Icon name="chevronLeft" size="s" />
          {from === 'drug' ? '약 상세로' : '일정 상세로'}
        </Link>
        <div className="schedule-form-page__title">
          <h1 className="screen-title">일정 수정</h1>
          <p className="screen-body schedule-form-page__lead">
            {from === 'drug' && `${drug.name}의 복용 정보예요. `}기간, 복용 시간, 1회 개수, 복용 때를 바꿀 수 있어요.
          </p>
        </div>
      </div>

      <form className="schedule-form-page__form" ref={formRef} noValidate onSubmit={submit}>
        {invalidNames.length > 0 && (
          <Notice variant="negative" role="alert">
            <b>저장하지 못했어요.</b> 표시한 {invalidNames.length}곳을 고쳐 주세요.
            <br />
            {invalidNames.map((name) => errorNames[name]).join(', ')}
          </Notice>
        )}
        {saveError && (
          <Notice variant="negative" role="alert">
            <b>저장하지 못했어요.</b> 입력한 내용은 그대로 있어요. 잠시 후 다시 저장해 주세요.
          </Notice>
        )}

        <ScheduleSection titleId={targetId} title="일정을 바꿀 약">
          <div className="schedule-form-page__fixed">
            <DrugThumb imageUrl={drug.drug?.imageUrl} custom={!drug.drug} alt={`${drug.name} 공식 사진`} />
            <dl>
              <div>
                <dt className="screen-label">약</dt>
                <dd>
                  <DrugName>{drug.name}</DrugName>
                </dd>
                {drug.drug?.maker && <dd className="schedule-form-page__maker">{drug.drug.maker}</dd>}
              </div>
              <div>
                <dt className="screen-label">프로필</dt>
                <dd>
                  <ProfileTag color={profileColor(profiles, profileId)}>{owner.profileName}</ProfileTag>
                </dd>
              </div>
            </dl>
          </div>
          <p className="mt-field__help">약과 프로필은 바꿀 수 없어요. 다른 약이나 사람으로 바꾸려면 이 일정을 지우고 새로 추가해 주세요.</p>
        </ScheduleSection>

        <PeriodField
          startDate={form.startDate}
          endDate={form.endDate}
          today={today}
          quickDays={[7, 14, 30, 60]}
          quickLabel="시작일부터 기간 빠르게 고르기"
          error={errors.endDate}
          onChange={(patch) => change('endDate', patch)}
        >
          <Notice variant="info">
            {from === 'drug'
              ? '오늘까지만 먹으려면 복용 달력의 일정 상세에서 ‘그만 먹기’를 눌러 주세요.'
              : '오늘까지만 먹으려면 일정 상세에서 ‘그만 먹기’를 눌러 주세요.'}
          </Notice>
        </PeriodField>

        <DoseTimesField
          slots={form.slots}
          intro="지금 일정의 시간이에요. 시간을 눌러 바꾸거나 빼고, 필요하면 더할 수 있어요."
          addHint="order"
          error={errors.times}
          onChange={(slots) => change('times', { slots })}
        />

        <DoseCountField value={form.dose} hint="1부터 10까지 넣을 수 있어요." error={errors.dose} onChange={(dose) => change('dose', { dose })} />

        <TimingField
          value={form.timing}
          hint="20자까지 쓸 수 있어요. 비우고 저장하면 복용 때가 지워져요."
          onChange={(timing) => setForm((previous) => ({ ...previous, timing }))}
        />

        <Notice variant="info" icon="clock">
          바꾼 복용 시간은 다음 알림부터 반영돼요.
        </Notice>

        <div className="schedule-form-page__foot">
          <Link className="mt-btn mt-btn--assistive" to={backTo}>
            취소
          </Link>
          <button className="mt-btn mt-btn--solid" type="submit" disabled={saving}>
            {saving && <Icon name="loader" size="s" className="icon--spin" />}
            {saving ? '저장 중…' : saveError ? '다시 저장' : '수정 저장'}
          </button>
        </div>
      </form>
    </>
  )
}

export default ScheduleEditPage
