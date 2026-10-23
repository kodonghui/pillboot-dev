import { useEffect, useId, useRef, useState } from 'react'
import { Link, Navigate, useNavigate, useSearchParams } from 'react-router'
import { errorOf } from '../api/client.js'
import { registerDrugs } from '../api/drugs.js'
import { deletePhoto, getPhotoCandidates, identifyPhoto } from '../api/photos.js'
import DrugIngredient from '../components/DrugIngredient.jsx'
import FieldError from '../components/FieldError.jsx'
import Icon from '../components/Icon.jsx'
import Notice from '../components/Notice.jsx'
import PhotoBusy from '../components/PhotoBusy.jsx'
import PhotoFailure from '../components/PhotoFailure.jsx'
import PhotoHead from '../components/PhotoHead.jsx'
import { PhotoChange, PhotoDrop, PhotoPreview } from '../components/PhotoPick.jsx'
import PhotoSaveBar from '../components/PhotoSaveBar.jsx'
import { useManagedProfile } from '../components/useManagedProfile.js'
import { paths, prescriptionResultLink, prescriptionSearchLink } from '../paths.js'
import { photoMessages, photoProblem, photoProblemOf, photoSize } from '../photoFile.js'
import { usePrescriptionDraft } from '../store/prescriptionDraft.js'
import { eunAfter } from '../text.js'
import './PrescriptionPhotoPage.css'

const pageTitle = '처방전·약봉투 사진 올리기'
const steps = ['사진에서 약 이름 · 함량 · 단위를 읽어요.', '읽은 이름으로 식약처 약 정보에서 후보를 찾아요.']

// 결과 사진은 기다리지 않고 지운다. 이미 지워졌으면(30분 정리 · 저장 뒤) 실패해도 그대로 둔다.
function forgetPhoto(photoId) {
  deletePhoto(photoId).catch(() => {})
}

// 이름 고쳐 검색(MED-03 처방전 검색 모드)과 다시 찍기. 읽은 이름을 검색어로 넣고, 검색에서 고른 약은 이 이름 자리로 가져온다.
// onRetake 가 없으면 이름 고쳐 검색만 둔다.
function FixActions({ group, photoId, onRetake }) {
  return (
    <>
      <Link className="mt-btn mt-btn--assistive mt-btn--sm" to={prescriptionSearchLink(group, photoId)}>
        <Icon name="search" size="s" />
        이름 고쳐 검색
      </Link>
      {onRetake && (
        <button className="mt-btn mt-btn--assistive mt-btn--sm" type="button" onClick={onRetake}>
          <Icon name="refresh" size="s" />
          다시 찍기
        </button>
      )}
    </>
  )
}

// 목업 .bdg-br: 체크 아이콘이 붙은 초록 배지
function CheckBadge({ children }) {
  return (
    <span className="mt-badge mt-badge--positive">
      <Icon name="check" size="s" />
      {children}
    </span>
  )
}

// 후보 · 고른 약 한 개의 이름, 성분, 제조사 · 품목기준코드
function DrugLines({ drug, ingredient }) {
  return (
    <>
      <span className="prescription-photo__cand-name">{drug.name}</span>
      <DrugIngredient value={ingredient} />
      <span className="screen-caption prescription-photo__meta">
        {drug.maker && <span>{drug.maker}</span>}
        <span>{drug.itemSeq}</span>
      </span>
    </>
  )
}

// IMG-04 찾은 약 확인 · IMG-06 모두 0건. 읽은 이름마다 후보 하나나 "이 중에 없음"을 고르거나, 이름 고쳐 검색(MED-03)에서 약을 골라 온다.
// 고른 값은 처방전 임시 상태(store/prescriptionDraft.js)에 두어 검색에 다녀와도 남고, 새로고침하면 풀린다.
// 사진 후보와 검색으로 고른 약은 한 번에 저장한다(POST .../drugs 의 items 에 약마다 source · readNo).
function PrescriptionResult({ profile, color, draft, onRetake, onLeave, onExpired }) {
  const navigate = useNavigate()
  const id = useId()
  const pick = usePrescriptionDraft((state) => state.pick)
  const replaceResult = usePrescriptionDraft((state) => state.replaceResult)
  const finish = usePrescriptionDraft((state) => state.finish)
  const savingRef = useRef(false)
  const [saving, setSaving] = useState(false)
  const [saveError, setSaveError] = useState(false)

  const { photoId, result, picks } = draft
  const groups = [...result.drugs].sort((a, b) => a.readNo - b.readNo)
  const name = profile.profileName

  // 고른 값: 'none'(이 중에 없음) · 사진 후보 · 이름 검색으로 고른 약. 그사이 등록돼 고를 수 없게 된 사진 후보는 고르지 않은 것으로 본다.
  const pickOf = (group) => {
    const value = picks[group.readNo] ?? null
    if (value === null || value === 'none' || value.source === 'SEARCH') return value
    return group.candidates.some((candidate) => candidate.itemSeq === value.itemSeq && !candidate.alreadyRegistered) ? value : null
  }
  const searchedIn = (group) => pickOf(group)?.source === 'SEARCH'

  // 저장할 약: 읽은 이름 순서대로 이름마다 하나. 같은 약을 두 이름에서 골라도 처음 이름에서 한 번만 보낸다.
  const items = []
  groups.forEach((group) => {
    const value = pickOf(group)
    if (value && value !== 'none' && !items.some((item) => item.itemSeq === value.itemSeq)) {
      items.push({ itemSeq: value.itemSeq, source: value.source, readNo: group.readNo })
    }
  })
  const fromSearch = items.filter((item) => item.source === 'SEARCH').length
  const fromPhoto = items.length - fromSearch

  // 읽은 이름의 지금 상태: 후보 확인 · 이름 검색으로 고름 · 조회 실패 · 미확인(일치하는 약 없음)
  const found = groups.filter((group) => group.candidates.length > 0 && !searchedIn(group))
  const searched = groups.filter(searchedIn)
  const open = groups.filter((group) => group.candidates.length === 0 && !searchedIn(group))
  const failed = open.filter((group) => group.lookup === 'FAILED')
  const notFound = open.filter((group) => group.lookup !== 'FAILED')
  const unpicked = groups.filter((group) => group.candidates.length > 0 && (!pickOf(group) || pickOf(group) === 'none'))
  // 조회 실패가 섞인 결과와 사진 없이 읽은 이름만 남은 결과는 새 상태 보드(lookup-mixed · zero-filled · rx-no-results)의 짧은 문구를 쓴다.
  const short = !photoId || groups.some((group) => group.candidates.length === 0 && group.lookup === 'FAILED')
  // 사진 없이 읽은 이름만 남고 아직 고른 약이 없으면 IMG-06(rx-no-results)으로 보인다.
  const empty = !photoId && items.length === 0

  const choose = (group, value) => {
    pick(group.readNo, value)
    setSaveError(false)
  }

  const save = async () => {
    if (savingRef.current) return
    savingRef.current = true
    setSaving(true)
    setSaveError(false)
    try {
      // 사진이 남은 결과는 사진 번호와 함께 보내고, 모두 0건이라 사진이 없으면 이름 검색으로만 저장한다.
      const response = await registerDrugs(
        profile.profileId,
        photoId ? { source: 'PRESCRIPTION_PHOTO', photoId: Number(photoId), items } : { source: 'SEARCH', items },
      )
      // 저장하면 서버가 사진을 지우므로 뒤로 가기로 이 결과에 돌아오지 않게 바꿔 넣는다.
      // 이미 등록돼 건너뛴 약(skipped)은 약 목록에 이미 있으므로 저장한 약(saved)만 알린다.
      finish()
      navigate(paths.drugs, { replace: true, state: { profileId: profile.profileId, saved: response.saved ?? [] } })
    } catch (error) {
      // 401 은 client 가 로그인 만료로 처리한다(만료 화면으로 가며 임시 결과도 지운다).
      if (errorOf(error).status === 401) return
      // 사진이 남아 있으면 후보를 새로 받아(그사이 등록된 약 반영) 고른 약은 그대로 두고, 지워졌으면 만료로 보여 준다.
      if (photoId) {
        try {
          replaceResult(await getPhotoCandidates(photoId))
        } catch (reloadError) {
          const { status } = errorOf(reloadError)
          if (status === 400 || status === 404) {
            onExpired()
            return
          }
        }
      }
      setSaveError(true)
      setSaving(false)
      savingRef.current = false
    }
  }

  let summary
  if (photoId) {
    const parts = [
      [found.length, '후보를 찾았'],
      [searched.length, '이름 검색으로 골랐'],
      [failed.length, '약 정보를 불러오지 못했'],
      [notFound.length, short ? '일치하는 약이 없' : '찾지 못했'],
    ].filter(([count]) => count > 0)
    summary =
      parts.length === 1
        ? `${groups.length}개 모두 ${parts[0][1]}어요.`
        : `${groups.length}개 중 ${parts.map(([count, text]) => `${count}개는 ${text}`).join('고, ')}어요.`
  } else if (searched.length > 0) {
    summary = `읽은 이름 ${groups.length}개 모두 일치하는 약이 없었고, ${searched.length}개는 이름 검색으로 골랐어요.`
  } else {
    summary = `읽은 이름 ${groups.length}개 모두 일치하는 약을 찾지 못했어요. 이름마다 고쳐 검색해 고를 수 있어요.`
  }

  let help
  if (items.length === 0) {
    help = photoId
      ? `맞는 약을 하나 이상 고르면 저장할 수 있어요. 고른 약만 ${name} 약 목록에 저장하고, 이 화면을 나가면 아무것도 저장되지 않아요.`
      : '이름을 고쳐 검색해 약을 고르면 이 화면에서 한 번에 저장할 수 있어요.'
  } else if (!short && fromSearch === 0) {
    // 사진 후보만 고른 결과(목업 IMG-04 selected)
    const skipped = open.length > 0 ? ` 미확인 ${open.length}개(${open.map((group) => group.readName).join(', ')})는 저장하지 않아요.` : ''
    help = `고른 ${items.length}개만 ${name} 약 목록에 저장해요.${skipped} 이 화면을 나가면 아무것도 저장되지 않아요.`
  } else {
    // 이름 검색 · 조회 실패가 섞인 결과(목업 search-filled · zero-filled · lookup-mixed)
    let lead = `고른 ${items.length}개만 ${name} 약 목록에 저장해요.`
    if (fromPhoto > 0 && fromSearch > 0) lead = `사진 후보 ${fromPhoto}개와 이름 검색으로 고른 ${fromSearch}개를 ${name} 약 목록에 한 번에 저장해요.`
    else if (fromSearch > 0) lead = `이름 검색으로 고른 ${fromSearch}개를 ${name} 약 목록에 저장해요.`
    const names = unpicked.map((group) => group.readName)
    const skipped = names.length > 0 ? ` ${names.join(', ')}${eunAfter(names[names.length - 1])} 고르지 않아 저장하지 않아요.` : ''
    let rest = ''
    if (open.length === 1) rest = ` ${open[0].readName}도 고쳐 검색해 이어서 고를 수 있어요.`
    else if (open.length > 1) rest = ' 다른 이름은 고쳐 검색해 이어서 고를 수 있어요.'
    help = `${lead}${skipped}${rest}`
  }

  const summaryBar = (
    <div className="prescription-photo__summary" role="status">
      {photoId ? (
        <>
          {found.length > 0 && <CheckBadge>후보 확인 {found.length}개</CheckBadge>}
          {searched.length > 0 && <CheckBadge>이름 검색 {searched.length}개</CheckBadge>}
          {failed.length > 0 && <span className="mt-badge mt-badge--caution">조회 실패 {failed.length}개</span>}
          {notFound.length > 0 && <span className="mt-badge mt-badge--caution">미확인 {notFound.length}개</span>}
        </>
      ) : (
        <>
          <span className="mt-badge mt-badge--caution">미확인 {groups.length}개</span>
          {searched.length > 0 && <CheckBadge>이름 검색 {searched.length}개</CheckBadge>}
        </>
      )}
      <span className="screen-body prescription-photo__neutral">{summary}</span>
    </div>
  )

  return (
    <div className="prescription-photo">
      <PhotoHead
        title={empty ? pageTitle : '찾은 약 확인'}
        profile={profile}
        color={color}
        text={empty ? undefined : `사진에서 약 이름 ${groups.length}개를 읽었어요.`}
        locked
        onLeave={onLeave}
      />

      {!empty && (
        <div className="prescription-photo__top">
          {summaryBar}
          <p className="screen-caption prescription-photo__muted">
            이름마다 사진에서 읽은 이름과 공식 이름을 비교해 맞는 약을 직접 골라 주세요. 아무것도 미리 골라 두지 않아요.
          </p>
        </div>
      )}

      <div className="prescription-photo__groups">
        {empty && summaryBar}
        {groups.map((group) => {
          const groupId = `${id}-group-${group.readNo}`
          const value = pickOf(group)
          const read = (badge) => (
            <div className="prescription-photo__read">
              <p className="screen-label prescription-photo__neutral">사진에서 읽은 이름</p>
              <h2 className="screen-section" id={groupId}>
                {group.readName}
              </h2>
              {badge}
            </div>
          )

          // 후보가 없던 이름을 이름 검색으로 채움(목업 search-filled · zero-filled)
          if (group.candidates.length === 0 && value?.source === 'SEARCH') {
            return (
              <section className="mt-card prescription-photo__group" key={group.readNo} aria-labelledby={groupId}>
                {read(<CheckBadge>이름 검색으로 골랐어요</CheckBadge>)}
                <div className="prescription-photo__side">
                  <p className="screen-label prescription-photo__neutral">이름 검색으로 고른 약</p>
                  <div className="prescription-photo__grid prescription-photo__grid--flush">
                    <div className="prescription-photo__cand prescription-photo__cand--checked prescription-photo__cand--static">
                      <span className="prescription-photo__src-pick" aria-hidden="true">
                        <Icon name="check" />
                      </span>
                      <span className="prescription-photo__cand-body">
                        <DrugLines drug={value} ingredient={value.material} />
                      </span>
                    </div>
                  </div>
                  <div className="prescription-photo__actions">
                    <FixActions group={group} photoId={photoId} />
                  </div>
                  <p className="screen-caption prescription-photo__muted">다시 검색해 고르면 이 이름의 약만 바뀌고, 다른 이름에서 고른 약은 그대로예요.</p>
                </div>
              </section>
            )
          }

          // 후보가 없는 이름: 조회 실패(FAILED) · 일치하는 약 없음(NOT_FOUND)
          if (group.candidates.length === 0) {
            const lookupFailed = group.lookup === 'FAILED'
            let text = '글자를 잘못 읽었을 수 있어요. 이름을 고쳐 검색하거나 이 부분이 잘 보이게 다시 찍어 주세요.'
            if (lookupFailed) text = '식약처 약 정보 조회가 잠시 안 돼요. 이름을 고쳐 검색해 고를 수 있어요.'
            else if (short) text = '글자를 잘못 읽었을 수 있어요. 이름을 고쳐 검색해 주세요.'
            const long = !lookupFailed && !short
            return (
              <section className="mt-card prescription-photo__group prescription-photo__group--unknown" key={group.readNo} aria-labelledby={groupId}>
                {read(<span className="mt-badge mt-badge--caution">{lookupFailed ? '조회 실패' : '미확인'}</span>)}
                <div className="prescription-photo__side">
                  <p className="screen-item">{lookupFailed ? '약 정보를 불러오지 못했어요' : '일치하는 약이 없어요'}</p>
                  <p className="screen-body prescription-photo__neutral">{text}</p>
                  <div className="prescription-photo__actions">
                    <FixActions group={group} photoId={photoId} onRetake={long ? onRetake : undefined} />
                  </div>
                  {long && <p className="screen-caption prescription-photo__muted">검색에서 약을 고르고 돌아와도 위에서 고른 약은 그대로예요.</p>}
                </div>
              </section>
            )
          }

          let badge = <span className="mt-badge">아직 고르지 않았어요</span>
          if (value === 'none') badge = <span className="mt-badge">이 중에 없음</span>
          else if (value?.source === 'SEARCH') badge = <CheckBadge>이름 검색으로 골랐어요</CheckBadge>
          else if (value) badge = <CheckBadge>골랐어요</CheckBadge>
          return (
            <section className="mt-card prescription-photo__group" key={group.readNo} aria-labelledby={groupId}>
              {read(badge)}
              <fieldset className="prescription-photo__choices">
                <legend className="screen-label prescription-photo__neutral">찾은 후보 {group.candidates.length}개</legend>
                <div className="prescription-photo__grid">
                  {value?.source === 'SEARCH' && (
                    <label className="prescription-photo__cand prescription-photo__cand--checked">
                      <input type="radio" name={groupId} checked onChange={() => choose(group, value)} />
                      <span className="prescription-photo__cand-body">
                        <span className="screen-caption prescription-photo__neutral">이름 검색으로 고른 약</span>
                        <DrugLines drug={value} ingredient={value.material} />
                      </span>
                    </label>
                  )}
                  {group.candidates.map((candidate) => {
                    if (candidate.alreadyRegistered) {
                      return (
                        <div className="prescription-photo__cand prescription-photo__cand--registered" key={candidate.itemSeq} aria-disabled="true">
                          <span className="prescription-photo__spacer" aria-hidden="true" />
                          <span className="prescription-photo__cand-body">
                            <DrugLines drug={candidate} ingredient={candidate.ingredientName} />
                            <span className="screen-caption prescription-photo__registered">
                              <Icon name="check" size="s" />
                              이미 등록한 약이에요
                            </span>
                          </span>
                        </div>
                      )
                    }
                    const checked = value?.source === 'PRESCRIPTION_PHOTO' && value.itemSeq === candidate.itemSeq
                    return (
                      <label
                        className={checked ? 'prescription-photo__cand prescription-photo__cand--checked' : 'prescription-photo__cand'}
                        key={candidate.itemSeq}
                      >
                        <input
                          type="radio"
                          name={groupId}
                          checked={checked}
                          onChange={() => choose(group, { source: 'PRESCRIPTION_PHOTO', itemSeq: candidate.itemSeq })}
                        />
                        <span className="prescription-photo__cand-body">
                          <DrugLines drug={candidate} ingredient={candidate.ingredientName} />
                        </span>
                      </label>
                    )
                  })}
                  <div
                    className={
                      value === 'none'
                        ? 'prescription-photo__cand prescription-photo__cand--none prescription-photo__cand--checked'
                        : 'prescription-photo__cand prescription-photo__cand--none'
                    }
                  >
                    <label className="prescription-photo__none">
                      <input type="radio" name={groupId} checked={value === 'none'} onChange={() => choose(group, 'none')} />
                      <span className="prescription-photo__cand-body">
                        <span className="prescription-photo__cand-name">이 중에 없음</span>
                        <span className="screen-caption prescription-photo__meta">읽은 이름을 고쳐 검색하거나 다시 찍을 수 있어요.</span>
                      </span>
                    </label>
                    <span className="prescription-photo__none-actions">
                      <FixActions group={group} photoId={photoId} onRetake={onRetake} />
                    </span>
                  </div>
                </div>
              </fieldset>
            </section>
          )
        })}
        {empty ? (
          <p className="screen-caption prescription-photo__muted">검색에서 고른 약은 그 이름 자리에 들어가요. 다른 이름도 이어서 고쳐 검색할 수 있어요.</p>
        ) : (
          <p className="screen-caption prescription-photo__neutral prescription-photo__note">복용 일정은 저장 후 약 상세에서 등록해 주세요.</p>
        )}
      </div>

      {saveError && (
        <Notice variant="negative" role="alert">
          <b>저장하지 못했어요.</b> 고른 약은 그대로 있어요. 잠시 후 다시 저장해 주세요.
        </Notice>
      )}
      <PhotoSaveBar
        status={items.length > 0 ? `${items.length}개 골랐어요` : '아직 고른 약이 없어요'}
        help={help}
        helpId={`${id}-save`}
        notes={
          saveError && (
            <p className="screen-caption prescription-photo__muted">
              {photoId ? '새로고침하면 고른 약은 풀려요. 찾은 후보는 다시 불러와 다시 고를 수 있어요.' : '새로고침하면 고른 약은 풀려요.'}
            </p>
          )
        }
      >
        <button className="mt-btn mt-btn--assistive" type="button" onClick={onRetake}>
          <Icon name="refresh" />
          다시 찍기
        </button>
        <button className="mt-btn mt-btn--solid" type="button" disabled={items.length === 0 || saving} aria-describedby={`${id}-save`} onClick={save}>
          {saving && <Icon name="loader" size="s" className="icon--spin" />}
          {items.length > 0 ? `${items.length}개 저장` : '저장'}
        </button>
      </PhotoSaveBar>
    </div>
  )
}

// IMG-01 처방전 · 약봉투 사진 올리기 → IMG-03 사진 확인 중 → IMG-04 찾은 약 확인 · IMG-06 찾지 못함 · 사진 확인 실패.
// 찾은 결과는 처방전 임시 상태(메모리)에 두고 사진 번호를 주소(?photo=)에 둔다. 새로고침하면 고른 약은 풀리고,
// 30분 동안은 후보를 다시 불러와 다시 고른다(GET /api/photos/{photoId}/candidates).
// 읽은 이름이 모두 0건이면 서버가 사진을 바로 지우므로 주소에는 names 만 남기고, 새로고침하면 다시 찍기 · 이름 검색을 안내한다.
function PrescriptionPhotoPage() {
  const navigate = useNavigate()
  const [searchParams, setSearchParams] = useSearchParams()
  const photoParam = searchParams.get('photo')
  const namesParam = searchParams.has('names')
  const { status, profile, color, retry } = useManagedProfile()
  const profileId = profile?.profileId
  const id = useId()
  const draft = usePrescriptionDraft((state) => state.draft)
  const startDraft = usePrescriptionDraft((state) => state.start)
  const finishDraft = usePrescriptionDraft((state) => state.finish)
  const clearDraft = usePrescriptionDraft((state) => state.clear)
  const [file, setFile] = useState(null)
  const [rejected, setRejected] = useState(null)
  const [busy, setBusy] = useState(false)
  const [failure, setFailure] = useState(null)
  // 주소의 사진 번호로 후보를 다시 불러오지 못한 결과: { photoId, expired } · { photoId, failed }
  // 다시 찍기는 결과를 바로 비우지만 주소는 한 박자 늦게 바뀐다. 그사이 주소에 남은 앞 번호(before)는 다시 읽지 않는다.
  const [loaded, setLoaded] = useState(null)
  const aliveRef = useRef(false)

  // 주소와 맞는 임시 결과: 같은 사람 · 같은 사진 번호(사진 없이 읽은 이름만 남은 결과는 names)
  const matches = photoParam ? draft?.photoId === photoParam : namesParam && draft?.photoId === null
  const current = matches && draft.profileId === profileId ? draft : null
  const ready = Boolean(current)

  useEffect(() => {
    if (!profileId || !photoParam || ready || loaded?.photoId === photoParam || loaded?.before === photoParam) return
    let active = true
    getPhotoCandidates(photoParam)
      .then((result) => active && startDraft({ profileId, photoId: photoParam, result }))
      .catch((error) => {
        if (!active) return
        const { status: code } = errorOf(error)
        setLoaded({ photoId: photoParam, [code === 400 || code === 404 ? 'expired' : 'failed']: true })
      })
    return () => {
      active = false
    }
  }, [profileId, photoParam, ready, loaded?.photoId, loaded?.before, startDraft])

  // 화면이 내려가면 늦게 온 사진 확인 결과는 버린다. 저장했거나 사진 등록을 그만두고 넘어가면 임시 결과를 지우고,
  // 이름 고쳐 검색에 다녀오는 동안에는 둔다.
  useEffect(() => {
    aliveRef.current = true
    return () => {
      aliveRef.current = false
      if (usePrescriptionDraft.getState().draft?.done) clearDraft()
    }
  }, [clearDraft])

  if (status === 'none') return <Navigate to={paths.profileNew} replace />
  if (status === 'failed') {
    return (
      <Notice
        variant="negative"
        role="alert"
        action={
          <button className="mt-btn mt-btn--assistive mt-btn--md" type="button" onClick={retry}>
            다시 시도
          </button>
        }
      >
        프로필을 불러오지 못했어요. 잠시 후 다시 시도해 주세요.
      </Notice>
    )
  }
  if (!profile) return null

  const pick = (next) => {
    const problem = photoProblem(next)
    setRejected(problem ? { name: next.name, size: next.size, problem } : null)
    setFile(problem ? null : next)
  }

  // 서버가 판독 · 조회를 마칠 때까지 기다린다. 화면에서 시간을 끊거나 사진을 저절로 다시 보내지 않는다.
  const find = async () => {
    setBusy(true)
    setFailure(null)
    try {
      const response = await identifyPhoto(profile.profileId, 'PRESCRIPTION', { image: file })
      // 기다리는 사이 다른 화면으로 갔으면(결과는 저장되지 않는다고 안내함) 결과를 버리고 사진도 지운다.
      if (!aliveRef.current) {
        if (response.photoId) forgetPhoto(response.photoId)
        return
      }
      // 결과는 주소가 바뀌면 보이므로 그때까지 사진 확인 중 화면을 둔다.
      if (response.photoId) {
        const photoId = String(response.photoId)
        startDraft({ profileId: profile.profileId, photoId, result: response })
        navigate(prescriptionResultLink(photoId), { replace: true })
        return
      }
      // 읽은 이름이 모두 0건이면 서버가 사진을 바로 지운다(사진 번호 없음). 이름마다 고쳐 검색할 카드만 보여 준다.
      if (response.status === 'NOT_FOUND' && response.drugs?.length > 0) {
        startDraft({ profileId: profile.profileId, photoId: null, result: response })
        navigate(prescriptionResultLink(null), { replace: true })
        return
      }
      setFailure({ type: response.status === 'UNREADABLE' ? 'unreadable' : 'photoError' })
    } catch (error) {
      if (!aliveRef.current) return
      // 서버가 사진을 거절하면(형식 · 크기) 고르기 화면에 같은 오류를 보여 준다. 그 밖(503 · 네트워크)은 사진 확인 실패다.
      const problem = photoProblemOf(errorOf(error).code)
      if (problem) {
        setRejected({ name: file.name, size: file.size, problem })
        setFile(null)
      } else setFailure({ type: 'photoError' })
    }
    setBusy(false)
  }

  // 다시 찍기: 찾은 결과의 사진을 지우고 임시 결과를 비운 뒤 사진 고르기로 돌아간다.
  const retake = () => {
    if (current?.photoId) forgetPhoto(current.photoId)
    clearDraft()
    setFile(null)
    setRejected(null)
    setFailure(null)
    setBusy(false)
    setLoaded({ before: photoParam })
    setSearchParams({}, { replace: true })
  }

  // 등록 방법으로 · 취소(사진 등록을 그만둠): 찾은 결과의 사진을 지우고, 화면을 떠날 때 임시 결과를 지운다.
  const leave = () => {
    if (current?.photoId) forgetPhoto(current.photoId)
    finishDraft()
  }

  const searchAction = (
    <Link className="mt-btn mt-btn--assistive" to={paths.drugSearch} onClick={leave}>
      <Icon name="search" />
      이름으로 검색
    </Link>
  )
  const retakeAction = (solid) => (
    <button className={solid ? 'mt-btn mt-btn--solid' : 'mt-btn mt-btn--assistive'} type="button" onClick={retake}>
      <Icon name="refresh" />
      다시 찍기
    </button>
  )

  if (photoParam) {
    const fetched = loaded?.photoId === photoParam ? loaded : null
    if (current && !fetched?.expired) {
      return (
        <PrescriptionResult
          key={photoParam}
          profile={profile}
          color={color}
          draft={current}
          onRetake={retake}
          onLeave={leave}
          onExpired={() => setLoaded({ photoId: photoParam, expired: true })}
        />
      )
    }
    return (
      <div className="prescription-photo">
        <PhotoHead title={fetched?.expired ? pageTitle : '찾은 약 확인'} profile={profile} color={color} locked onLeave={leave} />
        {fetched?.expired && (
          <PhotoFailure
            icon="clock"
            plain
            badge="만료"
            title="결과를 볼 수 있는 시간이 지났어요"
            text="찾은 결과는 30분 동안만 다시 열 수 있어요. 사진을 다시 올리거나 이름으로 검색해 주세요."
            actions={
              <>
                {retakeAction(true)}
                {searchAction}
              </>
            }
            note={{ text: '올린 사진은 남지 않아요. 저장한 약이 있다면 약 목록에서 볼 수 있어요.' }}
          />
        )}
        {fetched?.failed && (
          <Notice
            variant="negative"
            role="alert"
            action={
              <button className="mt-btn mt-btn--assistive mt-btn--md" type="button" onClick={() => setLoaded(null)}>
                다시 시도
              </button>
            }
          >
            찾은 결과를 불러오지 못했어요. 잠시 후 다시 시도해 주세요.
          </Notice>
        )}
      </div>
    )
  }

  if (namesParam) {
    if (current) {
      return <PrescriptionResult key="names" profile={profile} color={color} draft={current} onRetake={retake} onLeave={leave} />
    }
    // 읽은 이름만 남은 결과는 메모리에만 있어 새로고침하면 다시 볼 수 없다.
    return (
      <div className="prescription-photo">
        <PhotoHead title={pageTitle} profile={profile} color={color} locked onLeave={leave} />
        <PhotoFailure
          icon="image"
          plain
          badge="다시 볼 수 없음"
          title="읽은 이름을 다시 불러올 수 없어요"
          text="일치하는 약이 없던 사진은 바로 지워서 새로고침하면 결과를 다시 볼 수 없어요. 다시 찍거나 이름으로 검색해 주세요."
          actions={
            <>
              {retakeAction(true)}
              {searchAction}
            </>
          }
        />
      </div>
    )
  }

  if (busy) {
    return (
      <div className="prescription-photo">
        <PhotoHead title={pageTitle} profile={profile} color={color} />
        <div className="prescription-photo__body">
          <PhotoBusy photos={[{ file, caption: file.name }]} steps={steps} />
        </div>
      </div>
    )
  }

  if (failure) {
    return (
      <div className="prescription-photo">
        <PhotoHead title={pageTitle} profile={profile} color={color} locked onLeave={leave} />
        {failure.type === 'unreadable' && (
          <PhotoFailure
            badge="못 읽음"
            title="약 이름을 읽지 못했어요"
            text="사진에서 약 이름을 찾지 못했어요. 약 이름 · 함량 · 단위가 선명하게 보이게 다시 찍거나, 약 이름으로 검색해 주세요."
            actions={
              <>
                {retakeAction(true)}
                {searchAction}
              </>
            }
            note={{ variant: 'positive', icon: 'check', text: '올린 사진은 바로 지웠어요.' }}
          />
        )}
        {failure.type === 'photoError' && (
          <PhotoFailure
            badge="사진 확인 실패"
            title="사진 확인에 실패했어요"
            text="다시 시도해 주세요. 사진을 확인하는 중에 문제가 생긴 것으로, 맞는 약이 없다는 뜻은 아니에요."
            actions={
              <>
                {/* 같은 사진을 고른 채 사진 고르기로 돌아간다(목업 링크 IMG-01 selected). 약 찾기를 누를 때만 다시 보낸다. */}
                <button className="mt-btn mt-btn--solid" type="button" onClick={() => setFailure(null)}>
                  <Icon name="refresh" />
                  다시 시도
                </button>
                {searchAction}
              </>
            }
            caption="사진을 자동으로 다시 보내지 않아요. 다시 시도를 누를 때만 보내요."
          />
        )}
      </div>
    )
  }

  const errorId = `${id}-error`
  return (
    <div className="prescription-photo">
      <PhotoHead title={pageTitle} profile={profile} color={color} onLeave={leave} />
      <div className="prescription-photo__body">
        <div className="prescription-photo__drop">
          <PhotoDrop
            id={`${id}-photo`}
            title="약 이름이 보이는 사진 1장을 골라 주세요"
            invalid={Boolean(rejected)}
            describedBy={rejected ? errorId : undefined}
            onPick={pick}
          />
          {rejected && (
            <div className="prescription-photo__error" id={errorId} role="alert">
              <FieldError>{photoMessages[rejected.problem]}</FieldError>
              <p className="screen-caption prescription-photo__muted prescription-photo__rejected">
                고른 사진: {rejected.name} <span className="prescription-photo__number">({photoSize(rejected.size)})</span>
              </p>
            </div>
          )}
        </div>

        {file && (
          <section className="prescription-photo__picked" aria-labelledby={`${id}-picked`}>
            <h2 className="screen-label prescription-photo__neutral" id={`${id}-picked`}>
              고른 사진
            </h2>
            <div className="mt-card prescription-photo__picked-card">
              <PhotoPreview file={file} alt="고른 사진" className="prescription-photo__preview" />
              <span className="prescription-photo__file">
                <span className="prescription-photo__file-name">{file.name}</span>
                <span className="screen-caption prescription-photo__muted prescription-photo__number">{photoSize(file.size)}</span>
              </span>
              <PhotoChange onPick={pick} />
            </div>
          </section>
        )}

        <div className="mt-notice">
          <Icon name="info" className="mt-notice__icon" />
          <div className="mt-notice__body">
            <p className="mt-notice__title">찍는 요령</p>
            <p className="mt-notice__text">약 이름 · 함량 · 단위(예: 스포라녹스캡슐 100밀리그램)가 흐리지 않고 잘리지 않게 찍어 주세요.</p>
            <p className="mt-notice__text screen-caption prescription-photo__muted">글자가 기울거나 접힌 부분에 걸리면 잘못 읽을 수 있어요.</p>
          </div>
        </div>
        <Notice variant="info">올린 사진은 약 등록이 끝나면 지워져요. 처방전의 다른 글자(병원, 이름 등)는 저장하지 않아요.</Notice>

        <div className="prescription-photo__actions">
          <Link className="mt-btn mt-btn--assistive" to={paths.drugNew} onClick={leave}>
            취소
          </Link>
          <button className="mt-btn mt-btn--solid" type="button" disabled={!file} onClick={find}>
            <Icon name="search" />
            약 찾기
          </button>
        </div>
      </div>
    </div>
  )
}

export default PrescriptionPhotoPage
