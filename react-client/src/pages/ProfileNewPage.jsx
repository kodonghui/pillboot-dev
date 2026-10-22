import { useEffect, useId, useState } from 'react'
import { Link, useNavigate } from 'react-router'
import { createProfile, getProfiles } from '../api/profiles.js'
import Icon from '../components/Icon.jsx'
import Notice from '../components/Notice.jsx'
import ProfileForm from '../components/ProfileForm.jsx'
import { paths } from '../paths.js'
import { useAuthStore } from '../store/auth.js'
import './ProfileFormPage.css'

// PRF-02 프로필 만들기. 프로필이 하나도 없으면 "첫 프로필"(가입 직후), 있으면 프로필 관리의 "프로필 추가"로 보여 준다.
// 첫 프로필은 저장하면 관리할 사람으로 정하고 홈으로 간다.
function ProfileNewPage() {
  const navigate = useNavigate()
  const setProfileId = useAuthStore((state) => state.setProfileId)
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

  const first = profiles.length === 0

  const save = async (request) => {
    const created = await createProfile(request)
    if (first) {
      setProfileId(created.profileId)
      navigate(paths.home, { replace: true })
    } else {
      navigate(paths.profiles, { replace: true })
    }
  }

  return (
    <div className="profile-form-page">
      {first ? (
        <div className="profile-form-page__intro">
          <h1 className="screen-title" id={titleId}>
            프로필 만들기
          </h1>
          <p className="screen-body profile-form-page__lead">
            본인이나 가족의 프로필을 만들면 약을 등록하고 복용 일정을 정할 수 있어요.
          </p>
        </div>
      ) : (
        <div className="profile-form-page__head">
          <Link className="mt-btn mt-btn--ghost profile-form-page__back" to={paths.profiles}>
            <Icon name="chevronLeft" size="s" />
            프로필 관리로
          </Link>
          <h1 className="screen-title" id={titleId}>
            프로필 만들기
          </h1>
        </div>
      )}
      <ProfileForm
        titleId={titleId}
        selfTaken={profiles.some((profile) => profile.relationshipType === 'SELF')}
        cancelTo={first ? null : paths.profiles}
        onSubmit={save}
      >
        <Notice>저장하면 복용 달력 색이 자동으로 정해져요.</Notice>
      </ProfileForm>
    </div>
  )
}

export default ProfileNewPage
