import { useEffect, useId, useRef, useState } from 'react'
import { flushSync } from 'react-dom'
import { errorOf } from '../api/client.js'
import { changeNotificationSettings, getNotificationSettings } from '../api/notifications.js'
import ConfirmDialog from '../components/ConfirmDialog.jsx'
import Icon from '../components/Icon.jsx'
import Notice from '../components/Notice.jsx'
import { hasPushSubscription, pushSupported, subscribePush, unsubscribePush } from '../push.js'
import './NotificationsPage.css'

// 켜기 · 끄기에 실패했을 때의 문구. 동의창이 열려 있으면 창 안에, 아니면 카드 안에 보인다.
const errorTexts = {
  dismissed: '알림을 허용하지 않아 켜지 못했어요. 다시 누르고 크롬이 물으면 ‘허용’을 눌러 주세요.',
  consent: '알림 수신 동의가 다시 필요해요. 내용을 확인하고 ‘동의하고 켜기’를 눌러 주세요.',
  on: '알림을 켜지 못했어요. 잠시 후 다시 시도해 주세요.',
  off: '알림을 끄지 못했어요. 잠시 후 다시 시도해 주세요.',
}

// NTF-01 알림. 이 크롬의 복약 알림을 켜고 끈다(알림 설정 GET · PUT, 이 크롬의 웹 푸시 구독은 push.js).
// 켜짐은 서버에서 켜져 있고 이 크롬에 구독과 알림 권한이 있을 때다. 다른 컴퓨터에서만 켰으면 이 크롬은 꺼짐이다.
// 동의가 없으면 알림 수신 동의창을 먼저 열고, 크롬이 알림을 막아 두었으면 푸는 방법을 보여 준다.
function NotificationsPage() {
  const supported = pushSupported()
  const titleId = useId()
  const statusId = useId()
  const headingRef = useRef(null)
  const blockedRef = useRef(null)
  const [attempt, setAttempt] = useState(0)
  const [loaded, setLoaded] = useState(null)
  // 이 크롬의 알림 권한. 크롬 설정에서 바꾸면 "알림 켜기"를 누를 때 다시 읽는다.
  const [permission, setPermission] = useState(() => (supported ? Notification.permission : 'default'))
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState(null)
  const [consentOpen, setConsentOpen] = useState(false)

  useEffect(() => {
    let active = true
    Promise.all([getNotificationSettings(), supported ? hasPushSubscription().catch(() => false) : false])
      .then(([settings, subscribed]) => active && setLoaded({ key: attempt, settings, subscribed }))
      .catch(() => active && setLoaded({ key: attempt, failed: true }))
    return () => {
      active = false
    }
  }, [attempt, supported])

  const current = loaded?.key === attempt ? loaded : null

  // 크롬이 막아 두었으면 푸는 방법으로 포커스를 옮긴다.
  const showBlocked = () => {
    flushSync(() => {
      setPermission('denied')
      setConsentOpen(false)
      setBusy(false)
    })
    blockedRef.current.focus()
  }

  // 크롬 알림 권한 → 이 크롬 구독 저장 → 꺼져 있거나 동의가 없으면 켜기 저장(동의는 동의창에서 받았다).
  const turnOn = async () => {
    const { settings } = current
    setBusy(true)
    setError(null)
    const result = await Notification.requestPermission().catch(() => 'default')
    if (result === 'denied') {
      showBlocked()
      return
    }
    setPermission(result)
    if (result !== 'granted') {
      setBusy(false)
      setError('dismissed')
      return
    }
    try {
      await subscribePush()
      if (!settings.pushEnabled || !settings.consentActive) {
        await changeNotificationSettings({
          pushEnabled: true,
          notificationConsent: settings.consentActive
            ? undefined
            : { agreed: true, policyVersion: settings.consentPolicyVersion },
        })
      }
    } catch (failure) {
      // 그 사이 동의가 철회됐거나 약관이 바뀌었으면(409) 새 약관으로 동의창을 다시 연다.
      const latest =
        errorOf(failure).code === 'CONSENT_REQUIRED' ? await getNotificationSettings().catch(() => null) : null
      setBusy(false)
      if (latest) {
        setLoaded((previous) => ({ ...previous, settings: latest }))
        setConsentOpen(true)
        setError('consent')
      } else {
        setError('on')
      }
      return
    }
    // 서버 구독은 방금 하나 이상 생겼고, 동의는 이미 있었거나 방금 받았다.
    flushSync(() => {
      setLoaded((previous) => ({
        ...previous,
        subscribed: true,
        settings: {
          ...previous.settings,
          pushEnabled: true,
          consentActive: true,
          subscriptionCount: Math.max(previous.settings.subscriptionCount, 1),
        },
      }))
      setConsentOpen(false)
      setBusy(false)
    })
    headingRef.current.focus()
  }

  // "알림 켜기": 크롬이 막아 두었으면 푸는 방법으로, 동의가 없으면 동의창으로, 있으면 바로 켠다.
  const start = () => {
    setError(null)
    if (Notification.permission === 'denied') showBlocked()
    else if (current.settings.consentActive) turnOn()
    else setConsentOpen(true)
  }

  // 끄기는 서버가 동의를 철회하고 이 회원의 모든 브라우저 구독을 지운다. 이 크롬의 구독은 지우지 못해도 알림이 오지 않는다.
  const turnOff = async () => {
    setBusy(true)
    setError(null)
    try {
      await changeNotificationSettings({ pushEnabled: false })
    } catch {
      setBusy(false)
      setError('off')
      return
    }
    await unsubscribePush().catch(() => {})
    flushSync(() => {
      setLoaded((previous) => ({
        ...previous,
        subscribed: false,
        settings: { ...previous.settings, pushEnabled: false, consentActive: false, subscriptionCount: 0 },
      }))
      setBusy(false)
    })
    headingRef.current.focus()
  }

  // "동의 안 함" · Esc: 아무것도 보내지 않고 닫는다. 포커스는 브라우저가 "알림 켜기"로 돌려준다.
  const closeConsent = () => {
    setConsentOpen(false)
    setError(null)
  }

  const head = (
    <div className="notifications-page__head">
      <h1 className="screen-title">알림</h1>
      <p className="screen-body notifications-page__neutral">
        복약 알림을 켜고 꺼요. 켜는 건 지금 쓰는 크롬에만 적용돼요.
      </p>
    </div>
  )

  if (current?.failed) {
    return (
      <div className="notifications-page">
        {head}
        <Notice
          variant="negative"
          role="alert"
          action={
            <button className="mt-btn mt-btn--assistive mt-btn--md" type="button" onClick={() => setAttempt((count) => count + 1)}>
              다시 시도
            </button>
          }
        >
          알림 설정을 불러오지 못했어요. 잠시 후 다시 시도해 주세요.
        </Notice>
      </div>
    )
  }
  if (!current) return <div className="notifications-page">{head}</div>

  const { settings, subscribed } = current
  const on = settings.pushEnabled && settings.subscriptionCount > 0 && subscribed && permission === 'granted'
  const blocked = supported && !on && permission === 'denied'
  const errorText = errorTexts[error]

  return (
    <div className="notifications-page">
      {head}
      <section className="mt-card mt-card--wide notifications-page__card" aria-labelledby={titleId}>
        <span className={on ? 'notifications-page__state notifications-page__state--on' : 'notifications-page__state'}>
          <Icon name={on ? 'bell' : 'bellOff'} size="l" />
        </span>
        <div className="notifications-page__body">
          <div className="notifications-page__title">
            <h2 className="screen-item" id={titleId} ref={headingRef} tabIndex={-1} aria-describedby={statusId}>
              복약 알림
            </h2>
            {on ? (
              <span className="mt-badge mt-badge--primary" id={statusId}>
                <Icon name="check" size="s" />
                켜짐
              </span>
            ) : (
              <span className="mt-badge" id={statusId}>
                꺼짐
              </span>
            )}
          </div>
          <p className="screen-body notifications-page__neutral">
            {on ? '복용 시간마다 크롬으로 알려 드려요.' : '켜면 복용 시간마다 크롬으로 알려 드려요.'}
          </p>
          {!supported && (
            <Notice variant="caution">이 브라우저에서는 복약 알림을 켤 수 없어요. 컴퓨터의 크롬에서 열어 주세요.</Notice>
          )}
          {blocked && (
            <div className="mt-notice mt-notice--caution notifications-page__blocked" ref={blockedRef} tabIndex={-1}>
              <Icon name="alertTriangle" className="mt-notice__icon" />
              <div className="mt-notice__body">
                <p className="mt-notice__text">
                  <b>크롬에서 필받음 알림을 막아 두었어요.</b> 크롬 설정에서 알림을 '허용'으로 바꿔야 켤 수 있어요.
                </p>
                <ol className="notifications-page__steps">
                  <li>화면 맨 위 주소 칸 왼쪽의 작은 그림을 눌러요.</li>
                  <li>알림을 ‘허용’으로 바꿔요.</li>
                  <li>이 화면으로 돌아와 ‘알림 켜기’를 다시 눌러 주세요.</li>
                </ol>
              </div>
            </div>
          )}
          {errorText && !consentOpen && (
            <Notice variant="negative" role="alert">
              {errorText}
            </Notice>
          )}
          <div className="notifications-page__actions">
            {on ? (
              <button className="mt-btn mt-btn--assistive" type="button" disabled={busy} onClick={turnOff}>
                <Icon name={busy ? 'loader' : 'bellOff'} className={busy ? 'icon--spin' : undefined} />
                {busy ? '끄는 중…' : '알림 끄기'}
              </button>
            ) : (
              <button
                className="mt-btn mt-btn--solid"
                type="button"
                disabled={busy || !supported}
                aria-haspopup={settings.consentActive || blocked ? undefined : 'dialog'}
                onClick={start}
              >
                <Icon name={busy ? 'loader' : 'bell'} className={busy ? 'icon--spin' : undefined} />
                {busy ? '켜는 중…' : '알림 켜기'}
              </button>
            )}
          </div>
          {on && (
            <p className="screen-caption notifications-page__muted">알림을 끄면 다른 컴퓨터에서도 복약 알림이 모두 꺼져요.</p>
          )}
        </div>
      </section>
      <Notice>알림은 컴퓨터마다 따로 켜야 해요. 다른 컴퓨터에서도 받으려면 그 컴퓨터에서도 알림을 켜 주세요.</Notice>
      {consentOpen && (
        <ConfirmDialog
          title="복약 알림 수신 동의"
          cancelLabel="동의 안 함"
          confirmLabel="동의하고 켜기"
          confirmDisabled={busy}
          error={errorText}
          onConfirm={turnOn}
          onClose={closeConsent}
        >
          <div className="notifications-page__consent">
            <ul className="notifications-page__terms">
              <li>
                <Icon name="clock" />
                <p>복용 시간마다 크롬으로 복약 알림을 보내 드려요.</p>
              </li>
              <li>
                <Icon name="sliders" />
                <p>알림은 설정 › 알림에서 언제든 끌 수 있어요.</p>
              </li>
              <li>
                <Icon name="info" />
                <p>동의하면 크롬이 '알림을 받을까요?' 하고 한 번 더 물어봐요. '허용'을 눌러 주세요.</p>
              </li>
            </ul>
            <p className="screen-caption notifications-page__muted">동의하지 않아도 다른 기능은 그대로 쓸 수 있어요.</p>
          </div>
        </ConfirmDialog>
      )}
    </div>
  )
}

export default NotificationsPage
