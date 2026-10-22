import { useEffect, useId, useState } from 'react'
import { Link, Navigate, useNavigate, useSearchParams } from 'react-router'
import { errorOf } from '../api/client.js'
import { registerDrugs } from '../api/drugs.js'
import { deletePhoto, getPhotoCandidates, identifyPhoto } from '../api/photos.js'
import FieldError from '../components/FieldError.jsx'
import Icon from '../components/Icon.jsx'
import Notice from '../components/Notice.jsx'
import PhotoBusy from '../components/PhotoBusy.jsx'
import PhotoFailure from '../components/PhotoFailure.jsx'
import PhotoHead from '../components/PhotoHead.jsx'
import { PhotoChange, PhotoDrop, PhotoPreview } from '../components/PhotoPick.jsx'
import PhotoSaveBar from '../components/PhotoSaveBar.jsx'
import { useManagedProfile } from '../components/useManagedProfile.js'
import { paths } from '../paths.js'
import { photoMessages, photoProblem, photoProblemOf, photoSize } from '../photoFile.js'
import './PrescriptionPhotoPage.css'

const pageTitle = '처방전·약봉투 사진 올리기'
const steps = ['사진에서 약 이름 · 함량 · 단위를 읽어요.', '읽은 이름으로 식약처 약 정보에서 후보를 찾아요.']

// 이름 고쳐 검색(MED-03). 읽은 이름이 하나면 검색어로 넣어 둔다.
function searchLink(names) {
  return names.length === 1 ? `${paths.drugSearch}?${new URLSearchParams({ q: names[0] })}` : paths.drugSearch
}

// 결과 사진은 기다리지 않고 지운다. 이미 지워졌으면(30분 정리 · 저장 뒤) 실패해도 그대로 둔다.
function forgetPhoto(photoId) {
  deletePhoto(photoId).catch(() => {})
}

function FixActions({ names, onRetake }) {
  return (
    <>
      <Link className="mt-btn mt-btn--assistive mt-btn--sm" to={searchLink(names)}>
        <Icon name="search" size="s" />
        이름 고쳐 검색
      </Link>
      <button className="mt-btn mt-btn--assistive mt-btn--sm" type="button" onClick={onRetake}>
        <Icon name="refresh" size="s" />
        다시 찍기
      </button>
    </>
  )
}

// IMG-04 찾은 약 확인. 읽은 이름마다 후보 하나나 "이 중에 없음"을 직접 고르고, 고른 약만 저장한다.
// 고른 값은 화면에만 두어 새로고침하면 처음부터 다시 고른다.
function PrescriptionResult({ profile, color, photoId, response, onRetake, onReload, onExpired }) {
  const navigate = useNavigate()
  const id = useId()
  const [picks, setPicks] = useState({})
  const [saving, setSaving] = useState(false)
  const [saveError, setSaveError] = useState(false)

  const groups = [...response.drugs].sort((a, b) => a.readNo - b.readNo)
  const found = groups.filter((group) => group.candidates.length > 0)
  const unknown = groups.filter((group) => group.candidates.length === 0)
  const name = profile.profileName

  // 고른 값: 'none'(이 중에 없음) · 품목기준코드. 그사이 등록돼 고를 수 없게 된 후보는 고르지 않은 것으로 본다.
  const pickOf = (group) => {
    const value = picks[group.readNo]
    if (value === 'none') return value
    return group.candidates.some((candidate) => candidate.itemSeq === value && !candidate.alreadyRegistered) ? value : null
  }
  // 같은 약을 두 이름에서 골라도 한 번만 저장한다.
  const chosen = [...new Set(found.map(pickOf).filter((value) => value && value !== 'none'))]

  const choose = (group, value) => {
    setPicks((previous) => ({ ...previous, [group.readNo]: value }))
    setSaveError(false)
  }

  const save = async () => {
    setSaving(true)
    setSaveError(false)
    try {
      const result = await registerDrugs(profile.profileId, {
        source: 'PRESCRIPTION_PHOTO',
        photoId: Number(photoId),
        items: chosen.map((itemSeq) => ({ itemSeq })),
      })
      // 저장하면 서버가 사진을 지우므로 뒤로 가기로 이 결과에 돌아오지 않게 바꿔 넣는다.
      navigate(paths.drugs, { replace: true, state: { profileId: profile.profileId, saved: result.saved ?? [] } })
    } catch {
      // 사진이 남아 있으면 후보를 새로 받아(그사이 등록된 약 반영) 다시 고르게 하고, 지워졌으면 만료로 보여 준다.
      try {
        onReload(await getPhotoCandidates(photoId))
        setSaveError(true)
      } catch (error) {
        const { status } = errorOf(error)
        if (status === 400 || status === 404) onExpired()
        else setSaveError(true)
      }
      setSaving(false)
    }
  }

  let summary = `${groups.length}개 모두 후보를 찾았어요.`
  if (unknown.length > 0) summary = `${groups.length}개 중 ${found.length}개는 후보를 찾았고, ${unknown.length}개는 찾지 못했어요.`
  const skipped = unknown.length > 0 ? ` 미확인 ${unknown.length}개(${unknown.map((group) => group.readName).join(', ')})는 저장하지 않아요.` : ''
  const help =
    chosen.length > 0
      ? `고른 ${chosen.length}개만 ${name} 약 목록에 저장해요.${skipped} 이 화면을 나가면 아무것도 저장되지 않아요.`
      : `맞는 약을 하나 이상 고르면 저장할 수 있어요. 고른 약만 ${name} 약 목록에 저장하고, 이 화면을 나가면 아무것도 저장되지 않아요.`

  return (
    <div className="prescription-photo">
      <PhotoHead
        title="찾은 약 확인"
        profile={profile}
        color={color}
        text={`사진에서 약 이름 ${groups.length}개를 읽었어요.`}
        locked
        onLeave={() => forgetPhoto(photoId)}
      />

      <div className="prescription-photo__top">
        <div className="prescription-photo__summary" role="status">
          <span className="mt-badge mt-badge--positive">
            <Icon name="check" size="s" />
            후보 확인 {found.length}개
          </span>
          {unknown.length > 0 && <span className="mt-badge mt-badge--caution">미확인 {unknown.length}개</span>}
          <span className="screen-body prescription-photo__neutral">{summary}</span>
        </div>
        <p className="screen-caption prescription-photo__muted">
          이름마다 사진에서 읽은 이름과 공식 이름을 비교해 맞는 약을 직접 골라 주세요. 아무것도 미리 골라 두지 않아요.
        </p>
      </div>

      <div className="prescription-photo__groups">
        {groups.map((group) => {
          const groupId = `${id}-group-${group.readNo}`
          const read = (badge) => (
            <div className="prescription-photo__read">
              <p className="screen-label prescription-photo__neutral">사진에서 읽은 이름</p>
              <h2 className="screen-section" id={groupId}>
                {group.readName}
              </h2>
              {badge}
            </div>
          )

          if (group.candidates.length === 0) {
            const failed = group.lookup === 'FAILED'
            return (
              <section className="mt-card prescription-photo__group prescription-photo__group--unknown" key={group.readNo} aria-labelledby={groupId}>
                {read(<span className="mt-badge mt-badge--caution">미확인</span>)}
                <div className="prescription-photo__unknown">
                  <p className="screen-item">{failed ? '이 이름은 지금 약 정보를 조회하지 못했어요' : '이 이름으로 찾은 약이 없어요'}</p>
                  <p className="screen-body prescription-photo__neutral">
                    {failed
                      ? '식약처 약 정보 조회가 잠시 안 됐어요. 이름으로 검색하거나 잠시 뒤 다시 찍어 주세요.'
                      : '글자를 잘못 읽었을 수 있어요. 이름을 고쳐 검색하거나 이 부분이 잘 보이게 다시 찍어 주세요.'}
                  </p>
                  <div className="prescription-photo__actions">
                    <FixActions names={[group.readName]} onRetake={onRetake} />
                  </div>
                </div>
              </section>
            )
          }

          const pick = pickOf(group)
          let badge = <span className="mt-badge">아직 고르지 않았어요</span>
          if (pick === 'none') badge = <span className="mt-badge">이 중에 없음</span>
          else if (pick) {
            badge = (
              <span className="mt-badge mt-badge--positive">
                <Icon name="check" size="s" />
                골랐어요
              </span>
            )
          }
          return (
            <section className="mt-card prescription-photo__group" key={group.readNo} aria-labelledby={groupId}>
              {read(badge)}
              <fieldset className="prescription-photo__choices">
                <legend className="screen-label prescription-photo__neutral">찾은 후보 {group.candidates.length}개</legend>
                <div className="prescription-photo__grid">
                  {group.candidates.map((candidate) => {
                    const meta = (
                      <span className="screen-caption prescription-photo__meta">
                        {candidate.maker && <span>{candidate.maker}</span>}
                        <span>{candidate.itemSeq}</span>
                      </span>
                    )
                    if (candidate.alreadyRegistered) {
                      return (
                        <div className="prescription-photo__cand prescription-photo__cand--registered" key={candidate.itemSeq} aria-disabled="true">
                          <span className="prescription-photo__spacer" aria-hidden="true" />
                          <span className="prescription-photo__cand-body">
                            <span className="prescription-photo__cand-name">{candidate.name}</span>
                            {meta}
                            <span className="screen-caption prescription-photo__registered">
                              <Icon name="check" size="s" />
                              이미 등록한 약이에요
                            </span>
                          </span>
                        </div>
                      )
                    }
                    const checked = pick === candidate.itemSeq
                    return (
                      <label
                        className={checked ? 'prescription-photo__cand prescription-photo__cand--checked' : 'prescription-photo__cand'}
                        key={candidate.itemSeq}
                      >
                        <input type="radio" name={groupId} checked={checked} onChange={() => choose(group, candidate.itemSeq)} />
                        <span className="prescription-photo__cand-body">
                          <span className="prescription-photo__cand-name">{candidate.name}</span>
                          {meta}
                        </span>
                      </label>
                    )
                  })}
                  <div
                    className={
                      pick === 'none'
                        ? 'prescription-photo__cand prescription-photo__cand--none prescription-photo__cand--checked'
                        : 'prescription-photo__cand prescription-photo__cand--none'
                    }
                  >
                    <label className="prescription-photo__none">
                      <input type="radio" name={groupId} checked={pick === 'none'} onChange={() => choose(group, 'none')} />
                      <span className="prescription-photo__cand-body">
                        <span className="prescription-photo__cand-name">이 중에 없음</span>
                        <span className="screen-caption prescription-photo__meta">읽은 이름을 고쳐 검색하거나 다시 찍을 수 있어요.</span>
                      </span>
                    </label>
                    <span className="prescription-photo__none-actions">
                      <FixActions names={[group.readName]} onRetake={onRetake} />
                    </span>
                  </div>
                </div>
              </fieldset>
            </section>
          )
        })}
        <p className="screen-caption prescription-photo__neutral prescription-photo__note">복용 일정은 저장 후 약 상세에서 등록해 주세요.</p>
      </div>

      {saveError && (
        <Notice variant="negative" role="alert">
          <b>저장하지 못했어요.</b> 잠시 후 다시 저장해 주세요.
        </Notice>
      )}
      <PhotoSaveBar status={chosen.length > 0 ? `${chosen.length}개 골랐어요` : '아직 고른 약이 없어요'} help={help} helpId={`${id}-save`}>
        <button className="mt-btn mt-btn--assistive" type="button" onClick={onRetake}>
          <Icon name="refresh" />
          다시 찍기
        </button>
        <button className="mt-btn mt-btn--solid" type="button" disabled={chosen.length === 0 || saving} aria-describedby={`${id}-save`} onClick={save}>
          {saving && <Icon name="loader" size="s" className="icon--spin" />}
          {chosen.length > 0 ? `${chosen.length}개 저장` : '저장'}
        </button>
      </PhotoSaveBar>
    </div>
  )
}

// IMG-01 처방전 · 약봉투 사진 올리기 → IMG-03 찾는 중 → IMG-04 찾은 약 확인 · IMG-06 찾지 못함.
// 찾은 결과는 사진 번호를 주소(?photo=)에 두어 새로고침해도 30분 동안 다시 불러온다(GET /api/photos/{photoId}/candidates).
function PrescriptionPhotoPage() {
  const [searchParams, setSearchParams] = useSearchParams()
  const photoParam = searchParams.get('photo')
  const { status, profile, color, retry } = useManagedProfile()
  const id = useId()
  const [file, setFile] = useState(null)
  const [rejected, setRejected] = useState(null)
  const [busy, setBusy] = useState(false)
  const [failure, setFailure] = useState(null)
  // 주소의 사진 번호로 불러온 결과: { photoId, response } · { photoId, expired } · { photoId, failed }
  // 다시 찍기는 결과를 바로 비우지만 주소는 한 박자 늦게 바뀐다. 그사이 주소에 남은 앞 번호(before)는 다시 읽지 않는다.
  const [loaded, setLoaded] = useState(null)

  useEffect(() => {
    if (!photoParam || loaded?.photoId === photoParam || loaded?.before === photoParam) return
    let active = true
    getPhotoCandidates(photoParam)
      .then((response) => active && setLoaded({ photoId: photoParam, response }))
      .catch((error) => {
        if (!active) return
        const { status: code } = errorOf(error)
        setLoaded({ photoId: photoParam, [code === 400 || code === 404 ? 'expired' : 'failed']: true })
      })
    return () => {
      active = false
    }
  }, [photoParam, loaded?.photoId, loaded?.before])

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

  const find = async () => {
    setBusy(true)
    setFailure(null)
    try {
      const response = await identifyPhoto(profile.profileId, 'PRESCRIPTION', { image: file })
      if (response.status === 'UNREADABLE') setFailure({ type: 'unreadable' })
      else if (response.status === 'NOT_FOUND') setFailure({ type: 'notFound', names: response.drugs.map((drug) => drug.readName) })
      else if (response.photoId) {
        const photoId = String(response.photoId)
        setLoaded({ photoId, response })
        setSearchParams({ photo: photoId }, { replace: true })
      } else setFailure({ type: 'server' })
    } catch (error) {
      // 서버가 사진을 거절하면(형식 · 크기) 고르기 화면에 같은 오류를 보여 준다.
      const problem = photoProblemOf(errorOf(error).code)
      if (problem) {
        setRejected({ name: file.name, size: file.size, problem })
        setFile(null)
      } else setFailure({ type: 'server' })
    }
    setBusy(false)
  }

  // 다시 찍기: 찾은 결과의 사진은 지우고 사진 고르기로 돌아간다.
  const retake = () => {
    if (loaded?.response) forgetPhoto(loaded.photoId)
    setFile(null)
    setRejected(null)
    setFailure(null)
    setLoaded({ before: photoParam })
    setSearchParams({}, { replace: true })
  }

  if (photoParam) {
    const current = loaded?.photoId === photoParam ? loaded : null
    if (current?.response) {
      return (
        <PrescriptionResult
          key={photoParam}
          profile={profile}
          color={color}
          photoId={photoParam}
          response={current.response}
          onRetake={retake}
          onReload={(response) => setLoaded({ photoId: photoParam, response })}
          onExpired={() => setLoaded({ photoId: photoParam, expired: true })}
        />
      )
    }
    return (
      <div className="prescription-photo">
        <PhotoHead title={current?.expired ? pageTitle : '찾은 약 확인'} profile={profile} color={color} locked />
        {current?.expired && (
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
                <Link className="mt-btn mt-btn--assistive" to={paths.drugSearch}>
                  <Icon name="search" />
                  이름으로 검색
                </Link>
              </>
            }
            note={{ text: '올린 사진은 남지 않아요. 저장한 약이 있다면 약 목록에서 볼 수 있어요.' }}
          />
        )}
        {current?.failed && (
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
    const searchAction = (
      <Link className="mt-btn mt-btn--assistive" to={paths.drugSearch}>
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
    return (
      <div className="prescription-photo">
        <PhotoHead title={pageTitle} profile={profile} color={color} locked />
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
        {failure.type === 'notFound' && (
          <PhotoFailure
            badge="검색 0개"
            title="읽은 이름으로 찾은 약이 없어요"
            text="이름을 잘못 읽었을 수 있어요. 틀린 글자를 고쳐 검색하거나 이름이 잘 보이게 다시 찍어 주세요."
            actions={
              <>
                <Link className="mt-btn mt-btn--solid" to={searchLink(failure.names)}>
                  <Icon name="search" />
                  이름 고쳐 검색
                </Link>
                {retakeAction(false)}
              </>
            }
          >
            <p className="screen-label prescription-photo__neutral">사진에서 읽은 이름</p>
            <ul className="prescription-photo__names">
              {failure.names.map((readName) => (
                <li key={readName}>{readName}</li>
              ))}
            </ul>
          </PhotoFailure>
        )}
        {failure.type === 'server' && (
          <PhotoFailure
            icon="cloudOff"
            plain
            badge="조회 장애"
            title="지금 약 정보를 조회할 수 없어요"
            text="사진을 읽거나 식약처 약 정보를 조회하지 못했어요. 잠시 뒤 다시 시도해 주세요."
            actions={
              <>
                <button className="mt-btn mt-btn--solid" type="button" onClick={find}>
                  <Icon name="refresh" />
                  다시 시도
                </button>
                {searchAction}
              </>
            }
            note={{ text: '사진을 자동으로 다시 보내지 않아요. 다시 시도를 누를 때만 한 번 보내요.' }}
          />
        )}
      </div>
    )
  }

  const errorId = `${id}-error`
  return (
    <div className="prescription-photo">
      <PhotoHead title={pageTitle} profile={profile} color={color} />
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
          <Link className="mt-btn mt-btn--assistive" to={paths.drugNew}>
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
