import { useEffect, useId, useRef, useState } from 'react'
import { Link, Navigate, useNavigate, useSearchParams } from 'react-router'
import { errorOf } from '../api/client.js'
import { registerDrugs } from '../api/drugs.js'
import { cancelPillIdentification, getPhotoImage, getPillIdentification, identifyPhoto } from '../api/photos.js'
import FieldError from '../components/FieldError.jsx'
import Icon from '../components/Icon.jsx'
import Notice from '../components/Notice.jsx'
import PhotoBusy from '../components/PhotoBusy.jsx'
import PhotoFailure from '../components/PhotoFailure.jsx'
import PhotoHead from '../components/PhotoHead.jsx'
import { PhotoChange, PhotoDrop, PhotoPreview } from '../components/PhotoPick.jsx'
import PhotoSaveBar from '../components/PhotoSaveBar.jsx'
import PillCandidate from '../components/PillCandidate.jsx'
import PillClueList from '../components/PillClueList.jsx'
import PillRefinePanel from '../components/PillRefinePanel.jsx'
import { useManagedProfile } from '../components/useManagedProfile.js'
import { paths } from '../paths.js'
import { photoMessages, photoProblem, photoProblemOf, photoSize } from '../photoFile.js'
import { eulAfter } from '../text.js'
import '../components/VisuallyHidden.css'
import './PillPhotoPage.css'

const pageTitle = '알약 사진 올리기'
const steps = ['두 사진에서 각인 · 모양 · 색상을 읽어요.', '읽은 단서로 식약처 낱알식별 정보에서 후보를 찾아요.']
const sides = [
  { key: 'front', name: '사진 1' },
  { key: 'back', name: '사진 2', aside: '반대쪽 면' },
]
const noPhotos = { front: null, back: null }
const keepNote = '이 결과는 30분 동안 새로고침해도 다시 볼 수 있어요. 마지막으로 찾은 결과만 돌아오고, 고치던 단서는 저장되지 않아요.'
const clueNames = { IMPRINT: '각인', SHAPE: '모양', COLOR: '색상' }

// 결과를 다시 읽지 못한 까닭. 없거나 · 바뀌었거나 · 시간이 지난 결과는 만료로 본다.
function readProblem(error) {
  const { status } = errorOf(error)
  return [400, 404, 409, 410].includes(status) ? 'expired' : 'failed'
}

// 단서 부족 첫 문장. 각인만 없으면 목업 문장, 아니면 없는 단서를 이어 쓴다.
function missingText(missing) {
  if (missing.length === 1 && missing[0] === 'IMPRINT') return '두 사진 모두 각인을 읽지 못했어요.'
  const names = missing.map((field) => clueNames[field]).filter(Boolean)
  if (names.length === 0) return '사진에서 단서를 읽지 못했어요.'
  return `사진에서 ${names.join(' · ')}${eulAfter(names[names.length - 1])} 읽지 못했어요.`
}

// 알약 사진 칸 하나(IMG-02 .slot)
function PillSlot({ side, file, rejected, onPick }) {
  const id = useId()
  return (
    <section className="mt-card pill-photo__slot" aria-labelledby={`${id}-title`}>
      <div className="pill-photo__slot-head">
        <h2 className="screen-item" id={`${id}-title`}>
          {side.name}
          {side.aside && <span className="screen-caption pill-photo__aside"> {side.aside}</span>}
        </h2>
        {file ? (
          <span className="mt-badge mt-badge--positive">
            <Icon name="check" size="s" />
            골랐어요
          </span>
        ) : (
          <span className="mt-badge">아직 없어요</span>
        )}
      </div>
      {file ? (
        <>
          <PhotoPreview file={file} alt={`고른 ${side.name}`} className="pill-photo__slot-photo" />
          <div className="pill-photo__slot-file">
            <span className="pill-photo__file">
              <span className="pill-photo__file-name">{file.name}</span>
              <span className="screen-caption pill-photo__muted pill-photo__number">{photoSize(file.size)}</span>
            </span>
            <PhotoChange onPick={onPick} />
          </div>
        </>
      ) : (
        <PhotoDrop
          id={`${id}-input`}
          size="sm"
          title={`${side.name} 고르기`}
          invalid={Boolean(rejected)}
          describedBy={rejected ? `${id}-error` : undefined}
          onPick={onPick}
        />
      )}
      {rejected && (
        <div className="pill-photo__error" id={`${id}-error`} role="alert">
          <FieldError>{photoMessages[rejected.problem]}</FieldError>
          <p className="screen-caption pill-photo__muted pill-photo__rejected">
            고른 사진: {rejected.name} <span className="pill-photo__number">({photoSize(rejected.size)})</span>
          </p>
        </div>
      )}
    </section>
  )
}

// 결과를 새로고침해 다시 열었을 때 서버에 남은 내 사진(GET /api/photos/{photoId}/image)
function ServerPhoto({ photoId, alt, className }) {
  const [loaded, setLoaded] = useState(null)

  useEffect(() => {
    let active = true
    getPhotoImage(photoId)
      .then((blob) => active && setLoaded({ photoId, blob }))
      .catch(() => active && setLoaded({ photoId, failed: true }))
    return () => {
      active = false
    }
  }, [photoId])

  const blob = loaded?.photoId === photoId ? loaded.blob : null
  if (blob) return <PhotoPreview file={blob} alt={alt} className={className} />
  return (
    <span className={`photo-preview ${className}`}>
      <Icon name="image" size="l" className="pill-photo__muted" />
    </span>
  )
}

// IMG-05 찾은 약 확인. 후보가 5개 이하면 하나를 골라 저장하고, 많으면 미리보기만 보여 주고 단서를 보완하게 한다.
// 고른 약은 화면에만 두어 새로고침하면 다시 고른다.
function PillResult({ profile, color, result, files, focusTitle, onRefine, onRetake, onLeave, onReload, onExpired }) {
  const navigate = useNavigate()
  const id = useId()
  const titleRef = useRef(null)
  const [pick, setPick] = useState(null)
  const [saving, setSaving] = useState(false)
  const [saveError, setSaveError] = useState(false)

  useEffect(() => {
    if (focusTitle) titleRef.current?.focus()
  }, [focusTitle])

  const candidates = result.candidates ?? []
  const total = result.totalCandidateCount ?? candidates.length
  const tooMany = result.reason === 'TOO_MANY_CANDIDATES'
  const selectable = result.selectionAllowed && !tooMany
  const picked = selectable ? candidates.find((candidate) => candidate.itemSeq === pick && !candidate.alreadyRegistered) : null
  const name = profile.profileName

  // 내 사진: 이 화면에서 고른 사진이 있으면 그것을, 새로고침했으면 서버에 남은 사진을 보여 준다.
  let photos = null
  if (files.front && files.back) {
    photos = [files.front, files.back].map((file, index) => <PhotoPreview key={index} file={file} alt="" className="pill-photo__mine-photo" />)
  } else if (result.photoIds?.length === 2) {
    photos = result.photoIds.map((photoId) => <ServerPhoto key={photoId} photoId={photoId} alt="" className="pill-photo__mine-photo" />)
  }

  const save = async () => {
    setSaving(true)
    setSaveError(false)
    try {
      const response = await registerDrugs(profile.profileId, {
        source: result.registrationSource,
        photoId: result.registrationSource === 'PILL_PHOTO' ? result.photoId : undefined,
        identificationId: result.identificationId,
        items: [{ itemSeq: picked.itemSeq }],
      })
      // 저장하면 이 결과는 끝나므로 뒤로 가기로 돌아오지 않게 바꿔 넣는다.
      navigate(paths.drugs, { replace: true, state: { profileId: profile.profileId, saved: response.saved ?? [] } })
    } catch (error) {
      if (errorOf(error).status === 410) {
        onExpired()
        return
      }
      // 결과를 다시 읽어 그사이 바뀐 상태(이미 등록 · 교체 · 저장 끝남)를 보여 준다.
      try {
        onReload(await getPillIdentification(profile.profileId, result.identificationId))
        setSaveError(true)
      } catch (readError) {
        if (readProblem(readError) === 'expired') onExpired()
        else setSaveError(true)
      }
      setSaving(false)
    }
  }

  const retakeButton = (
    <button className="mt-btn mt-btn--assistive" type="button" onClick={onRetake}>
      <Icon name="refresh" />
      다시 찍기
    </button>
  )

  let bar
  if (tooMany) {
    bar = (
      <PhotoSaveBar status="단서를 보완해 다시 찾아 주세요" help="각인이 읽힌 면 1개 이상, 모양, 색상 1개 이상이 있으면 다시 찾을 수 있어요.">
        <Link className="mt-btn mt-btn--assistive" to={paths.drugSearch}>
          <Icon name="search" />
          이름으로 검색
        </Link>
        {retakeButton}
        <button className="mt-btn mt-btn--solid" type="button" onClick={onRefine}>
          <Icon name="pencil" />
          단서 보완하기
        </button>
      </PhotoSaveBar>
    )
  } else if (!selectable) {
    bar = (
      <PhotoSaveBar status="고를 수 있는 약이 없어요" help="찾은 후보가 모두 이미 등록한 약이에요. 약 목록에서 확인해 주세요.">
        {retakeButton}
        <Link className="mt-btn mt-btn--solid" to={paths.drugs} onClick={onLeave}>
          약 목록으로
        </Link>
      </PhotoSaveBar>
    )
  } else {
    bar = (
      <PhotoSaveBar
        busy={saving}
        error={saveError ? "저장하지 못했어요. 고른 약은 그대로예요." : null}
        status={picked ? `${picked.name}${eulAfter(picked.name)} 골랐어요` : '아직 고른 약이 없어요'}
        help={
          picked
            ? `고른 약 한 개를 ${name} 약 목록에 저장해요. 다른 후보를 누르면 고른 약이 바뀌어요.`
            : `약 하나를 고르면 저장할 수 있어요. 고른 약 한 개만 ${name} 약 목록에 저장해요.`
        }
        helpId={`${id}-save`}
        notes={<p className="screen-caption pill-photo__muted">이 결과는 30분 동안 새로고침해도 다시 볼 수 있어요. 고치던 단서는 저장되지 않아요.</p>}
      >
        {retakeButton}
        <button className="mt-btn mt-btn--solid" type="button" disabled={!picked || saving} aria-describedby={`${id}-save`} onClick={save}>
          {saving && <Icon name="loader" size="s" className="icon--spin" />}
          저장
        </button>
      </PhotoSaveBar>
    )
  }

  return (
    <div className="pill-photo">
      <PhotoHead
        title="찾은 약 확인"
        profile={profile}
        color={color}
        text={result.origin === 'CLUES' ? '고친 단서로 찾았어요.' : '알약 사진 2장에서 읽은 단서로 찾았어요.'}
        locked
        onLeave={onLeave}
      />
      <div className="pill-photo__columns">
        <section className="mt-card pill-photo__mine" aria-labelledby={`${id}-mine`}>
          <h2 className="screen-item" id={`${id}-mine`}>
            {photos ? '내 사진과 읽은 단서' : '찾은 단서'}
          </h2>
          {photos && (
            <div className="pill-photo__mine-photos">
              {photos.map((photo, index) => (
                <figure className="pill-photo__figure" key={sides[index].key}>
                  {photo}
                  <figcaption className="screen-caption pill-photo__muted">
                    {sides[index].name}
                    {sides[index].aside && ` · ${sides[index].aside}`}
                  </figcaption>
                </figure>
              ))}
            </div>
          )}
          <div className="pill-photo__mine-clues">
            <PillClueList clues={result.clues} grouped />
          </div>
          <button className="mt-btn mt-btn--assistive mt-btn--sm pill-photo__start" type="button" onClick={onRefine}>
            <Icon name="pencil" size="s" />
            단서 보완하기
          </button>
          <p className="screen-caption pill-photo__muted">단서를 고치면 고른 후보가 풀리고 다시 찾아요.</p>
        </section>

        <section className="pill-photo__candidates" aria-labelledby={`${id}-candidates`}>
          <div className="pill-photo__count">
            <h2 className="screen-item pill-photo__count-title" id={`${id}-candidates`} ref={titleRef} tabIndex={-1}>
              {tooMany ? '후보가 많아요' : `찾은 후보 ${total}개`}
            </h2>
            <span className="screen-caption pill-photo__muted">
              {tooMany ? `전체 ${total}개 중 ${candidates.length}개 미리보기` : `전체 ${total}개 · 모두 보여 드려요`}
            </span>
          </div>
          {tooMany ? (
            <div className="mt-notice mt-notice--caution" role="status">
              <Icon name="alertTriangle" className="mt-notice__icon" />
              <div className="mt-notice__body">
                <p className="mt-notice__title">지금은 고르거나 저장할 수 없어요.</p>
                <p className="mt-notice__text">
                  읽은 단서만으로는 후보가 {total}개라 맞는 약을 가리기 어려워요. 단서를 보완하면 후보를 줄일 수 있어요.
                </p>
              </div>
            </div>
          ) : (
            <p className="screen-body pill-photo__neutral">내 사진과 공식 정보를 비교해 맞는 약을 직접 골라 주세요. 아무것도 미리 골라 두지 않아요.</p>
          )}
          <Notice>
            &quot;일치&quot;는 사진에서 읽은 값과 공식 정보가 같은 항목만 표시해요. 사진에서 못 읽었거나 공식 정보가 없는 항목은 일치로 세지
            않고 &quot;확인 못 함&quot;으로 보여 드려요. 점수나 %는 쓰지 않아요.
          </Notice>
          {!tooMany ? (
            <fieldset className="pill-photo__choices" disabled={saving}>
              <legend className="visually-hidden">맞는 약 하나 고르기</legend>
              <div className="pill-photo__list">
                {candidates.map((candidate) => (
                  <PillCandidate
                    key={candidate.itemSeq}
                    candidate={candidate}
                    clues={result.clues}
                    name={`${id}-pick`}
                    checked={picked?.itemSeq === candidate.itemSeq}
                    onChange={() => {
                      setPick(candidate.itemSeq)
                      setSaveError(false)
                    }}
                  />
                ))}
              </div>
            </fieldset>
          ) : (
            <ul className="pill-photo__list" aria-label={`후보 미리보기 ${candidates.length}개`}>
              {candidates.map((candidate) => (
                <li key={candidate.itemSeq}>
                  <PillCandidate candidate={candidate} clues={result.clues} preview />
                </li>
              ))}
            </ul>
          )}
          {tooMany ? (
            <p className="screen-caption pill-photo__muted">나머지 {total - candidates.length}개는 단서를 보완한 뒤에 볼 수 있어요.</p>
          ) : (
            <p className="screen-caption pill-photo__muted">
              맞는 약이 없으면 단서를 보완하거나 다시 찍거나{' '}
              <Link className="pill-photo__link" to={paths.drugSearch}>
                이름으로 검색
              </Link>
              해 주세요.
            </p>
          )}
        </section>
      </div>

      {saveError && (
        <Notice variant="negative" role="alert">
          <b>저장하지 못했어요.</b> 잠시 후 다시 저장해 주세요.
        </Notice>
      )}
      {bar}
    </div>
  )
}

// IMG-02 알약 사진 올리기 → IMG-03 찾는 중 → IMG-05 찾은 약 확인 · IMG-06 찾지 못함 · IMG-07 단서 보완하기.
// 찾은 결과는 식별 ID 를 주소(?result=)에 두어 새로고침해도 30분 동안 다시 불러온다.
function PillPhotoPage() {
  const [searchParams, setSearchParams] = useSearchParams()
  const resultParam = searchParams.get('result')
  const { status, profile, color, retry } = useManagedProfile()
  const profileId = profile?.profileId
  const needId = useId()
  const [files, setFiles] = useState(noPhotos)
  const [rejected, setRejected] = useState(noPhotos)
  const [sendError, setSendError] = useState(null)
  const [busy, setBusy] = useState(false)
  const [serverError, setServerError] = useState(false)
  // 주소의 식별 ID 로 불러온 결과: { id, data } · { id, problem: 'expired' | 'failed' }
  // 새 결과 보이기 · 다시 찍기는 결과를 바로 바꾸지만 주소는 한 박자 늦게 바뀐다. 그사이 주소에 남은 앞 ID(before)는 다시 읽지 않는다.
  const [loaded, setLoaded] = useState(null)
  const [refining, setRefining] = useState(false)
  const [refined, setRefined] = useState(false)

  useEffect(() => {
    if (!profileId || !resultParam || loaded?.id === resultParam || loaded?.before === resultParam) return
    let active = true
    getPillIdentification(profileId, resultParam)
      .then((data) => active && setLoaded({ id: resultParam, data }))
      .catch((error) => active && setLoaded({ id: resultParam, problem: readProblem(error) }))
    return () => {
      active = false
    }
  }, [profileId, resultParam, loaded?.id, loaded?.before])

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

  const current = resultParam && loaded?.id === resultParam ? loaded : null
  const result = current?.data?.lifecycle === 'ACTIVE' ? current.data : null

  // 결과를 끝낸다(서버가 사진도 지운다). 기다리지 않고, 이미 끝났으면 실패해도 그대로 둔다.
  const forget = () => {
    if (result) cancelPillIdentification(profile.profileId, result.identificationId).catch(() => {})
  }

  const show = (response, fromRefine) => {
    setLoaded({ id: response.identificationId, data: response, before: resultParam })
    setSearchParams({ result: response.identificationId }, { replace: true })
    setServerError(false)
    setRefining(false)
    if (fromRefine) setRefined(true)
  }

  const pick = (side, file) => {
    const problem = photoProblem(file)
    setRejected((previous) => ({ ...previous, [side]: problem ? { name: file.name, size: file.size, problem } : null }))
    setFiles((previous) => ({ ...previous, [side]: problem ? null : file }))
    setSendError(null)
  }

  const find = async () => {
    setBusy(true)
    setServerError(false)
    setSendError(null)
    try {
      show(await identifyPhoto(profile.profileId, 'PILL', { front: files.front, back: files.back }))
    } catch (error) {
      // 서버가 사진을 거절하면(형식 · 크기) 고르기 화면에 알리고, 그 밖의 실패는 서버 오류 화면으로 보낸다.
      const problem = photoProblemOf(errorOf(error).code)
      if (problem) setSendError(problem)
      else setServerError(true)
    }
    setBusy(false)
  }

  // 다시 찍기: 지금 결과를 끝내고 사진 고르기로 돌아간다. 서버 오류 뒤에는 고른 사진을 그대로 두어 다시 보낼 수 있게 한다.
  const retake = () => {
    forget()
    if (!serverError) setFiles(noPhotos)
    setRejected(noPhotos)
    setSendError(null)
    setServerError(false)
    setLoaded({ before: resultParam })
    setRefining(false)
    setSearchParams({}, { replace: true })
  }

  const refinePanel = refining && (
    <PillRefinePanel
      clues={result?.clues ?? null}
      profileId={profile.profileId}
      previousId={result?.identificationId}
      caution={Boolean(result?.selectionAllowed)}
      replacing={Boolean(result)}
      onDone={(response) => show(response, true)}
      onClose={() => setRefining(false)}
    />
  )
  const searchAction = (
    <Link className="mt-btn mt-btn--assistive" to={paths.drugSearch}>
      <Icon name="search" />
      이름으로 검색
    </Link>
  )
  const nextActions = (
    <>
      <button className="mt-btn mt-btn--solid" type="button" onClick={() => setRefining(true)}>
        <Icon name="pencil" />
        단서 보완하기
      </button>
      <button className="mt-btn mt-btn--assistive" type="button" onClick={retake}>
        <Icon name="refresh" />
        다시 찍기
      </button>
      {searchAction}
    </>
  )

  if (resultParam) {
    if (!current) {
      return (
        <div className="pill-photo">
          <PhotoHead title="찾은 약 확인" profile={profile} color={color} locked />
        </div>
      )
    }
    if (current.problem === 'failed') {
      return (
        <div className="pill-photo">
          <PhotoHead title="찾은 약 확인" profile={profile} color={color} locked />
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
        </div>
      )
    }
    if (current.problem === 'expired') {
      return (
        <div className="pill-photo">
          <PhotoHead title={pageTitle} profile={profile} color={color} locked />
          <PhotoFailure
            icon="clock"
            plain
            badge="만료"
            title="결과를 볼 수 있는 시간이 지났어요"
            text="찾은 결과는 30분 동안만 다시 열 수 있어요. 사진을 다시 올리거나 이름으로 검색해 주세요."
            actions={
              <>
                <button className="mt-btn mt-btn--solid" type="button" onClick={retake}>
                  <Icon name="refresh" />
                  다시 찍기
                </button>
                {searchAction}
              </>
            }
            note={{ text: '올린 사진과 고치던 단서는 남지 않아요. 저장한 약이 있다면 약 목록에서 볼 수 있어요.' }}
          />
        </div>
      )
    }
    // 끝난 결과: 바뀐 결과로 옮기고, 저장을 마쳤으면 약 목록, 취소했으면 사진 고르기로 간다.
    const { lifecycle, replacementIdentificationId } = current.data
    if (lifecycle === 'REPLACED' && replacementIdentificationId) {
      return <Navigate to={`${paths.pillPhoto}?${new URLSearchParams({ result: replacementIdentificationId })}`} replace />
    }
    if (lifecycle === 'CONSUMED') return <Navigate to={paths.drugs} replace />
    if (!result) return <Navigate to={paths.pillPhoto} replace />

    const missing = result.missingFields ?? []
    if (result.status === 'UNREADABLE' || result.status === 'NOT_FOUND') {
      const unreadable = result.status === 'UNREADABLE'
      return (
        <div className="pill-photo">
          <PhotoHead title={pageTitle} profile={profile} color={color} locked onLeave={forget} />
          <PhotoFailure
            badge={unreadable ? '단서 부족' : '후보 0개'}
            title={unreadable ? '단서가 부족해서 찾지 못했어요' : '단서에 맞는 알약이 없어요'}
            text={
              unreadable
                ? `${missingText(missing)} 다시 찾으려면 각인이 읽힌 면 1개 이상, 모양, 색상 1개 이상이 필요해요.`
                : '읽은 단서로 식약처 낱알식별 정보를 찾았지만 맞는 약이 없었어요. 단서가 사진과 맞는지 확인해 주세요.'
            }
            actions={nextActions}
            note={{ text: keepNote }}
          >
            <p className="screen-label pill-photo__neutral">읽은 단서</p>
            <PillClueList clues={result.clues} missing={unreadable ? missing : []} />
          </PhotoFailure>
          {refinePanel}
        </div>
      )
    }

    return (
      <>
        <PillResult
          key={result.identificationId}
          profile={profile}
          color={color}
          result={result}
          files={files}
          focusTitle={refined}
          onRefine={() => setRefining(true)}
          onRetake={retake}
          onLeave={forget}
          onReload={(data) => setLoaded({ id: result.identificationId, data })}
          onExpired={() => setLoaded({ id: result.identificationId, problem: 'expired' })}
        />
        {refinePanel}
      </>
    )
  }

  const ready = Boolean(files.front && files.back)
  return (
    <div className="pill-photo">
      <PhotoHead title={pageTitle} profile={profile} color={color} locked={serverError} />
      {busy && (
        <div className="pill-photo__body">
          <PhotoBusy
            photos={sides.map((side) => ({ file: files[side.key], caption: `${side.name} · ${files[side.key].name}` }))}
            steps={steps}
            size="sm"
          />
        </div>
      )}
      {!busy && serverError && (
        <PhotoFailure
          icon="cloudOff"
          plain
          badge="서버 오류"
          title="지금 약 정보를 찾을 수 없어요"
          text="약 정보 서버에 연결하지 못했어요. 잠시 뒤 단서를 직접 넣어 다시 찾거나, 다시 찍거나, 이름으로 검색해 주세요."
          actions={nextActions}
          note={{ text: '사진을 자동으로 다시 보내지 않아요. 버튼을 누를 때만 다시 찾아요.' }}
        />
      )}
      {!busy && !serverError && (
        <div className="pill-photo__body">
          <p className="screen-body pill-photo__neutral">알약 한 면을 사진 1로, 뒤집은 반대쪽 면을 사진 2로 한 장씩 올려 주세요.</p>
          <div className="pill-photo__slots">
            {sides.map((side) => (
              <PillSlot key={side.key} side={side} file={files[side.key]} rejected={rejected[side.key]} onPick={(file) => pick(side.key, file)} />
            ))}
          </div>
          {sendError && (
            <div role="alert">
              <FieldError>사진 1 · 2 가운데 올릴 수 없는 사진이 있어요. {photoMessages[sendError]}</FieldError>
            </div>
          )}
          <div className="mt-notice">
            <Icon name="info" className="mt-notice__icon" />
            <div className="mt-notice__body">
              <p className="mt-notice__title">찍는 요령</p>
              <p className="mt-notice__text">
                알약 전체가 화면 안에 들어오고 각인(새겨진 글자 · 기호), 전체 모양, 색상이 선명하게 보이게 찍어 주세요.
              </p>
              <p className="mt-notice__text">빛 반사나 그림자가 각인을 가리지 않게 해 주세요. 사진 2는 알약을 뒤집은 반대쪽 면이에요.</p>
              <p className="mt-notice__text screen-caption pill-photo__muted">어느 면을 먼저 찍어도 괜찮아요. 올린 사진은 약 등록이 끝나면 지워져요.</p>
            </div>
          </div>
          {!ready && (
            <div className="mt-notice mt-notice--caution" id={needId} role="status">
              <Icon name="alertTriangle" className="mt-notice__icon" />
              <p className="mt-notice__text">두 면이 모두 있어야 약을 찾을 수 있어요.</p>
            </div>
          )}
          <div className="pill-photo__actions">
            <Link className="mt-btn mt-btn--assistive" to={paths.drugNew}>
              취소
            </Link>
            <button
              className="mt-btn mt-btn--solid"
              type="button"
              disabled={!ready}
              aria-describedby={ready ? undefined : needId}
              onClick={find}
            >
              <Icon name="search" />
              약 찾기
            </button>
          </div>
        </div>
      )}
      {refinePanel}
    </div>
  )
}

export default PillPhotoPage
