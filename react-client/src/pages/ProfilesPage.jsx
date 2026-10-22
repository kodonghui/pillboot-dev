import { useEffect, useId, useRef, useState } from 'react'
import { flushSync } from 'react-dom'
import { generatePath, Link } from 'react-router'
import { errorOf } from '../api/client.js'
import { deleteProfile, getProfiles } from '../api/profiles.js'
import ConfirmDialog from '../components/ConfirmDialog.jsx'
import Icon from '../components/Icon.jsx'
import Notice from '../components/Notice.jsx'
import ProfileDot from '../components/ProfileDot.jsx'
import { paths } from '../paths.js'
import { genderLabels, pregnancyLabel, profileColor, profileColorName, relationLabels } from '../profileInfo.js'
import { chooseProfileId, useAuthStore } from '../store/auth.js'
import { roAfter } from '../text.js'
import './ProfilesPage.css'

// PRF-01 프로필 관리. 표에서 관리할 사람을 바꾸고(관리하기), 수정(PRF-03) · 삭제 확인으로 간다.
// 불러온 목록에 저장된 관리할 사람이 없으면 docs/auth-logout.md 규칙(본인 → 맨 위)으로 다시 정한다.
function ProfilesPage() {
  const profileId = useAuthStore((state) => state.profileId)
  const setProfileId = useAuthStore((state) => state.setProfileId)
  const syncProfileId = useAuthStore((state) => state.syncProfileId)
  const titleId = useId()
  const rowId = useId()
  const tableRef = useRef(null)
  const [profiles, setProfiles] = useState(null)
  const [failed, setFailed] = useState(false)
  const [attempt, setAttempt] = useState(0)
  // 삭제 확인창을 연 프로필과 그 창의 상태. error: 'last'(마지막 프로필) · 'failed'(그 밖의 실패)
  const [target, setTarget] = useState(null)
  const [deleting, setDeleting] = useState(false)
  const [deleteError, setDeleteError] = useState(null)

  useEffect(() => {
    let active = true
    getProfiles()
      .then((data) => {
        if (!active) return
        setProfiles(data)
        syncProfileId(data)
      })
      .catch(() => active && setFailed(true))
    return () => {
      active = false
    }
  }, [attempt, syncProfileId])

  const retry = () => {
    setFailed(false)
    setAttempt((count) => count + 1)
  }

  // "관리하기" 버튼이 "지금 관리 중" 표시로 바뀌므로 포커스를 그 표시로 옮긴다.
  const manage = (profile) => {
    flushSync(() => setProfileId(profile.profileId))
    document.getElementById(`${rowId}-${profile.profileId}-now`)?.focus()
  }

  const openDelete = (profile) => {
    setDeleteError(null)
    setTarget(profile)
  }

  const closeDelete = () => {
    setTarget(null)
    setDeleteError(null)
  }

  const remove = async () => {
    const removed = target
    setDeleting(true)
    setDeleteError(null)
    try {
      await deleteProfile(removed.profileId)
    } catch (error) {
      setDeleteError(errorOf(error).code === 'LAST_PROFILE' ? 'last' : 'failed')
      setDeleting(false)
      return
    }
    const remaining = profiles.filter((profile) => profile.profileId !== removed.profileId)
    // 지운 줄의 버튼으로는 포커스를 돌려줄 수 없어 표로 옮긴다.
    flushSync(() => {
      setProfiles(remaining)
      syncProfileId(remaining)
      setTarget(null)
      setDeleting(false)
    })
    tableRef.current.focus()
  }

  const head = (
    <div className="profiles-page__head">
      <div className="profiles-page__intro">
        <h1 className="screen-title" id={titleId}>
          프로필 관리
        </h1>
        <p className="screen-body profiles-page__lead">
          약 목록과 챗봇은 프로필마다 따로예요. 복용 달력에는 모든 프로필 일정이 색으로 함께 보여요.
        </p>
      </div>
      <Link className="mt-btn mt-btn--solid" to={paths.profileNew}>
        <Icon name="plus" />
        프로필 추가
      </Link>
    </div>
  )

  if (failed) {
    return (
      <div className="profiles-page">
        {head}
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
      </div>
    )
  }

  // 삭제 확인창: 마지막 프로필이면 막고, 지금 관리 중인 프로필이면 바뀔 사람을 미리 알린다.
  const lastOne = target !== null && (profiles.length === 1 || deleteError === 'last')
  const remaining = target ? profiles.filter((profile) => profile.profileId !== target.profileId) : []
  const nextId = target?.profileId === profileId ? chooseProfileId(remaining, null) : null
  const next = remaining.find((profile) => profile.profileId === nextId)
  let error = null
  if (lastOne) error = '마지막 프로필은 삭제할 수 없습니다.'
  else if (deleteError === 'failed') error = '삭제하지 못했어요. 잠시 후 다시 시도해 주세요.'

  return (
    <div className="profiles-page">
      {head}
      {profiles && (
        <section
          ref={tableRef}
          className="mt-card mt-card--flush profiles-page__card"
          tabIndex={-1}
          aria-label={`프로필 ${profiles.length}개`}
        >
          <div className="profiles-page__scroll">
            <table className="profiles-page__table">
              <thead>
                <tr>
                  <th className="screen-label" scope="col">
                    이름
                  </th>
                  <th className="screen-label" scope="col">
                    관계
                  </th>
                  <th className="screen-label" scope="col">
                    출생연도
                  </th>
                  <th className="screen-label" scope="col">
                    성별
                  </th>
                  <th className="screen-label" scope="col">
                    임신 여부
                  </th>
                  <th className="screen-label" scope="col">
                    복용 달력 색(자동)
                  </th>
                  <th className="screen-label" scope="col">
                    <span className="profiles-page__hidden">행동</span>
                  </th>
                </tr>
              </thead>
              <tbody>
                {profiles.map((profile) => {
                  const color = profileColor(profiles, profile.profileId)
                  return (
                    <tr key={profile.profileId}>
                      <th className="screen-item profiles-page__name" scope="row">
                        {profile.profileName}
                      </th>
                      <td>{relationLabels[profile.relationshipType] ?? profile.relationshipType}</td>
                      <td className="profiles-page__number">{profile.profileBirthYear}년</td>
                      <td>{genderLabels[profile.profileGender] ?? profile.profileGender}</td>
                      <td>{pregnancyLabel(profile.profilePregnant)}</td>
                      <td>
                        <span className="profiles-page__color">
                          <ProfileDot color={color} />
                          {profileColorName(color)}
                        </span>
                      </td>
                      <td>
                        <div className="profiles-page__actions">
                          {profile.profileId === profileId ? (
                            <span
                              className="mt-badge mt-badge--primary profiles-page__now"
                              id={`${rowId}-${profile.profileId}-now`}
                              tabIndex={-1}
                            >
                              <Icon name="check" size="s" />
                              지금 관리 중
                            </span>
                          ) : (
                            <button
                              className="mt-btn mt-btn--assistive mt-btn--md"
                              type="button"
                              aria-label={`${profile.profileName} 관리하기`}
                              onClick={() => manage(profile)}
                            >
                              관리하기
                            </button>
                          )}
                          <Link
                            className="mt-btn mt-btn--ghost mt-btn--md"
                            to={generatePath(paths.profileEdit, { profileId: String(profile.profileId) })}
                            aria-label={`${profile.profileName} 프로필 수정`}
                          >
                            <Icon name="pencil" size="s" />
                            수정
                          </Link>
                          <button
                            className="mt-btn mt-btn--ghost mt-btn--md profiles-page__delete"
                            type="button"
                            aria-haspopup="dialog"
                            aria-label={`${profile.profileName} 프로필 삭제`}
                            onClick={() => openDelete(profile)}
                          >
                            <Icon name="trash" size="s" />
                            삭제
                          </button>
                        </div>
                      </td>
                    </tr>
                  )
                })}
              </tbody>
            </table>
          </div>
        </section>
      )}
      {target && (
        <ConfirmDialog
          title={`${target.profileName} 프로필을 삭제할까요?`}
          description="이 프로필에 등록된 약과 챗봇 대화 기록도 함께 삭제됩니다. 삭제 후에는 되돌릴 수 없습니다."
          danger
          confirmLabel="프로필 삭제"
          confirmIcon="trash"
          confirmDisabled={deleting || lastOne}
          error={error}
          onConfirm={remove}
          onClose={closeDelete}
        >
          {next && !lastOne && (
            <Notice variant="caution" icon="info">
              지금 관리 중인 프로필이에요. 삭제하면 관리할 사람이{' '}
              <span className="profiles-page__nowrap">
                {next.profileName}({relationLabels[next.relationshipType]}){roAfter(next.profileName)}
              </span>{' '}
              바뀌어요.
            </Notice>
          )}
        </ConfirmDialog>
      )}
    </div>
  )
}

export default ProfilesPage
