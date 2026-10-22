import { useEffect, useId, useRef, useState } from 'react'
import { flushSync } from 'react-dom'
import { generatePath, Link, Navigate, useNavigate, useSearchParams } from 'react-router'
import { errorOf } from '../api/client.js'
import { registerDrugs } from '../api/drugs.js'
import { getScheduleChoices, saveSchedule } from '../api/schedules.js'
import DoseCountField from '../components/DoseCountField.jsx'
import DoseTimesField from '../components/DoseTimesField.jsx'
import DrugPicker from '../components/DrugPicker.jsx'
import Icon from '../components/Icon.jsx'
import Notice from '../components/Notice.jsx'
import PeriodField from '../components/PeriodField.jsx'
import ScheduleSection from '../components/ScheduleSection.jsx'
import TimingField from '../components/TimingField.jsx'
import { useManagedProfile } from '../components/useManagedProfile.js'
import { isDate, monthOf, todayKst } from '../dates.js'
import { paths } from '../paths.js'
import { checkForm, drugValues, errorNames, initialForm, pickName, requestOf } from '../scheduleForm.js'
import { eunAfter, periodText } from '../text.js'
import './ScheduleFormPage.css'

// 들어온 곳(from)별 돌아가는 글자. 없거나 모르는 값이면 복용 달력으로 돌아간다.
const backLabels = { calendar: '복용 달력으로', home: '홈으로', drugs: '약 목록으로', drug: '약 상세로' }

// SCH-03 일정 추가. 사람과 약을 고르고 기간 · 복용 시간 · 1회 개수 · 복용 때를 정해 저장한다(PUT .../schedule).
// 공식 약 정보에서 고른 약은 먼저 그 사람의 약으로 등록(POST .../drugs items 방식, source SEARCH)한 뒤 일정을 저장한다.
// 주소: ?profile(사람) · drug(고른 약) · date(시작일, 복용 달력에서 누른 날) · from(들어온 곳)
function ScheduleNewPage() {
  const navigate = useNavigate()
  const [searchParams] = useSearchParams()
  const today = todayKst()
  const from = backLabels[searchParams.get('from')] ? searchParams.get('from') : 'calendar'
  const dateParam = searchParams.get('date')
  const startFromCalendar = from === 'calendar' && isDate(dateParam)
  const firstStart = startFromCalendar ? dateParam : today
  const drugParam = Number(searchParams.get('drug')) || null
  const profileParam = Number(searchParams.get('profile')) || null
  const { status, profiles, profile, retry: retryProfiles } = useManagedProfile()
  const whoId = useId()
  const drugTitleId = useId()
  const formRef = useRef(null)
  const [chosenProfileId, setChosenProfileId] = useState(null)
  const [choicesAttempt, setChoicesAttempt] = useState(0)
  const [choices, setChoices] = useState(null)
  const [picked, setPicked] = useState(null)
  const [drugParamUsed, setDrugParamUsed] = useState(false)
  const [form, setForm] = useState(() => initialForm(firstStart, null))
  const [errors, setErrors] = useState({})
  const [saving, setSaving] = useState(false)
  const [saveError, setSaveError] = useState(null)

  const fromParam = profiles?.some((item) => item.profileId === profileParam) ? profileParam : null
  const profileId = chosenProfileId ?? fromParam ?? profile?.profileId ?? null
  const choicesKey = `${profileId}|${choicesAttempt}`

  useEffect(() => {
    if (!profileId) return
    let active = true
    getScheduleChoices(profileId)
      .then((data) => active && setChoices({ key: choicesKey, drugs: data.drugs }))
      .catch(() => active && setChoices({ key: choicesKey, failed: true }))
    return () => {
      active = false
    }
  }, [profileId, choicesKey])

  const currentChoices = choices?.key === choicesKey ? choices : null

  // 약 상세 · 약 목록에서 들어왔으면 그 약을 골라 두고 저장된 값을 넣는다(처음 한 번).
  if (!drugParamUsed && drugParam && currentChoices?.drugs && profileId === (fromParam ?? profile?.profileId)) {
    setDrugParamUsed(true)
    const drug = currentChoices.drugs.find((item) => item.myDrugId === drugParam)
    if (drug) {
      setPicked({ kind: 'mine', drug })
      setForm((previous) => ({ ...previous, ...drugValues(drug) }))
    }
  }

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
  if (!profiles || !profileId) return null

  const owner = profiles.find((item) => item.profileId === profileId)
  const name = owner.profileName
  const mine = picked?.kind === 'mine' ? picked.drug : null
  const replacing = Boolean(mine?.hasSchedule)
  const backTo = {
    calendar: `${paths.calendar}?month=${monthOf(firstStart)}`,
    home: paths.home,
    drugs: paths.drugs,
    drug: drugParam ? generatePath(paths.drugDetail, { myDrugId: drugParam }) : paths.drugs,
  }[from]

  let lead = '오늘부터 먹는 것으로 정해 두었어요. 끝나는 날짜와 먹는 시간을 정해 주세요.'
  if (startFromCalendar) lead = '복용 달력에서 누른 날짜부터 시작해요. 끝나는 날짜와 먹는 시간을 정해 주세요.'
  else if (from === 'drug' && mine && !mine.hasSchedule && mine.myDrugId === drugParam) {
    lead = `${mine.drugName}${eunAfter(mine.drugName)} 아직 일정이 없어요. 오늘부터 먹는 것으로 정해 두었으니 끝나는 날짜와 먹는 시간을 정해 주세요.`
  }

  let startHint = null
  if (form.startDate === firstStart) startHint = startFromCalendar ? '복용 달력에서 누른 날짜예요.' : '오늘 날짜예요. 다른 날부터 먹는다면 바꿔 주세요.'

  const change = (key, patch) => {
    setForm((previous) => ({ ...previous, ...patch }))
    setErrors((previous) => ({ ...previous, [key]: null }))
  }

  const chooseProfile = (next) => {
    if (next === profileId) return
    setChosenProfileId(next)
    setDrugParamUsed(true)
    setPicked(null)
    setForm((previous) => ({ ...previous, ...drugValues(null) }))
    setErrors((previous) => ({ ...previous, drug: null }))
    setSaveError(null)
  }

  // 약을 고르면 그 약에 저장된 시간 · 1회 개수 · 복용 때로 바꾼다(없으면 기본값).
  // 지워진 약 대신 다시 고를 때는 입력한 값을 그대로 둔다(목업 save-failed "입력한 기간과 시간은 그대로 두었어요").
  const pick = (next) => {
    setPicked(next)
    if (next && saveError !== 'missing') setForm((previous) => ({ ...previous, ...drugValues(next.kind === 'mine' ? next.drug : null) }))
    setErrors((previous) => ({ ...previous, drug: null }))
    if (saveError === 'missing') setSaveError(null)
  }

  const focusFirstInvalid = () => formRef.current.querySelector('[aria-invalid="true"], [data-invalid]')?.focus()

  const submit = async (event) => {
    event.preventDefault()
    const nextErrors = checkForm(form, { drugMissing: !picked })
    flushSync(() => {
      setErrors(nextErrors)
      setSaveError(null)
    })
    if (Object.keys(nextErrors).length) {
      focusFirstInvalid()
      return
    }

    setSaving(true)
    let myDrugId = mine?.myDrugId
    try {
      if (!myDrugId) {
        // 공식 약은 먼저 이 사람의 약으로 등록한다. 이미 있으면(skipped) 그 약 번호를 쓴다.
        const result = await registerDrugs(profileId, { source: 'SEARCH', items: [{ itemSeq: picked.drug.itemSeq }] })
        myDrugId = result.saved?.[0]?.myDrugId ?? result.skipped?.[0]?.myDrugId
        // 일정 저장이 실패해도 다시 등록하지 않게, 고른 약을 등록된 내 약으로 바꿔 둔다.
        setPicked({ kind: 'mine', drug: { myDrugId, profileId, drugName: pickName(picked), hasSchedule: false } })
        setChoicesAttempt((count) => count + 1)
      }
      await saveSchedule(profileId, myDrugId, requestOf(form))
      navigate(from === 'calendar' ? `${paths.calendar}?month=${monthOf(form.startDate)}` : backTo, { replace: true })
    } catch (error) {
      setSaving(false)
      if (myDrugId && errorOf(error).status === 404) {
        // 다른 곳에서 약을 지웠다. 고른 약을 놓고(쓴 이름은 남김) 목록을 다시 불러온다.
        flushSync(() => {
          setPicked(null)
          setErrors((previous) => ({ ...previous, drug: { message: `${name}의 약 목록에 이 약이 없어요.` } }))
          setSaveError('missing')
          setChoicesAttempt((count) => count + 1)
        })
        focusFirstInvalid()
      } else {
        setSaveError('failed')
      }
    }
  }

  const invalidNames = Object.keys(errorNames).filter((key) => errors[key])
  let submitLabel = replacing ? '새 일정으로 바꿔 저장' : '일정 저장'
  if (saveError === 'failed') submitLabel = '다시 저장'

  return (
    <>
      <div className="schedule-form-page__head">
        <Link className="mt-btn mt-btn--ghost schedule-form-page__back" to={backTo}>
          <Icon name="chevronLeft" size="s" />
          {backLabels[from]}
        </Link>
        <div className="schedule-form-page__title">
          <h1 className="screen-title">일정 추가</h1>
          <p className="screen-body schedule-form-page__lead">{lead}</p>
        </div>
      </div>

      <form className="schedule-form-page__form" ref={formRef} noValidate onSubmit={submit}>
        {saveError === 'missing' && (
          <Notice variant="negative" role="alert">
            <b>저장하지 못했어요.</b> 고른 약을 찾을 수 없어요.
            <br />
            다른 기기에서 약을 지웠을 수 있어요. 약을 다시 골라 주세요. 입력한 기간과 시간은 그대로 두었어요.
          </Notice>
        )}
        {saveError !== 'missing' && invalidNames.length > 0 && (
          <Notice variant="negative" role="alert">
            <b>저장하지 못했어요.</b> 표시한 {invalidNames.length}곳을 고쳐 주세요.
            <br />
            {invalidNames.map((key) => errorNames[key]).join(', ')}
          </Notice>
        )}
        {saveError === 'failed' && (
          <Notice variant="negative" role="alert">
            <b>저장하지 못했어요.</b> 입력한 내용은 그대로 있어요. 잠시 후 다시 저장해 주세요.
          </Notice>
        )}

        <ScheduleSection titleId={whoId} title="누구의 일정인가요">
          <div className="schedule-form-page__chips" role="group" aria-labelledby={whoId}>
            {profiles.map((item) => (
              <button
                key={item.profileId}
                className="mt-chip"
                type="button"
                aria-pressed={item.profileId === profileId}
                onClick={() => chooseProfile(item.profileId)}
              >
                {item.profileName}
              </button>
            ))}
          </div>
        </ScheduleSection>

        <ScheduleSection titleId={drugTitleId} title="약" mark="필수">
          <DrugPicker
            key={profileId}
            profileId={profileId}
            profileName={name}
            choices={currentChoices?.drugs ?? null}
            choicesFailed={Boolean(currentChoices?.failed)}
            picked={picked}
            error={errors.drug}
            onRetryChoices={() => setChoicesAttempt((count) => count + 1)}
            onPick={pick}
          />
          {replacing && (
            <Notice variant="caution" role="status" icon="alertTriangle">
              <b>이 약에는 이미 일정이 있어요.</b> 지금 일정({periodText(mine.startDate, mine.endDate)}, {mine.doseTimes.join('·')})은 저장하면 새로
              정한 일정으로 바뀌어요. 약 하나에는 일정을 하나만 둘 수 있어요.
            </Notice>
          )}
        </ScheduleSection>

        <PeriodField
          startDate={form.startDate}
          endDate={form.endDate}
          today={today}
          startHint={startHint}
          quickDays={[3, 7, 14, 30]}
          quickLabel="기간 빠르게 고르기"
          error={errors.endDate}
          onChange={(patch) => change('endDate', patch)}
        />

        <DoseTimesField
          slots={form.slots}
          intro={replacing ? null : '아침·점심·저녁이 기본으로 들어가 있어요. 시간을 눌러 바꾸거나 필요 없는 때는 빼 주세요.'}
          addHint="count"
          error={errors.times}
          onChange={(slots) => change('times', { slots })}
        />

        <DoseCountField
          value={form.dose}
          hint={
            mine?.dosePerTime != null && form.dose === String(mine.dosePerTime)
              ? '이 약에 저장된 값을 넣어 두었어요. 1부터 10까지 넣을 수 있어요.'
              : '1부터 10까지 넣을 수 있어요.'
          }
          error={errors.dose}
          onChange={(dose) => change('dose', { dose })}
        />

        <TimingField
          value={form.timing}
          hint={mine?.timing && form.timing === mine.timing ? '이 약에 저장된 값이에요. 20자까지 쓸 수 있어요.' : '20자까지 쓸 수 있어요.'}
          onChange={(timing) => setForm((previous) => ({ ...previous, timing }))}
        />

        <div className="schedule-form-page__foot">
          <Link className="mt-btn mt-btn--assistive" to={backTo}>
            취소
          </Link>
          <button className="mt-btn mt-btn--solid" type="submit" disabled={saving}>
            {saving && <Icon name="loader" size="s" className="icon--spin" />}
            {saving ? '저장 중…' : submitLabel}
          </button>
        </div>
      </form>
    </>
  )
}

export default ScheduleNewPage
