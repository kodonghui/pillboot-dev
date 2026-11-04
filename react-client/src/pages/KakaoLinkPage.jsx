import { useCallback, useEffect, useId, useRef, useState } from 'react'
import { flushSync } from 'react-dom'
import { Link } from 'react-router'
import { errorOf } from '../api/client.js'
import { createKakaoLinkCode, deleteKakaoLink, getKakaoLinks } from '../api/kakaoLinks.js'
import ConfirmDialog from '../components/ConfirmDialog.jsx'
import FieldError from '../components/FieldError.jsx'
import Icon from '../components/Icon.jsx'
import Notice from '../components/Notice.jsx'
import '../components/VisuallyHidden.css'
import {
  codeLifetimeMs,
  lastUsedText,
  maxKakaoLinks,
  remainingText,
  spokenCode,
  viewedProfileName,
} from '../kakaoLinkInfo.js'
import { paths } from '../paths.js'
import { monthDayTime } from '../text.js'
import './KakaoLinkPage.css'

// 연결하기 전에 알리는 것(안내 판 KAKAO-LINK-2, 서버가 코드 · 연결에 남긴다)
const points = [
  '연결한 카카오톡은 이 계정의 모든 프로필(엄마·아빠 …)의 약목록·복약 일정·출생연도·성별·임신 여부를 볼 수 있어요. 카톡에서 ‘프로필 바꾸기’로 골라요.',
  '가족 본인의 카카오톡에 이으면 그 가족도 이 계정의 모든 프로필 약을 볼 수 있어요.',
  '카톡에서 한 상담은 이 계정의 상담 기록에 남아, 이 계정으로 로그인한 사람은 누구나 볼 수 있어요.',
  '카톡 대화는 카카오 서버를 거쳐요. 휴대폰을 같이 쓰는 사람이 카톡에서 약 이름을 볼 수 있어요.',
  '카톡에서 확정한 약 등록·일정 바꾸기는 그 프로필에 바로 저장돼요.',
  '복용 시각에 지금 고른 프로필의 약 이름이 카톡으로도 와요. 카톡에서 ‘알림 끄기’로 끌 수 있고, 웹 푸시 알림은 따로 와요.',
  '언제든 이 화면이나 카톡 ‘연결 끊기’로 끊을 수 있어요. 끊어도 약목록·상담 기록은 지워지지 않아요.',
]

// 코드가 보이는 동안 연결 목록을 다시 읽는 간격과 '복사했어요'를 보이는 시간
const pollMs = 5000
const copiedMs = 2000
// 남은 시간을 한 번 읽어 주는 때(1분 남음)
const warnMs = 60 * 1000

const codeErrors = {
  rate: '코드를 너무 자주 받았어요. 10분 뒤 다시 받아 주세요.',
  failed: '코드를 받지 못했어요. 잠시 뒤 다시 눌러 주세요.',
}

// 클립보드 API를 막은 브라우저(앱 안 브라우저 · 권한 거부)에서는 보이지 않는 입력칸을 골라 예전 방식으로 복사한다.
function copyByCommand(text) {
  const area = document.createElement('textarea')
  area.value = text
  area.readOnly = true
  area.className = 'visually-hidden'
  document.body.appendChild(area)
  area.select()
  const copied = document.execCommand('copy')
  area.remove()
  if (!copied) throw new Error('copy failed')
}

async function copyText(text) {
  try {
    await navigator.clipboard.writeText(text)
  } catch {
    copyByCommand(text)
  }
}

// PRF-06 카카오톡 연결(v3-kakao-web-link.md, V3-KAKAO-012 · 015). 계정 단위 화면이라 관리할 사람(PRF-04)은 숨긴다.
// 코드는 이 화면의 상태에만 두고 주소 · 저장소 · 로그에 넣지 않는다. 새로 고치면 코드는 다시 보이지 않는다(서버도 다시 주지 않는다).
function KakaoLinkPage() {
  const titleRef = useRef(null)
  const listRef = useRef(null)
  const noticeTitleId = useId()
  const listTitleId = useId()
  const hintId = useId()
  const renewId = useId()
  const codeErrorId = useId()

  const [links, setLinks] = useState(null)
  const [listFailed, setListFailed] = useState(false)
  const [agreed, setAgreed] = useState(false)
  // 받은 코드와 이 브라우저 시계로 잰 마감 시각(ms). 시간이 지나면 expired 가 된다.
  const [issued, setIssued] = useState(null)
  const [expired, setExpired] = useState(false)
  const [now, setNow] = useState(() => Date.now())
  const [requesting, setRequesting] = useState(false)
  const [codeError, setCodeError] = useState(null)
  // 코드를 받다가 409 KAKAO_LINK_LIMIT 를 받음(목록을 다시 읽기 전에도 5개 안내를 보인다)
  const [limitHit, setLimitHit] = useState(false)
  const [copied, setCopied] = useState(false)
  // 화면 안에 남는 완료 안내('카카오톡과 연결됐어요.' · '연결을 끊었어요.')와 화면 읽기 알림
  const [done, setDone] = useState(null)
  const [announce, setAnnounce] = useState('')
  // 끊기 확인창: { link, number }
  const [target, setTarget] = useState(null)
  const [unlinking, setUnlinking] = useState(false)
  const [unlinkError, setUnlinkError] = useState(false)

  const alive = useRef(true)
  // 코드가 보이는 동안 true. 코드를 받을 때 있던 연결 번호(known)와 다시 읽은 목록을 견주어 새 연결을 찾는다.
  const watching = useRef(false)
  const known = useRef(null)
  const warned = useRef(false)
  const copiedTimer = useRef(null)
  // 목록 읽기 차례. 늦게 온 앞 읽기의 답과 끊기 전에 시작한 읽기의 답(끊은 줄이 되살아남)은 버린다.
  const readSeq = useRef(0)
  const appliedSeq = useRef(0)
  const staleUpTo = useRef(0)

  useEffect(() => {
    alive.current = true
    return () => {
      alive.current = false
      watching.current = false
      clearTimeout(copiedTimer.current)
    }
  }, [])

  // 연결 목록을 읽는다. quiet: 다시 읽기(실패해도 오류를 보이지 않고 전 목록을 둔다)
  const load = useCallback(async (quiet) => {
    const seq = ++readSeq.current
    const fresh = () => alive.current && seq > appliedSeq.current && seq > staleUpTo.current
    let data
    try {
      data = await getKakaoLinks()
    } catch {
      if (fresh() && !quiet) setListFailed(true)
      return
    }
    if (!fresh()) return
    appliedSeq.current = seq
    setLinks(data)
    setListFailed(false)
    if (data.length < maxKakaoLinks) setLimitHit(false)
    // 코드는 목록을 읽은 뒤에만 받으므로 견줄 연결 번호(known)가 늘 있다.
    if (!watching.current || known.current === null) return
    const ids = data.map((link) => link.linkId)
    if (ids.some((id) => !known.current.includes(id))) {
      watching.current = false
      known.current = null
      setIssued(null)
      setExpired(false)
      setCodeError(null)
      setDone('카카오톡과 연결됐어요.')
      setAnnounce('카카오톡과 연결됐어요.')
    }
  }, [])

  // 처음 읽기. 코드가 아직 없으니 새 연결을 견줄 것이 없다.
  useEffect(() => {
    let active = true
    const seq = ++readSeq.current
    getKakaoLinks()
      .then((data) => {
        if (!active || seq <= appliedSeq.current || seq <= staleUpTo.current) return
        appliedSeq.current = seq
        setLinks(data)
      })
      .catch(() => active && seq > appliedSeq.current && setListFailed(true))
    return () => {
      active = false
    }
  }, [])

  // 다른 창에 갔다 돌아오면 한 번 다시 읽는다(카톡에서 끊었거나 연결했을 수 있다).
  useEffect(() => {
    const onVisible = () => {
      if (document.visibilityState === 'visible') load(true)
    }
    document.addEventListener('visibilitychange', onVisible)
    return () => document.removeEventListener('visibilitychange', onVisible)
  }, [load])

  // 코드가 보이는 동안: 초마다 남은 시간을 고치고, 5초마다 목록을 다시 읽는다. 시간이 지나거나 연결되거나 화면을 떠나면 멈춘다.
  useEffect(() => {
    if (!issued || expired) return undefined
    const tick = setInterval(() => {
      const current = Date.now()
      setNow(current)
      if (current >= issued.deadline) {
        watching.current = false
        setExpired(true)
        setAnnounce('코드 시간이 지났어요. 새 코드를 받아 주세요.')
      } else if (!warned.current && issued.deadline - current <= warnMs) {
        warned.current = true
        setAnnounce('코드 시간이 1분 남았어요.')
      }
    }, 1000)
    const poll = setInterval(() => load(true), pollMs)
    return () => {
      clearInterval(tick)
      clearInterval(poll)
    }
  }, [issued, expired, load])

  const retryList = () => {
    setListFailed(false)
    load(false)
  }

  const requestCode = async () => {
    if (links === null) return
    setRequesting(true)
    setCodeError(null)
    setDone(null)
    let data
    try {
      data = await createKakaoLinkCode()
    } catch (error) {
      if (!alive.current) return
      const { status, code } = errorOf(error)
      setRequesting(false)
      if (status === 409 && code === 'KAKAO_LINK_LIMIT') {
        watching.current = false
        setIssued(null)
        setExpired(false)
        setLimitHit(true)
        load(true)
        return
      }
      setCodeError(status === 429 ? 'rate' : 'failed')
      return
    }
    if (!alive.current) return
    // 남은 시간은 서버가 준 마감 시각으로 재되, 이 기기 시계가 늦어도 10분을 넘기지 않는다.
    const received = Date.now()
    const serverLeft = Date.parse(data.expiresAt) - received
    const left = Number.isFinite(serverLeft) ? Math.min(Math.max(serverLeft, 0), codeLifetimeMs) : codeLifetimeMs
    known.current = links.map((link) => link.linkId)
    watching.current = true
    warned.current = false
    clearTimeout(copiedTimer.current)
    setIssued({ code: data.code, deadline: received + left })
    setNow(received)
    setExpired(false)
    setCopied(false)
    setRequesting(false)
    setAnnounce('연결 코드를 받았어요.')
  }

  const copy = async () => {
    try {
      await copyText(issued.code)
    } catch {
      return
    }
    if (!alive.current) return
    setCopied(true)
    setAnnounce('복사했어요.')
    clearTimeout(copiedTimer.current)
    copiedTimer.current = setTimeout(() => alive.current && setCopied(false), copiedMs)
  }

  const openUnlink = (link, number) => {
    setUnlinkError(false)
    setDone(null)
    setTarget({ link, number })
  }

  const closeUnlink = () => {
    setTarget(null)
    setUnlinkError(false)
  }

  // 지운 줄의 '끊기'로는 포커스를 돌려줄 수 없어, 남은 목록(없으면 화면 제목)으로 옮긴다.
  const focusAfterUnlink = () => {
    ;(listRef.current ?? titleRef.current)?.focus()
  }

  const unlink = async () => {
    const { link } = target
    setUnlinking(true)
    setUnlinkError(false)
    try {
      await deleteKakaoLink(link.linkId)
    } catch (error) {
      if (!alive.current) return
      if (errorOf(error).status === 404) {
        // 이미 끊겼거나 이 계정의 연결이 아니다. 목록을 다시 읽어 지금 상태를 보인다.
        flushSync(() => {
          setTarget(null)
          setUnlinking(false)
        })
        focusAfterUnlink()
        load(false)
        return
      }
      setUnlinkError(true)
      setUnlinking(false)
      return
    }
    if (!alive.current) return
    staleUpTo.current = readSeq.current
    flushSync(() => {
      setLinks((current) => current.filter((item) => item.linkId !== link.linkId))
      setLimitHit(false)
      setTarget(null)
      setUnlinking(false)
      setDone('연결을 끊었어요.')
    })
    setAnnounce('연결을 끊었어요.')
    focusAfterUnlink()
  }

  const loading = links === null && !listFailed
  // 목록을 한 번도 읽지 못함: 코드를 받을 때 있던 연결을 몰라 새 연결을 알아볼 수 없어 코드를 받지 않는다.
  const noBaseline = links === null
  const full = (links?.length ?? 0) >= maxKakaoLinks || limitHit
  const seconds = issued ? Math.max(0, Math.ceil((issued.deadline - now) / 1000)) : 0
  const codeErrorLine = codeError && <FieldError id={codeErrorId}>{codeErrors[codeError]}</FieldError>
  const describedBy = (id) => [id, codeError && codeErrorId].filter(Boolean).join(' ') || undefined

  let action
  if (full) {
    action = (
      <div className="kakao-link__action">
        <Notice variant="info">카카오톡은 {maxKakaoLinks}개까지 연결할 수 있어요. 하나를 끊은 뒤 다시 받아 주세요.</Notice>
      </div>
    )
  } else if (issued && !expired) {
    action = (
      <div className="kakao-link__action">
        <div className="kakao-link__code-box">
          <p className="screen-hero kakao-link__code">
            <span className="visually-hidden">{spokenCode(issued.code)}</span>
            <span aria-hidden="true">{issued.code}</span>
          </p>
          <p className="screen-label kakao-link__timer">
            <Icon name="clock" size="s" />
            {remainingText(seconds)}
          </p>
          <button className="mt-btn mt-btn--solid" type="button" onClick={copy}>
            {copied && <Icon name="check" size="s" />}
            {copied ? '복사했어요' : '코드 복사'}
          </button>
        </div>
        <ol className="screen-body kakao-link__steps">
          <li>카카오톡에서 ‘약봉이’ 채널 대화방을 열어요</li>
          <li>이 코드를 그대로 보내요</li>
        </ol>
        <Notice variant="caution">코드는 10분 동안 한 번만 쓸 수 있어요. 다른 사람에게 알려 주지 마세요.</Notice>
        <div className="kakao-link__renew">
          <p className="screen-caption kakao-link__hint" id={renewId}>
            {agreed ? '새 코드를 받으면 지금 코드는 못 써요.' : '확인란을 골라야 코드를 받을 수 있어요.'}
          </p>
          <button
            className="mt-btn mt-btn--assistive"
            type="button"
            disabled={!agreed || requesting}
            aria-describedby={describedBy(renewId)}
            onClick={requestCode}
          >
            <Icon name={requesting ? 'loader' : 'refresh'} size="s" className={requesting ? 'icon--spin' : undefined} />
            새 코드 받기
          </button>
          {codeErrorLine}
        </div>
      </div>
    )
  } else if (issued && expired) {
    action = (
      <div className="kakao-link__action">
        <div className="kakao-link__code-box">
          <p className="screen-item kakao-link__expired">
            <Icon name="clock" />
            코드 시간이 지났어요.
          </p>
        </div>
        {!agreed && (
          <p className="screen-caption kakao-link__hint" id={hintId}>
            확인란을 골라야 코드를 받을 수 있어요.
          </p>
        )}
        <button
          className="mt-btn mt-btn--solid"
          type="button"
          disabled={!agreed || requesting}
          aria-describedby={describedBy(!agreed && hintId)}
          onClick={requestCode}
        >
          <Icon name={requesting ? 'loader' : 'refresh'} size="s" className={requesting ? 'icon--spin' : undefined} />
          새 코드 받기
        </button>
        {codeErrorLine}
      </div>
    )
  } else {
    action = (
      <div className="kakao-link__action">
        {loading ? (
          <p className="screen-caption kakao-link__hint kakao-link__busy" role="status">
            <Icon name="loader" size="s" className="icon--spin" />
            연결 상태를 불러오는 중이에요.
          </p>
        ) : noBaseline ? (
          <p className="screen-caption kakao-link__hint" id={hintId}>
            연결 상태를 불러온 뒤 코드를 받을 수 있어요.
          </p>
        ) : (
          !agreed && (
            <p className="screen-caption kakao-link__hint" id={hintId}>
              확인란을 골라야 코드를 받을 수 있어요.
            </p>
          )
        )}
        <button
          className="mt-btn mt-btn--solid"
          type="button"
          disabled={!agreed || requesting || noBaseline}
          aria-describedby={describedBy(!loading && (noBaseline || !agreed) && hintId)}
          onClick={requestCode}
        >
          {requesting && <Icon name="loader" size="s" className="icon--spin" />}
          연결 코드 받기
        </button>
        {codeErrorLine}
      </div>
    )
  }

  return (
    <div className="kakao-link-page">
      <div className="kakao-link-page__head">
        <Link className="mt-btn mt-btn--ghost kakao-link-page__back" to={paths.profiles}>
          <Icon name="chevronLeft" size="s" />
          프로필 관리로
        </Link>
        <h1 className="screen-title kakao-link-page__title" ref={titleRef} tabIndex={-1}>
          카카오톡 연결
        </h1>
      </div>
      <p className="visually-hidden" role="status">
        {announce}
      </p>
      {done && <Notice variant="positive">{done}</Notice>}
      <section className="mt-card kakao-link__card" aria-labelledby={noticeTitleId}>
        <h2 className="screen-item" id={noticeTitleId}>
          카카오톡에서도 이 계정의 프로필 약으로 답해 드려요
        </h2>
        <ul className="screen-body kakao-link__points">
          {points.map((point) => (
            <li key={point}>{point}</li>
          ))}
        </ul>
        {!full && (
          <div className="kakao-link__agree">
            <label className="kakao-link__check">
              <input type="checkbox" checked={agreed} onChange={(event) => setAgreed(event.target.checked)} />
              위 내용을 확인했어요
            </label>
          </div>
        )}
        {action}
      </section>
      {listFailed && (
        <Notice
          variant="negative"
          role="alert"
          action={
            <button className="mt-btn mt-btn--assistive mt-btn--md" type="button" onClick={retryList}>
              다시 시도
            </button>
          }
        >
          연결 상태를 불러오지 못했어요.
        </Notice>
      )}
      {links?.length > 0 && (
        <section ref={listRef} className="mt-card kakao-link__card" aria-labelledby={listTitleId} tabIndex={-1}>
          <h2 className="screen-item" id={listTitleId}>
            연결된 카카오톡
          </h2>
          <ul className="kakao-link__rows">
            {links.map((link, index) => {
              const name = `카카오톡 ${index + 1}`
              return (
                <li className="kakao-link__row" key={link.linkId}>
                  <div className="kakao-link__row-text">
                    <p className="screen-body kakao-link__row-name">{name}</p>
                    <p className="screen-caption kakao-link__muted">
                      <span className="kakao-link__nowrap">{monthDayTime(link.linkedAt)} 연결</span> ·{' '}
                      <span className="kakao-link__nowrap">
                        {link.lastUsedAt ? `마지막 사용 ${lastUsedText(link.lastUsedAt)}` : '아직 사용 안 함'}
                      </span>
                    </p>
                    <p className="screen-caption kakao-link__neutral">지금 보는 프로필: {viewedProfileName(link)}</p>
                  </div>
                  <button
                    className="mt-btn mt-btn--danger-outlined mt-btn--md"
                    type="button"
                    aria-haspopup="dialog"
                    aria-label={`${name} 연결 끊기`}
                    onClick={() => openUnlink(link, index + 1)}
                  >
                    끊기
                  </button>
                </li>
              )
            })}
          </ul>
        </section>
      )}
      {target && (
        <ConfirmDialog
          title={`카카오톡 ${target.number} 연결을 끊을까요?`}
          description="이 카카오톡 약봉이는 이 계정의 약을 더 쓰지 않아요. 약목록과 상담 기록은 지워지지 않아요."
          danger
          confirmLabel="끊기"
          busy={unlinking}
          error={unlinkError ? '끊지 못했어요. 다시 눌러 주세요.' : null}
          onConfirm={unlink}
          onClose={closeUnlink}
        />
      )}
    </div>
  )
}

export default KakaoLinkPage
