import { Fragment, useEffect, useId, useRef, useState } from 'react'
import { flushSync } from 'react-dom'
import { Link, Navigate, useNavigate, useSearchParams } from 'react-router'
import { errorOf } from '../api/client.js'
import { registerDrugs, searchDrugs } from '../api/drugs.js'
import DrugThumb from '../components/DrugThumb.jsx'
import FieldError from '../components/FieldError.jsx'
import GuideFailed from '../components/GuideFailed.jsx'
import Icon from '../components/Icon.jsx'
import NoValue from '../components/NoValue.jsx'
import Notice from '../components/Notice.jsx'
import ProfileDot from '../components/ProfileDot.jsx'
import { useDrugGuide } from '../components/useDrugGuide.js'
import { useManagedProfile } from '../components/useManagedProfile.js'
import { guideProvider, guideSections } from '../drugInfo.js'
import { paths } from '../paths.js'
import { monthDayTime } from '../text.js'
import './DrugSearchPage.css'

// 서버 검색 규칙: 앞뒤 공백을 뺀 2~50자, 한 번에 최대 20개
const minLength = 2
const maxLength = 50
const pageSize = 20

// 결과 없음 예시: 첫 띄어쓰기나 숫자 앞까지 줄인 이름('타이레놀정 650' → '타이레놀정')
function shorterQuery(query) {
  const head = query.split(/[\s\d]/)[0]
  return head.length >= minLength && head !== query ? head : null
}

// MED-03 약 이름 검색. 검색어는 주소 쿼리(?q=)에 두어 새로고침 · 뒤로 가기에도 남는다.
// 후보를 고르면 약 정보를 서버에 맞춘 뒤(GET /api/drugs/{itemSeq}) 공식 정보를 보여 주고,
// 저장은 복약 일정 없이 고른 약만 보낸다(POST .../drugs 의 items 방식, source SEARCH).
function DrugSearchPage() {
  const navigate = useNavigate()
  const [searchParams, setSearchParams] = useSearchParams()
  const query = (searchParams.get('q') ?? '').trim()
  const { status, profile, color, retry: retryProfiles } = useManagedProfile()
  const profileId = profile?.profileId
  const fieldId = useId()
  const resultsId = useId()
  const chosenId = useId()
  const inputRef = useRef(null)
  const resultsTitleRef = useRef(null)
  const chosenTitleRef = useRef(null)
  const [text, setText] = useState(query)
  const [shownQuery, setShownQuery] = useState(query)
  const [tooShort, setTooShort] = useState(false)
  const [attempt, setAttempt] = useState(0)
  // 검색 결과 · 고른 약은 어느 검색의 것인지(key) 함께 적어 두고, 검색이 바뀌면 쓰지 않는다.
  const [result, setResult] = useState(null)
  const [moreState, setMoreState] = useState(null)
  const [chosen, setChosen] = useState(null)
  const [saving, setSaving] = useState(false)
  const [saveError, setSaveError] = useState(null)

  // 뒤로 · 앞으로 가기로 검색어가 바뀌면 입력칸도 따라간다.
  if (query !== shownQuery) {
    setShownQuery(query)
    setText(query)
  }

  const key = `${profileId}|${query}|${attempt}`
  const searching = Boolean(profileId) && query.length >= minLength

  useEffect(() => {
    if (!profileId || query.length < minLength) return
    let active = true
    searchDrugs({ name: query, profileId, page: 1, size: pageSize })
      .then((data) => active && setResult({ key, items: data.content, page: 1, last: data.last }))
      .catch(() => active && setResult({ key, failed: true }))
    return () => {
      active = false
    }
  }, [profileId, query, attempt, key])

  const current = searching && result?.key === key ? result : null
  const loading = searching && !current
  const pick = chosen?.key === key ? current?.items?.find((item) => item.itemSeq === chosen.itemSeq) : null
  const { guide, failed: guideFailed, retry: retryGuide } = useDrugGuide(pick?.itemSeq, { sync: true })

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

  const name = profile.profileName

  const submit = (event) => {
    event.preventDefault()
    const next = text.trim()
    if (next.length < minLength) {
      setTooShort(true)
      inputRef.current.focus()
      return
    }
    setTooShort(false)
    setSaveError(null)
    // 같은 검색어로 다시 누르면 주소는 그대로 두고 다시 검색한다.
    if (next === query) setAttempt((count) => count + 1)
    else setSearchParams({ q: next })
  }

  const loadMore = async () => {
    setMoreState({ key, loading: true })
    try {
      const data = await searchDrugs({ name: query, profileId, page: current.page + 1, size: pageSize })
      setResult((previous) => {
        if (previous?.key !== key) return previous
        const seen = new Set(previous.items.map((item) => item.itemSeq))
        return { ...previous, items: [...previous.items, ...data.content.filter((item) => !seen.has(item.itemSeq))], page: previous.page + 1, last: data.last }
      })
      setMoreState(null)
    } catch {
      setMoreState({ key, failed: true })
    }
  }

  // 고르면 오른쪽(좁은 화면은 아래) 상세의 제목으로 포커스를 옮긴다.
  const choose = (item) => {
    flushSync(() => {
      setChosen({ key, itemSeq: item.itemSeq })
      setSaveError(null)
    })
    chosenTitleRef.current?.focus()
  }

  const chooseAnother = () => {
    flushSync(() => {
      setChosen(null)
      setSaveError(null)
    })
    resultsTitleRef.current?.focus()
  }

  // 다른 곳에서 먼저 등록된 약이면 목록에서도 "이미 등록한 약"으로 바꾼다.
  const markRegistered = (itemSeq) =>
    setResult((previous) =>
      previous?.items ? { ...previous, items: previous.items.map((item) => (item.itemSeq === itemSeq ? { ...item, registered: true } : item)) } : previous,
    )

  const save = async () => {
    setSaving(true)
    setSaveError(null)
    try {
      // 응답 saved 가 비어 있으면(200) 이미 등록돼 건너뛴 약이다.
      const response = await registerDrugs(profileId, { source: 'SEARCH', items: [{ itemSeq: pick.itemSeq }] })
      if (response.saved?.length) {
        navigate(paths.drugs, { state: { profileId, saved: response.saved } })
        return
      }
      markRegistered(pick.itemSeq)
    } catch (error) {
      if (errorOf(error).status === 409) markRegistered(pick.itemSeq)
      else setSaveError('failed')
    }
    setSaving(false)
  }

  const items = current?.items
  const more = moreState?.key === key ? moreState : null
  const sections = guide ? guideSections(guide) : []

  return (
    <div className="drug-search">
      <div className="drug-search__head">
        <Link className="mt-btn mt-btn--ghost drug-search__back" to={paths.drugNew}>
          <Icon name="chevronLeft" size="s" />
          등록 방법으로
        </Link>
        <h1 className="screen-title">이름으로 검색</h1>
        <p className="screen-body drug-search__who">
          <ProfileDot color={color} />
          <span>
            <b>{name}</b>의 약 목록에 넣어요
          </span>
        </p>
      </div>

      <section className="drug-search__form" aria-label="약 이름 검색">
        <form className="drug-search__row" role="search" noValidate onSubmit={submit}>
          <div className="mt-field drug-search__field">
            <label className="mt-field__label" htmlFor={`${fieldId}-q`}>
              약 이름
            </label>
            <input
              ref={inputRef}
              className="mt-input"
              id={`${fieldId}-q`}
              type="text"
              enterKeyHint="search"
              maxLength={maxLength}
              autoComplete="off"
              placeholder="약 이름(예: 타이레놀)으로 찾아요"
              value={text}
              onChange={(event) => {
                setText(event.target.value)
                setTooShort(false)
              }}
              aria-invalid={tooShort ? true : undefined}
              aria-describedby={`${fieldId}-hint`}
            />
          </div>
          <button className="mt-btn mt-btn--solid drug-search__submit" type="submit" disabled={loading}>
            <Icon name={loading ? 'loader' : 'search'} className={loading ? 'icon--spin' : undefined} />
            검색
          </button>
        </form>
        {tooShort ? (
          <FieldError id={`${fieldId}-hint`}>2글자 이상 입력해 주세요.</FieldError>
        ) : (
          <p className="mt-field__help" id={`${fieldId}-hint`}>
            2글자 이상 입력해 주세요.
          </p>
        )}
      </section>

      {current?.failed && (
        <Notice
          variant="negative"
          role="alert"
          action={
            <button className="mt-btn mt-btn--assistive mt-btn--md" type="button" onClick={() => setAttempt((count) => count + 1)}>
              다시 시도
            </button>
          }
        >
          검색하지 못했어요. 잠시 후 다시 검색해 주세요.
        </Notice>
      )}

      {items?.length === 0 && (
        <section className="mt-card drug-search__none" role="status" aria-labelledby={resultsId}>
          <span className="drug-search__none-icon">
            <Icon name="search" size="l" />
          </span>
          <div className="drug-search__none-text">
            <h2 className="screen-section" id={resultsId}>
              &apos;{query}&apos;에 맞는 약을 찾지 못했어요.
            </h2>
            <p className="screen-body drug-search__neutral">띄어쓰기나 함량을 빼고 다시 검색해 보세요.</p>
          </div>
          {shorterQuery(query) && (
            <div className="drug-search__example">
              <p className="screen-label drug-search__neutral">이렇게 바꿔 검색해 보세요</p>
              <p>
                &apos;{query}&apos; 대신 <b>{shorterQuery(query)}</b>
              </p>
            </div>
          )}
        </section>
      )}

      {items?.length > 0 && (
        <section className="drug-search__results" aria-labelledby={resultsId}>
          <h2 className="screen-section drug-search__results-title" id={resultsId} ref={resultsTitleRef} tabIndex={-1}>
            &apos;{query}&apos; 검색 결과
          </h2>
          <div className="drug-search__columns">
            <div className="mt-card drug-search__list-card">
              <ul>
                {items.map((item) => {
                  const picked = item.itemSeq === pick?.itemSeq
                  const classes = ['drug-search__item', item.registered && 'drug-search__item--registered', picked && 'drug-search__item--picked']
                  return (
                    <li
                      key={item.itemSeq}
                      className={classes.filter(Boolean).join(' ')}
                      aria-disabled={item.registered || undefined}
                      aria-current={picked || undefined}
                    >
                      <DrugThumb imageUrl={item.imageUrl} dim={item.registered} />
                      <div className="drug-search__item-text">
                        <p className="screen-item drug-search__item-name">{item.name}</p>
                        {item.maker && <p className="screen-caption drug-search__muted">{item.maker}</p>}
                      </div>
                      {item.registered && (
                        <span className="screen-caption drug-search__registered">
                          <Icon name="check" size="s" />
                          이미 등록한 약이에요
                        </span>
                      )}
                      {!item.registered && picked && (
                        <span className="mt-badge drug-search__picked-badge">
                          <Icon name="check" size="s" />
                          고른 약
                        </span>
                      )}
                      {!item.registered && !picked && (
                        <button
                          className="mt-btn mt-btn--assistive mt-btn--md"
                          type="button"
                          aria-label={`${item.maker ? `${item.maker} ` : ''}${item.name} 고르기`}
                          onClick={() => choose(item)}
                        >
                          고르기
                        </button>
                      )}
                    </li>
                  )
                })}
              </ul>
              {!current.last && (
                <div className="drug-search__more">
                  <button className="mt-btn mt-btn--assistive mt-btn--block" type="button" disabled={more?.loading} onClick={loadMore}>
                    더보기
                    <Icon name="chevronDown" size="s" />
                  </button>
                  {more?.failed && <FieldError>더 불러오지 못했어요. 잠시 후 다시 눌러 주세요.</FieldError>}
                </div>
              )}
              <p className="screen-caption drug-search__note">복용 일정은 저장 후 약 상세에서 등록해 주세요.</p>
            </div>

            {pick ? (
              <section className="mt-card drug-search__picked" aria-labelledby={chosenId}>
                <div className="drug-search__picked-head">
                  <DrugThumb imageUrl={pick.imageUrl} size="md" alt={`${pick.name} 공식 사진`} />
                  <div className="drug-search__picked-text">
                    <p className="screen-label drug-search__neutral">고른 약</p>
                    <h2 className="screen-section drug-search__picked-title" id={chosenId} ref={chosenTitleRef} tabIndex={-1}>
                      {pick.name}
                    </h2>
                    {pick.maker && <p className="screen-caption drug-search__muted">{pick.maker}</p>}
                  </div>
                </div>

                {guideFailed && <GuideFailed onRetry={retryGuide} />}
                {!guideFailed && !guide && (
                  <p className="screen-caption drug-search__muted" role="status">
                    공식 정보를 불러오고 있어요.
                  </p>
                )}
                {guide && (
                  <section className="drug-search__block" aria-labelledby={`${chosenId}-official`}>
                    <h3 className="screen-item drug-search__block-title" id={`${chosenId}-official`}>
                      <Icon name="book" />
                      공식 정보
                    </h3>
                    {sections.length > 0 ? (
                      <dl className="drug-search__kv">
                        {sections.map((section) => (
                          <Fragment key={section.key}>
                            <dt className="screen-label">{section.title}</dt>
                            <dd>{section.content}</dd>
                          </Fragment>
                        ))}
                      </dl>
                    ) : (
                      <p className="screen-body drug-search__neutral">공공데이터에서 이 약의 효능 · 사용법 · 주의사항을 찾지 못했어요.</p>
                    )}
                  </section>
                )}
                {(guide || guideFailed) && (
                  <section className="drug-search__block" aria-labelledby={`${chosenId}-source`}>
                    <h3 className="screen-label drug-search__neutral" id={`${chosenId}-source`}>
                      출처
                    </h3>
                    <dl className="drug-search__kv">
                      <dt className="screen-label">제공</dt>
                      <dd>{guideProvider}</dd>
                      <dt className="screen-label">서비스</dt>
                      <dd>{guide?.sourceName ?? <NoValue />}</dd>
                      <dt className="screen-label">품목기준코드</dt>
                      <dd>{pick.itemSeq}</dd>
                      <dt className="screen-label">조회 시각</dt>
                      <dd className="drug-search__number">{guide ? monthDayTime(guide.retrievedAt) : <NoValue />}</dd>
                    </dl>
                  </section>
                )}

                {pick.registered && <Notice variant="info">이미 등록한 약이에요.</Notice>}
                {saveError && (
                  <Notice variant="negative" role="alert">
                    <b>저장하지 못했어요.</b> 잠시 후 다시 저장해 주세요.
                  </Notice>
                )}
                <div className="drug-search__actions">
                  {!pick.registered && (
                    <button className="mt-btn mt-btn--solid" type="button" disabled={saving} onClick={save}>
                      {saving && <Icon name="loader" size="s" className="icon--spin" />}
                      {name}의 약 목록에 저장
                    </button>
                  )}
                  <button className="mt-btn mt-btn--assistive" type="button" onClick={chooseAnother}>
                    다른 약 고르기
                  </button>
                </div>
              </section>
            ) : (
              <aside className="mt-card drug-search__placeholder" aria-label="고른 약의 공식 정보">
                <span className="drug-search__placeholder-icon">
                  <Icon name="book" size="l" />
                </span>
                <p className="screen-item">후보를 고르면 공식 정보를 보여 드려요</p>
                <p className="screen-body drug-search__neutral">효능, 사용법, 주의사항과 출처를 확인한 뒤 저장할 수 있어요.</p>
              </aside>
            )}
          </div>
        </section>
      )}
    </div>
  )
}

export default DrugSearchPage
