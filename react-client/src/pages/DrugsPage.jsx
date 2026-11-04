import { useEffect, useId, useRef, useState } from 'react'
import { generatePath, Link, Navigate, useLocation } from 'react-router'
import { errorOf } from '../api/client.js'
import { deleteMyDrugs, getMyDrugs } from '../api/drugs.js'
import ConfirmDialog from '../components/ConfirmDialog.jsx'
import DrugThumb from '../components/DrugThumb.jsx'
import ErrorScreen from '../components/ErrorScreen.jsx'
import Icon from '../components/Icon.jsx'
import Notice from '../components/Notice.jsx'
import ProfileTag from '../components/ProfileTag.jsx'
import StrengthName from '../components/StrengthName.jsx'
import { useManagedProfile } from '../components/useManagedProfile.js'
import { drugSourceLabel, hasSchedule } from '../drugInfo.js'
import { paths, scheduleLink } from '../paths.js'
import { monthDay } from '../text.js'
import './DrugsPage.css'

// 서버 규칙: 여러 개 삭제는 한 번에 1~50개
const maxDelete = 50

// MED-01 약 목록. 관리할 사람의 약을 최근 등록순으로 보여 준다.
// 약을 저장하고 돌아오면(state.saved) 저장 안내와 "새로 저장" 표시를 붙인다(MED-01 등록 직후).
// 프로필이 하나도 없으면 프로필 만들기로 보낸다(docs/auth-logout.md 의 로그인 직후 규칙과 같은 기준).
// "선택"을 누르면 선택 모드가 되어 여러 약을 골라 확인 창을 거쳐 한 번에 지운다(V3-DRUG-003, 화면정의서 v3-1-bulk-delete A안).
function DrugsPage() {
  const { state } = useLocation()
  const { status, profile, color, retry: retryProfiles } = useManagedProfile()
  const profileId = profile?.profileId
  const emptyId = useId()
  const checkId = useId()
  // 받은 목록 · 오류에 프로필 번호를 함께 적어 두어, 사람을 바꾸면 이전 사람의 목록을 보이지 않는다.
  const [loaded, setLoaded] = useState(null)
  const [attempt, setAttempt] = useState(0)
  // 선택 모드와 고른 약(myDrugId)
  const [selection, setSelection] = useState(null)
  const [confirming, setConfirming] = useState(false)
  const [deleting, setDeleting] = useState(false)
  const deletingRef = useRef(false)
  const [deleteError, setDeleteError] = useState(null)
  // 사람을 바꾸면 선택 모드 · 확인 창 · 오류를 모두 끈다. 다시 돌아와도 전에 고른 약이 살아나지 않게 지운다.
  const [selectionOwner, setSelectionOwner] = useState(profileId)
  if (selectionOwner !== profileId) {
    setSelectionOwner(profileId)
    setSelection(null)
    setConfirming(false)
    setDeleteError(null)
  }
  // 여러 개 삭제 결과 안내: 'done'(지운 수) · 'stale'(404, 아무것도 지우지 않음)
  const [outcome, setOutcome] = useState(null)
  // 선택 모드를 켜고 끌 때 사라진 버튼 대신 포커스를 옮길 곳
  const selectButtonRef = useRef(null)
  const allCheckRef = useRef(null)
  const focusNext = useRef(null)
  // 지금 보이는 사람. 늦게 온 응답이 그사이 바뀐 사람의 화면을 건드리지 않게 비교한다.
  const shownProfileRef = useRef(profileId)

  useEffect(() => {
    if (!profileId) return
    let active = true
    getMyDrugs(profileId)
      .then((list) => active && setLoaded({ profileId, attempt, list }))
      .catch((error) => active && setLoaded({ profileId, attempt, status: errorOf(error).status ?? 0 }))
    return () => {
      active = false
    }
  }, [profileId, attempt])

  useEffect(() => {
    shownProfileRef.current = profileId
  })

  // 옮길 곳이 아직 그려지지 않았으면(앞선 상태가 먼저 그려진 때) 표시를 남겨 두고 그려진 뒤에 옮긴다.
  useEffect(() => {
    const targets = { all: allCheckRef, select: selectButtonRef }
    const target = targets[focusNext.current]?.current
    if (!target) return
    target.focus()
    focusNext.current = null
  })

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

  const current = loaded?.profileId === profileId && loaded.attempt === attempt ? loaded : null
  if (current?.status === 403) return <ErrorScreen kind="forbidden" />
  if (current?.status === 404) return <ErrorScreen kind="notFound" />
  const list = current?.list ?? null
  const saved = state?.profileId === profileId ? (state.saved ?? []) : []
  const savedIds = new Set(saved.map((drug) => drug.myDrugId))
  // 일정을 저장하고 돌아오면 달력으로 잇는다(design/v3-dec-034-web-ux.md 6).
  const scheduleSavedMonth = state?.profileId === profileId ? state.scheduleSavedMonth : null
  const name = profile.profileName

  // 고른 약은 지금 목록에 남은 것만 센다(다시 불러온 목록에서 빠진 약은 저절로 풀린다).
  const selecting = selection?.profileId === profileId && list?.length > 0
  const pickedIds = new Set(selecting ? selection.ids : [])
  const picked = selecting ? list.filter((drug) => pickedIds.has(drug.myDrugId)) : []
  const count = picked.length
  const tooMany = count > maxDelete
  const allPicked = selecting && count === list.length
  const shownOutcome = outcome?.profileId === profileId ? outcome : null
  let selectStatus = count > 0 ? `${count}개 골랐어요` : '삭제할 약을 골라 주세요'
  if (tooMany) selectStatus = `${count}개 골랐어요. 한 번에 ${maxDelete}개까지 지울 수 있어요.`

  const startSelecting = () => {
    setSelection({ profileId, ids: [] })
    setOutcome(null)
    focusNext.current = 'all'
  }
  const stopSelecting = () => {
    setSelection(null)
    setOutcome(null)
    focusNext.current = 'select'
  }
  const toggle = (myDrugId) => {
    const ids = pickedIds.has(myDrugId) ? [...pickedIds].filter((id) => id !== myDrugId) : [...pickedIds, myDrugId]
    setSelection({ profileId, ids })
  }
  // 전체 선택은 모두 고름 · 모두 풂을 오간다.
  const toggleAll = () => setSelection({ profileId, ids: allPicked ? [] : list.map((drug) => drug.myDrugId) })

  // 삭제 뒤 목록을 다시 받는다. 받는 동안 지금 목록을 그대로 두고, 그사이 사람을 바꿨으면 버린다.
  // 받은 목록을 돌려준다(못 받았으면 null). 연결이 끊긴 삭제는 이 목록으로 결과를 맞춘다.
  const reload = () => {
    const keep = (next) => setLoaded((previous) => (previous?.profileId === profileId ? next : previous))
    return getMyDrugs(profileId)
      .then((list) => {
        keep({ profileId, attempt, list })
        return list
      })
      .catch((error) => {
        keep({ profileId, attempt, status: errorOf(error).status ?? 0 })
        return null
      })
  }

  const remove = async () => {
    if (deletingRef.current) return
    deletingRef.current = true
    setDeleting(true)
    setDeleteError(null)
    try {
      const response = await deleteMyDrugs(profileId, picked.map((drug) => drug.myDrugId))
      setConfirming(false)
      setSelection(null)
      setOutcome({ profileId, kind: 'done', count: response.deletedCount })
      focusNext.current = 'select'
      reload()
    } catch (error) {
      const { status: code } = errorOf(error)
      // 401 은 client 가 로그인 만료로 처리한다.
      if (code === 404) {
        // 서버가 아무것도 지우지 않았다. 선택 모드는 두고 고른 것만 비운다.
        setConfirming(false)
        setSelection({ profileId, ids: [] })
        setOutcome({ profileId, kind: 'stale' })
        focusNext.current = 'all'
        reload()
      } else if (code === undefined) {
        // 응답을 받지 못해 지워졌는지 모르므로 목록을 다시 받아 결과를 맞춘다. 맞출 때까지 삭제 버튼은 잠가 두어
        // 이미 지워진 약을 다시 보내지 않게 한다. 고른 약이 모두 목록에서 빠졌으면 서버가 지운 것이라 확인 창을 닫고
        // 완료로 알린다(남은 약을 새로 골라도 확인 창이 저절로 다시 뜨지 않게). 남은 약이 있으면 그 약만으로 창을 두고
        // 오류를 보인다. 목록도 받지 못하면 창을 닫고 목록 오류 안내에 맡긴다. 그사이 사람을 바꿨으면 아무것도 하지 않는다.
        const sent = picked.map((drug) => drug.myDrugId)
        const list = await reload()
        if (shownProfileRef.current !== profileId) return
        if (!list) {
          setConfirming(false)
        } else if (list.some((drug) => sent.includes(drug.myDrugId))) {
          setDeleteError('연결이 끊겨 삭제하지 못했어요. 다시 시도해 주세요.')
        } else {
          setConfirming(false)
          setSelection(null)
          setOutcome({ profileId, kind: 'done', count: sent.length })
          focusNext.current = 'select'
        }
      } else if (code !== 401) {
        setDeleteError('요청을 처리하지 못했어요. 다시 시도해 주세요.')
      }
    } finally {
      deletingRef.current = false
      setDeleting(false)
    }
  }

  return (
    <div className="drugs-page">
      {shownOutcome?.kind === 'done' && (
        <Notice variant="positive" role="status">
          <b>약 {shownOutcome.count}개를 삭제했어요.</b>
          <br />
          함께 있던 복약 일정과 알림도 지웠어요.
        </Notice>
      )}
      {shownOutcome?.kind === 'stale' && (
        <Notice variant="caution" role="alert">
          <b>아무것도 삭제하지 않았어요.</b>
          <br />
          고른 약 중에 이미 삭제된 약이 있었어요. 목록을 다시 불러왔으니 다시 골라 주세요.
        </Notice>
      )}
      {saved.length > 0 && (
        <Notice
          variant="positive"
          role="status"
          action={
            <span className="drugs-page__saved-actions">
              <Link className="mt-btn mt-btn--assistive mt-btn--md" to={paths.chat}>
                <Icon name="chat" size="s" />
                같이 먹어도 되는지 묻기
              </Link>
              <Link
                className="mt-btn mt-btn--assistive mt-btn--md"
                to={scheduleLink(paths.scheduleNew, { profileId, myDrugId: saved.length === 1 ? saved[0].myDrugId : null, from: 'drugs' })}
              >
                <Icon name="calendar" size="s" />
                일정 추가
              </Link>
            </span>
          }
        >
          <b>약 {saved.length}개를 저장했어요.</b>
          <br />
          새로 저장한 약은 아직 복약 일정이 없어요. 일정을 등록하면 복용 달력에서 복용 시간을 볼 수 있어요.
        </Notice>
      )}
      {scheduleSavedMonth && (
        <Notice
          variant="positive"
          role="status"
          action={
            <Link className="mt-btn mt-btn--assistive mt-btn--md" to={`${paths.calendar}?month=${scheduleSavedMonth}`}>
              <Icon name="calendar" size="s" />
              복용 달력 보기
            </Link>
          }
        >
          <b>일정을 저장했어요.</b>
          <br />
          복용 달력에서 먹는 날과 시간을 볼 수 있어요.
        </Notice>
      )}

      <div className="drugs-page__head">
        <div className="drugs-page__title">
          <h1 className="screen-title">약 목록</h1>
          <div className="drugs-page__meta">
            <ProfileTag color={color}>{name}</ProfileTag>
            {list && <span className="screen-body drugs-page__lead">약 {list.length}개</span>}
          </div>
        </div>
        {list?.length > 0 && !selecting && (
          <div className="drugs-page__head-actions">
            <button className="mt-btn mt-btn--assistive" type="button" ref={selectButtonRef} onClick={startSelecting}>
              <Icon name="check" />
              선택
            </button>
            <Link className="mt-btn mt-btn--solid" to={paths.drugNew}>
              <Icon name="plus" />약 등록
            </Link>
          </div>
        )}
      </div>

      {current && !list && (
        <Notice
          variant="negative"
          role="alert"
          action={
            <button className="mt-btn mt-btn--assistive mt-btn--md" type="button" onClick={() => setAttempt((count) => count + 1)}>
              다시 시도
            </button>
          }
        >
          약 목록을 불러오지 못했어요. 잠시 후 다시 시도해 주세요.
        </Notice>
      )}

      {list?.length === 0 && (
        <section className="mt-card drugs-page__empty" aria-labelledby={emptyId}>
          <span className="drugs-page__empty-icon">
            <Icon name="pill" size="l" />
          </span>
          <div className="drugs-page__empty-text">
            <h2 className="screen-section" id={emptyId}>
              등록된 약이 없어요
            </h2>
            <p className="screen-body drugs-page__lead">처방전이나 약봉투 사진, 알약 사진, 약 이름으로 {name}의 약을 등록할 수 있어요.</p>
          </div>
          <Link className="mt-btn mt-btn--solid drugs-page__empty-action" to={paths.drugNew}>
            <Icon name="plus" />약 등록
          </Link>
        </section>
      )}

      {list?.length > 0 && (
        <>
          <section className="mt-card mt-card--flush drugs-page__card" aria-label={`${name}의 약`}>
            <div className="drugs-page__scroll">
              <table className="drugs-page__table">
                <thead>
                  <tr>
                    {selecting && (
                      <th className="screen-label drugs-page__col-check" scope="col">
                        <label className="drugs-page__check">
                          <input
                            type="checkbox"
                            ref={(node) => {
                              allCheckRef.current = node
                              if (node) node.indeterminate = count > 0 && !allPicked
                            }}
                            checked={allPicked}
                            aria-label="전체 선택"
                            onChange={toggleAll}
                          />
                        </label>
                      </th>
                    )}
                    <th className="screen-label drugs-page__col-drug" scope="col">
                      약
                    </th>
                    <th className="screen-label drugs-page__col-source" scope="col">
                      등록 방법
                    </th>
                    <th className="screen-label" scope="col">
                      복약 일정
                    </th>
                  </tr>
                </thead>
                <tbody>
                  {list.map((drug) => (
                    <tr key={drug.myDrugId} className={pickedIds.has(drug.myDrugId) ? 'drugs-page__row--picked' : undefined}>
                      {selecting && (
                        <td className="drugs-page__col-check">
                          <label className="drugs-page__check">
                            <input
                              type="checkbox"
                              id={`${checkId}-${drug.myDrugId}`}
                              checked={pickedIds.has(drug.myDrugId)}
                              aria-label={`${drug.name} 고르기`}
                              onChange={() => toggle(drug.myDrugId)}
                            />
                          </label>
                        </td>
                      )}
                      <td>
                        <div className="drugs-page__drug">
                          <DrugThumb imageUrl={drug.imageUrl} />
                          <div className="drugs-page__stack">
                            <div className="drugs-page__name-line">
                              {/* 선택 모드에서는 약 이름이 상세로 가지 않고 그 행을 고른다. */}
                              {selecting ? (
                                <label className="screen-item drugs-page__name" htmlFor={`${checkId}-${drug.myDrugId}`}>
                                  <StrengthName name={drug.name} baseName={drug.baseName} strengthText={drug.strengthText} />
                                </label>
                              ) : (
                                <Link
                                  className="screen-item drugs-page__name"
                                  to={generatePath(paths.drugDetail, { myDrugId: String(drug.myDrugId) })}
                                >
                                  <StrengthName name={drug.name} baseName={drug.baseName} strengthText={drug.strengthText} />
                                </Link>
                              )}
                              {savedIds.has(drug.myDrugId) && <span className="mt-badge mt-badge--primary">새로 저장</span>}
                            </div>
                            {drug.maker && <span className="screen-caption drugs-page__muted">{drug.maker}</span>}
                          </div>
                        </div>
                      </td>
                      <td>
                        <div className="drugs-page__stack">
                          <span>{drugSourceLabel(drug)}</span>
                          <span className="screen-caption drugs-page__muted">{monthDay(drug.registeredAt)} 등록</span>
                        </div>
                      </td>
                      <td>
                        {hasSchedule(drug) ? (
                          <div className="drugs-page__schedule">
                            <div className="drugs-page__times">
                              {drug.doseTimes.map((time) => (
                                <span className="mt-badge drugs-page__time" key={time}>
                                  {time}
                                </span>
                              ))}
                            </div>
                            {drug.dosePerTime != null && <span className="drugs-page__dose">1회 {drug.dosePerTime}개</span>}
                            <span className="screen-caption drugs-page__muted">
                              {monthDay(drug.startDate)}부터 {monthDay(drug.endDate)}까지
                            </span>
                          </div>
                        ) : (
                          <div className="drugs-page__no-schedule">
                            <span className="mt-badge">일정 없음</span>
                            {/* 선택 모드에서는 잘못 누르지 않게 행 안 버튼을 숨긴다. */}
                            {!selecting && (
                              <Link
                                className="mt-btn mt-btn--assistive mt-btn--md"
                                to={scheduleLink(paths.scheduleNew, { profileId, myDrugId: drug.myDrugId, from: 'drugs' })}
                                aria-label={`${drug.name} 일정 등록`}
                              >
                                <Icon name="calendar" size="s" />
                                일정 등록
                              </Link>
                            )}
                          </div>
                        )}
                      </td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
          </section>
          {selecting && (
            <div className="mt-card drugs-page__select-bar">
              <div className="drugs-page__select-text">
                <p className="screen-item" role="status">
                  {selectStatus}
                </p>
                <p className="screen-caption drugs-page__muted">고른 약의 복약 일정과 알림도 함께 지워져요.</p>
              </div>
              <div className="drugs-page__select-actions">
                <button className="mt-btn mt-btn--assistive" type="button" onClick={stopSelecting}>
                  취소
                </button>
                <button
                  className="mt-btn mt-btn--danger"
                  type="button"
                  disabled={count === 0 || tooMany}
                  onClick={() => {
                    setDeleteError(null)
                    setConfirming(true)
                  }}
                >
                  <Icon name="trash" size="s" />
                  {count > 0 ? `${count}개 삭제` : '삭제'}
                </button>
              </div>
            </div>
          )}
          <p className="screen-caption drugs-page__muted">약 이름을 누르면 공식 정보와 {name}의 복용정보를 볼 수 있어요.</p>
        </>
      )}

      {confirming && count > 0 && (
        <ConfirmDialog
          title={`약 ${count}개를 삭제할까요?`}
          lead={
            <ul className="drugs-page__confirm-list">
              {picked.map((drug) => (
                <li className="screen-body" key={drug.myDrugId}>
                  {drug.name}
                </li>
              ))}
            </ul>
          }
          description="이 약들의 복약 일정과 알림도 함께 지워져요. 삭제한 뒤에는 되돌릴 수 없어요."
          danger
          confirmLabel={`${count}개 삭제`}
          confirmIcon="trash"
          busy={deleting}
          error={deleteError}
          onConfirm={remove}
          onClose={() => {
            setConfirming(false)
            setDeleteError(null)
          }}
        />
      )}
    </div>
  )
}

export default DrugsPage
