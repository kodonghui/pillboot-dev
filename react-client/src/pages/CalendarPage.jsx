import { useEffect, useId, useRef, useState } from 'react'
import { Link, Navigate, useSearchParams } from 'react-router'
import { errorOf } from '../api/client.js'
import { getMyDrug } from '../api/drugs.js'
import { getNotificationSettings } from '../api/notifications.js'
import { getCalendar } from '../api/schedules.js'
import CalendarMonth from '../components/CalendarMonth.jsx'
import Icon from '../components/Icon.jsx'
import IconButton from '../components/IconButton.jsx'
import Notice from '../components/Notice.jsx'
import ScheduleDetailPanel from '../components/ScheduleDetailPanel.jsx'
import { useManagedProfile } from '../components/useManagedProfile.js'
import VoiceOrb from '../components/VoiceOrb.jsx'
import VoiceScheduleDialog from '../components/VoiceScheduleDialog.jsx'
import { addMonths, isMonth, monthOf, monthWeeks, todayKst } from '../dates.js'
import { LiveConversation } from '../liveConversation.js'
import { paths, scheduleLink } from '../paths.js'
import { profileColor } from '../profileInfo.js'
import { scheduleKey } from '../scheduleInfo.js'
import { monthTitle } from '../text.js'
import './CalendarPage.css'

// 달 · 열린 일정 상세 주소(?month=YYYY-MM&profile=&drug=). 같은 화면 안의 이동이라 기록을 쌓지 않는다.
function calendarSearch(month, schedule) {
  const query = new URLSearchParams({ month })
  if (schedule) {
    query.set('profile', schedule.profileId)
    query.set('drug', schedule.myDrugId)
  }
  return `?${query}`
}

// SCH-01 복용 달력. 모든 프로필의 일정을 한 달 달력에 막대로 그린다(GET /api/calendar, 보이는 6주 범위).
// 날짜를 누르면 그날부터 일정 추가(SCH-03), 막대를 누르면 오른쪽에 일정 상세(SCH-05),
// 구슬을 누르면 AI 복약도우미 음성 대화(SCH-04)가 열린다. 달과 열린 상세는 주소 쿼리에 둔다.
function CalendarPage() {
  const [searchParams, setSearchParams] = useSearchParams()
  const today = todayKst()
  const monthParam = searchParams.get('month')
  const month = isMonth(monthParam) ? monthParam : monthOf(today)
  const openProfileId = Number(searchParams.get('profile')) || null
  const openDrugId = Number(searchParams.get('drug')) || null
  const selectedKey = openProfileId && openDrugId ? scheduleKey(openProfileId, openDrugId) : null
  const { status, profiles, profile, retry: retryProfiles } = useManagedProfile()
  const monthTitleId = useId()
  const errorTitleId = useId()
  const monthRef = useRef(null)
  const orbRef = useRef(null)
  const [attempt, setAttempt] = useState(0)
  const [calendar, setCalendar] = useState(null)
  const [panelAttempt, setPanelAttempt] = useState(0)
  const [panelData, setPanelData] = useState(null)
  const [barClicked, setBarClicked] = useState(false)
  const [settings, setSettings] = useState(null)
  const [conversation, setConversation] = useState(null)
  // 음성 대화를 연 횟수. "다시 시작"하면 대화 창을 새로 띄운다(포커스도 처음부터).
  const [voiceRun, setVoiceRun] = useState(0)
  const [newKey, setNewKey] = useState(null)

  const weeks = monthWeeks(month)
  const from = weeks[0][0]
  const to = weeks.at(-1)[6]
  const key = `${from}|${to}|${attempt}`

  // 다시 불러오는 동안에는 앞서 받은 일정을 그대로 그려 둔다.
  useEffect(() => {
    let active = true
    getCalendar(from, to)
      .then((data) => active && setCalendar({ key, schedules: data.schedules }))
      .catch(() => active && setCalendar((previous) => ({ key, failed: true, schedules: previous?.schedules ?? null })))
    return () => {
      active = false
    }
  }, [from, to, key])

  const panelKey = selectedKey ? `${selectedKey}|${panelAttempt}` : null

  useEffect(() => {
    if (!openProfileId || !openDrugId) return
    let active = true
    getMyDrug(openProfileId, openDrugId)
      .then((drug) => active && setPanelData({ key: panelKey, drug }))
      .catch((error) => active && setPanelData({ key: panelKey, status: errorOf(error).status ?? 0 }))
    return () => {
      active = false
    }
  }, [openProfileId, openDrugId, panelKey])

  // 복약 알림이 꺼져 있으면 위에 안내를 둔다. 불러오지 못하면 안내 없이 둔다.
  useEffect(() => {
    let active = true
    getNotificationSettings()
      .then((data) => active && setSettings(data))
      .catch(() => {})
    return () => {
      active = false
    }
  }, [])

  // 화면을 떠나면 음성 대화도 끝낸다(이미 끝난 대화는 그대로).
  useEffect(() => {
    if (!conversation) return
    return () => conversation.end()
  }, [conversation])

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
  if (!profiles) return null

  const current = calendar?.key === key ? calendar : null
  const schedules = calendar?.schedules ?? null
  const loadFailed = Boolean(current?.failed)
  const reload = () => setAttempt((count) => count + 1)

  const showMonth = (next) => setSearchParams(calendarSearch(next), { replace: true })

  // 상세를 닫으면 그 일정 막대(없으면 달 제목)로 포커스를 돌려준다.
  const closePanel = () => {
    setSearchParams(calendarSearch(month), { replace: true })
    const bar = document.querySelector(`[data-schedule="${selectedKey}"]`)
    ;(bar ?? monthRef.current).focus()
  }

  // 그만 먹기 · 삭제가 끝났거나 지워진 일정이면 상세를 닫고 달력을 새로 불러온다.
  const finishPanel = () => {
    reload()
    setSearchParams(calendarSearch(month), { replace: true })
    monthRef.current.focus()
  }

  const refreshPanel = () => {
    reload()
    setPanelAttempt((count) => count + 1)
  }

  const startVoice = () => {
    // 상세를 열어 둔 일정이 있으면 그 사람 · 약을, 없으면 관리할 사람을 대화에 넘긴다. 날짜는 오늘이다.
    const next = new LiveConversation({
      profileId: openProfileId ?? profile?.profileId ?? profiles[0].profileId,
      myDrugId: openProfileId ? openDrugId : null,
      date: today,
      onOperation: (operation, saved) => {
        reload()
        setPanelAttempt((count) => count + 1)
        if (operation.action === 'SAVE' && saved) setNewKey(scheduleKey(saved.profileId, saved.myDrugId))
      },
    })
    setNewKey(null)
    setConversation(next)
    setVoiceRun((count) => count + 1)
    next.start()
  }

  const closeVoice = () => {
    conversation.end()
    setConversation(null)
    orbRef.current?.focus()
  }

  // 저장 · 그만 먹기 뒤 "일정 상세": 대화를 닫고 그 일정의 상세를 연다(보이는 달에 없으면 시작한 달로).
  const showVoiceSchedule = (schedule) => {
    conversation.end()
    setConversation(null)
    setBarClicked(true)
    const visible = schedule.startDate <= to && schedule.endDate >= from
    setSearchParams(calendarSearch(visible ? month : monthOf(schedule.startDate), schedule), { replace: true })
  }

  let panel = null
  if (selectedKey) {
    const panelCurrent = panelData?.key === panelKey ? panelData : null
    const owner = profiles.find((item) => item.profileId === openProfileId)
    let panelStatus = 'loading'
    if (!owner) panelStatus = 'notFound'
    else if (panelCurrent?.drug) panelStatus = panelCurrent.drug.startDate ? 'ready' : 'notFound'
    else if (panelCurrent) panelStatus = [403, 404].includes(panelCurrent.status) ? 'notFound' : 'failed'
    panel = (
      <ScheduleDetailPanel
        key={selectedKey}
        status={panelStatus}
        drug={panelCurrent?.drug}
        profileId={openProfileId}
        profileName={owner?.profileName}
        color={owner ? profileColor(profiles, owner.profileId) : null}
        today={today}
        editTo={scheduleLink(paths.scheduleEdit, { profileId: openProfileId, myDrugId: openDrugId, from: 'calendar' })}
        focusOnOpen={barClicked}
        onClose={closePanel}
        onRetry={() => setPanelAttempt((count) => count + 1)}
        onRefresh={finishPanel}
        onDone={finishPanel}
        onStale={refreshPanel}
      />
    )
  }

  const monthNav = (
    <div className="calendar-page__nav">
      <IconButton icon="chevronLeft" label="이전 달" onClick={() => showMonth(addMonths(month, -1))} />
      <h2 className="screen-section calendar-page__month" id={monthTitleId} ref={monthRef} tabIndex={-1} aria-live="polite">
        {monthTitle(month)}
      </h2>
      <IconButton icon="chevronRight" label="다음 달" onClick={() => showMonth(addMonths(month, 1))} />
      {!loadFailed && (
        <button className="mt-btn mt-btn--assistive mt-btn--md calendar-page__today" type="button" onClick={() => showMonth(monthOf(today))}>
          오늘
        </button>
      )}
    </div>
  )

  return (
    <>
      <div className="calendar-page__head">
        <div className="calendar-page__title">
          <h1 className="screen-title">복용 달력</h1>
          <p className="screen-body calendar-page__neutral">날짜를 누르면 그날부터 일정을 추가하고, 일정을 누르면 상세를 볼 수 있어요.</p>
        </div>
        {!loadFailed && (
          <div className="calendar-page__actions">
            <VoiceOrb ref={orbRef} label="말로 일정 추가하기" onClick={startVoice} />
          </div>
        )}
      </div>

      {loadFailed ? (
        <>
          {monthNav}
          <section className="mt-card calendar-page__error" role="alert" aria-labelledby={errorTitleId}>
            <span className="calendar-page__error-icon">
              <Icon name="alertCircle" size="l" />
            </span>
            <div className="calendar-page__error-text">
              <h2 className="screen-section" id={errorTitleId}>
                복용 달력을 불러오지 못했어요
              </h2>
              <p className="screen-body calendar-page__neutral">잠시 후 다시 시도해 주세요. 저장한 일정은 그대로 있어요.</p>
            </div>
            <button className="mt-btn mt-btn--solid calendar-page__retry" type="button" onClick={reload}>
              <Icon name="refresh" />
              다시 불러오기
            </button>
            <p className="screen-caption calendar-page__muted">로그인이 끝났다면 로그인 화면으로 이동해요.</p>
          </section>
        </>
      ) : (
        <>
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
              <b>복약 알림이 꺼져 있어요.</b> 켜면 복용 시간마다 크롬으로 알려 드려요.
            </Notice>
          )}

          {current && current.schedules.length === 0 && (
            <div className="mt-notice calendar-page__empty">
              <span className="calendar-page__empty-icon">
                <Icon name="calendar" size="l" />
              </span>
              <div className="mt-notice__body">
                <p className="screen-item">아직 일정이 없어요</p>
                <p className="screen-body calendar-page__neutral">약을 먹기 시작할 날짜를 눌러 첫 일정을 추가해 보세요.</p>
              </div>
            </div>
          )}

          <div className="calendar-page__cols">
            <section className="calendar-page__content" aria-labelledby={monthTitleId}>
              {monthNav}
              <CalendarMonth
                month={month}
                today={today}
                schedules={schedules}
                profiles={profiles}
                selectedKey={selectedKey}
                newKey={newKey}
                barLink={(schedule) => calendarSearch(month, schedule)}
                onBarClick={() => setBarClicked(true)}
              />
            </section>
            {panel}
          </div>
        </>
      )}

      {conversation && (
        <VoiceScheduleDialog
          key={voiceRun}
          conversation={conversation}
          profiles={profiles}
          onClose={closeVoice}
          onRestart={startVoice}
          onShowSchedule={showVoiceSchedule}
        />
      )}
    </>
  )
}

export default CalendarPage
