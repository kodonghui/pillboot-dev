import { useEffect, useId, useState } from 'react'
import { Link, useNavigate, useParams } from 'react-router'
import { getProfiles, updateProfile } from '../api/profiles.js'
import ErrorScreen from '../components/ErrorScreen.jsx'
import Icon from '../components/Icon.jsx'
import Notice from '../components/Notice.jsx'
import ProfileForm from '../components/ProfileForm.jsx'
import ProfileTag from '../components/ProfileTag.jsx'
import { paths } from '../paths.js'
import { profileColor } from '../profileInfo.js'
import './ProfileFormPage.css'

// PRF-03 프로필 수정. 프로필 한 건을 받는 API가 없어 목록(GET /api/profiles)에서 찾는다.
// 저장하면 프로필 관리로 돌아간다.
function ProfileEditPage() {
  const navigate = useNavigate()
  const profileId = Number(useParams().profileId)
  const titleId = useId()
  const [profiles, setProfiles] = useState(null)
  const [failed, setFailed] = useState(false)
  const [attempt, setAttempt] = useState(0)

  useEffect(() => {
    let active = true
    getProfiles()
      .then((data) => active && setProfiles(data))
      .catch(() => active && setFailed(true))
    return () => {
      active = false
    }
  }, [attempt])

  const retry = () => {
    setFailed(false)
    setAttempt((count) => count + 1)
  }

  if (failed) {
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
  if (!profiles) return null

  const profile = profiles.find((item) => item.profileId === profileId)
  if (!profile) return <ErrorScreen kind="notFound" />

  const color = profileColor(profiles, profileId)

  const save = async (request) => {
    await updateProfile(profileId, request)
    navigate(paths.profiles, { replace: true })
  }

  return (
    <div className="profile-form-page">
      <div className="profile-form-page__head">
        <Link className="mt-btn mt-btn--ghost profile-form-page__back" to={paths.profiles}>
          <Icon name="chevronLeft" size="s" />
          프로필 관리로
        </Link>
        <div className="profile-form-page__title">
          <h1 className="screen-title" id={titleId}>
            프로필 수정
          </h1>
          <div className="profile-form-page__tags">
            <ProfileTag color={color}>{profile.profileName}</ProfileTag>
          </div>
        </div>
      </div>
      <ProfileForm
        key={profileId}
        titleId={titleId}
        profile={profile}
        selfTaken={profiles.some((item) => item.relationshipType === 'SELF' && item.profileId !== profileId)}
        cancelTo={paths.profiles}
        onSubmit={save}
      >
        <Notice>복용 달력 색은 자동으로 정해져요.</Notice>
        <Notice variant="info">
          바꾼 값은 다음 새 대화부터 약봉이 답변에 쓰여요. 약 목록, 일정, 대화방은 그대로예요.
        </Notice>
      </ProfileForm>
    </div>
  )
}

export default ProfileEditPage
