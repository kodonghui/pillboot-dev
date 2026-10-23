import { useId, useRef, useState } from 'react'
import { Link } from 'react-router'
import ChipGroup from './ChipGroup.jsx'
import FieldError from './FieldError.jsx'
import Icon from './Icon.jsx'
import Notice from './Notice.jsx'
import SelectMenu from './SelectMenu.jsx'
import { familyRelations, genderLabels, relationLabels } from '../profileInfo.js'
import './ProfileForm.css'

const firstYear = 1900
// 키 · 몸무게는 서버에서 int32 정수다.
const intMax = 2147483647

const genderOptions = ['FEMALE', 'MALE', 'UNKNOWN'].map((value) => ({ value, label: genderLabels[value] }))
const pregnancyOptions = [
  { value: false, label: '해당 없음' },
  { value: true, label: '임신 중' },
]
const relationOptions = familyRelations.map((value) => ({ value, label: relationLabels[value] }))
const bodySizeHelp = '키 · 몸무게는 입력한다면 0보다 큰 정수로 적어 주세요.'

// 만들기는 아무것도 골라 두지 않는다(목업 PRF-02-01 · 1). 본인 프로필이 이미 있으면 "가족"을 골라 둔다.
function valuesOf(profile, selfTaken) {
  if (!profile) {
    const kind = selfTaken ? 'family' : null
    return { kind, relation: null, name: '', birthYear: '', gender: null, pregnant: null, height: '', weight: '' }
  }
  const self = profile.relationshipType === 'SELF'
  return {
    kind: self ? 'self' : 'family',
    relation: self ? null : profile.relationshipType,
    name: profile.profileName,
    birthYear: String(profile.profileBirthYear),
    gender: profile.profileGender,
    pregnant: profile.profilePregnant,
    height: profile.profileHeight == null ? '' : String(profile.profileHeight),
    weight: profile.profileWeight == null ? '' : String(profile.profileWeight),
  }
}

const isOptionalCount = (value) => {
  const text = value.trim()
  return text === '' || (/^\d+$/.test(text) && Number(text) > 0 && Number(text) <= intMax)
}

function check(values, thisYear) {
  const year = values.birthYear.trim()
  let birthYear = null
  if (!year) birthYear = '출생연도를 입력해 주세요.'
  else if (!/^\d{4}$/.test(year) || Number(year) < firstYear || Number(year) > thisYear) {
    birthYear = `${firstYear}년부터 ${thisYear}년까지 입력해 주세요.`
  }
  return {
    kind: values.kind ? null : '관계를 선택해 주세요.',
    relation: values.kind === 'family' && !values.relation ? '상세 관계를 골라 주세요.' : null,
    name: values.name.trim() ? null : '이름을 입력해 주세요.',
    birthYear,
    gender: values.gender ? null : '성별을 선택해 주세요.',
    pregnant: values.pregnant === null ? '임신 여부를 선택해 주세요.' : null,
    height: !isOptionalCount(values.height),
    weight: !isOptionalCount(values.weight),
  }
}

// 서버 ProfileCreateRequest · ProfileUpdateRequest 모양
function requestOf(values) {
  return {
    profileName: values.name.trim(),
    relationshipType: values.kind === 'self' ? 'SELF' : values.relation,
    profileBirthYear: Number(values.birthYear.trim()),
    profileGender: values.gender,
    profileHeight: values.height.trim() ? Number(values.height.trim()) : null,
    profileWeight: values.weight.trim() ? Number(values.weight.trim()) : null,
    profilePregnant: values.pregnant,
  }
}

// PRF-02 프로필 만들기와 PRF-03 프로필 수정이 함께 쓰는 입력 양식.
// profile: 수정할 프로필(만들기면 없음). selfTaken: 다른 프로필이 이미 본인이면 "본인"을 막는다.
// cancelTo: "취소"가 갈 주소(첫 프로필은 없음). children: 저장 버튼 위 안내 줄.
// onSubmit(request) 가 실패하면 입력을 지우지 않고 저장 실패 안내를 보여 준다.
function ProfileForm({ titleId, profile, selfTaken, cancelTo, onSubmit, children }) {
  const fieldId = useId()
  const [values, setValues] = useState(() => valuesOf(profile, selfTaken))
  const [errors, setErrors] = useState({})
  const [saving, setSaving] = useState(false)
  const [failed, setFailed] = useState(false)
  const kindRef = useRef(null)
  const relationRef = useRef(null)
  const nameRef = useRef(null)
  const yearRef = useRef(null)
  const genderRef = useRef(null)
  const pregnantRef = useRef(null)
  const heightRef = useRef(null)
  const weightRef = useRef(null)

  const set = (key, value) => {
    setValues((current) => ({ ...current, [key]: value }))
    setErrors((current) => ({ ...current, [key]: null }))
  }
  const change = (key) => (event) => set(key, event.target.value)
  const chooseKind = (kind) => {
    set('kind', kind)
    setErrors((current) => ({ ...current, relation: null }))
  }

  const submit = async (event) => {
    event.preventDefault()
    const nextErrors = check(values, new Date().getFullYear())
    setErrors(nextErrors)
    setFailed(false)
    const invalid = [
      [nextErrors.kind, kindRef],
      [nextErrors.relation, relationRef],
      [nextErrors.name, nameRef],
      [nextErrors.birthYear, yearRef],
      [nextErrors.gender, genderRef],
      [nextErrors.pregnant, pregnantRef],
      [nextErrors.height, heightRef],
      [nextErrors.weight, weightRef],
    ].find(([error]) => error)
    if (invalid) {
      invalid[1].current.focus()
      return
    }

    // 성공하면 화면이 다른 주소로 가므로 저장 버튼은 잠근 채로 둔다.
    setSaving(true)
    try {
      await onSubmit(requestOf(values))
    } catch {
      setFailed(true)
      setSaving(false)
    }
  }

  const describe = (...ids) => ids.filter(Boolean).join(' ') || undefined
  const errorId = (key) => (errors[key] ? `${fieldId}-${key}-error` : null)
  const bodySizeInvalid = errors.height || errors.weight

  return (
    <form className="mt-card mt-card--wide profile-form" aria-labelledby={titleId} noValidate onSubmit={submit}>
      <ChipGroup
        legend="관계"
        mark="(필수)"
        options={[
          { value: 'self', label: '본인', disabled: selfTaken, describedBy: selfTaken ? `${fieldId}-self` : undefined },
          { value: 'family', label: '가족' },
        ]}
        value={values.kind}
        onChange={chooseKind}
        describedBy={describe(errorId('kind'))}
        firstRef={kindRef}
      >
        {selfTaken && (
          <p className="mt-field__help" id={`${fieldId}-self`}>
            본인 프로필은 하나만 만들 수 있어요.
          </p>
        )}
        {errors.kind && <FieldError id={errorId('kind')}>{errors.kind}</FieldError>}
      </ChipGroup>

      {values.kind === 'family' && (
        <div className="mt-field">
          <label className="mt-field__label" htmlFor={`${fieldId}-relation`}>
            상세 관계<span className="mt-field__req">(필수)</span>
          </label>
          <SelectMenu
            ref={relationRef}
            id={`${fieldId}-relation`}
            value={values.relation}
            options={relationOptions}
            placeholder="상세 관계를 골라 주세요"
            listLabel="상세 관계 선택지"
            invalid={Boolean(errors.relation)}
            describedBy={describe(errorId('relation'))}
            onChange={(relation) => set('relation', relation)}
          />
          {errors.relation && <FieldError id={errorId('relation')}>{errors.relation}</FieldError>}
        </div>
      )}

      <div className="mt-field">
        <label className="mt-field__label" htmlFor={`${fieldId}-name`}>
          이름<span className="mt-field__req">(필수)</span>
        </label>
        <input
          ref={nameRef}
          className="mt-input profile-form__name"
          id={`${fieldId}-name`}
          type="text"
          maxLength={10}
          placeholder="예: 할머니"
          value={values.name}
          onChange={change('name')}
          aria-invalid={errors.name ? true : undefined}
          aria-describedby={describe(errorId('name'), `${fieldId}-name-count`)}
        />
        <div className="profile-form__name-help">
          {errors.name && <FieldError id={errorId('name')}>{errors.name}</FieldError>}
          <p className="mt-field__help profile-form__count" id={`${fieldId}-name-count`}>
            {values.name.length} / 10
          </p>
        </div>
      </div>

      <div className="mt-field">
        <label className="mt-field__label" htmlFor={`${fieldId}-year`}>
          출생연도<span className="mt-field__req">(필수)</span>
        </label>
        <div className="profile-form__unit">
          <input
            ref={yearRef}
            className="mt-input profile-form__year"
            id={`${fieldId}-year`}
            type="text"
            inputMode="numeric"
            maxLength={4}
            placeholder="예: 1960"
            value={values.birthYear}
            onChange={change('birthYear')}
            aria-invalid={errors.birthYear ? true : undefined}
            aria-describedby={describe(errorId('birthYear'))}
          />
          <span className="profile-form__unit-label">년</span>
        </div>
        {errors.birthYear && <FieldError id={errorId('birthYear')}>{errors.birthYear}</FieldError>}
      </div>

      <ChipGroup
        legend="성별"
        mark="(필수)"
        options={genderOptions}
        value={values.gender}
        onChange={(gender) => set('gender', gender)}
        describedBy={describe(errorId('gender'))}
        firstRef={genderRef}
      >
        {errors.gender && <FieldError id={errorId('gender')}>{errors.gender}</FieldError>}
      </ChipGroup>

      <ChipGroup
        legend="임신 여부"
        mark="(필수)"
        options={pregnancyOptions}
        value={values.pregnant}
        onChange={(pregnant) => set('pregnant', pregnant)}
        describedBy={describe(errorId('pregnant'))}
        firstRef={pregnantRef}
      >
        {errors.pregnant && <FieldError id={errorId('pregnant')}>{errors.pregnant}</FieldError>}
      </ChipGroup>

      <fieldset className="profile-form__group">
        <legend className="mt-field__label profile-form__legend">
          키 · 몸무게<span className="mt-field__req">(선택)</span>
        </legend>
        <div className="profile-form__body-size">
          <div className="mt-field profile-form__body-field">
            <label className="mt-field__label profile-form__sub-label" htmlFor={`${fieldId}-height`}>
              키
            </label>
            <div className="profile-form__unit">
              <input
                ref={heightRef}
                className="mt-input"
                id={`${fieldId}-height`}
                type="text"
                inputMode="numeric"
                placeholder="예: 160"
                value={values.height}
                onChange={change('height')}
                aria-invalid={errors.height ? true : undefined}
                aria-describedby={`${fieldId}-body-help`}
              />
              <span className="profile-form__unit-label">cm</span>
            </div>
          </div>
          <div className="mt-field profile-form__body-field">
            <label className="mt-field__label profile-form__sub-label" htmlFor={`${fieldId}-weight`}>
              몸무게
            </label>
            <div className="profile-form__unit">
              <input
                ref={weightRef}
                className="mt-input"
                id={`${fieldId}-weight`}
                type="text"
                inputMode="numeric"
                placeholder="예: 55"
                value={values.weight}
                onChange={change('weight')}
                aria-invalid={errors.weight ? true : undefined}
                aria-describedby={`${fieldId}-body-help`}
              />
              <span className="profile-form__unit-label">kg</span>
            </div>
          </div>
        </div>
        {bodySizeInvalid ? (
          <FieldError id={`${fieldId}-body-help`}>{bodySizeHelp}</FieldError>
        ) : (
          <p className="mt-field__help" id={`${fieldId}-body-help`}>
            {bodySizeHelp}
          </p>
        )}
      </fieldset>

      {children}
      {failed && (
        <Notice variant="negative" role="alert">
          <b>저장하지 못했어요.</b> 입력한 내용은 그대로 있어요. 잠시 후 다시 저장해 주세요.
        </Notice>
      )}

      <div className="profile-form__footer">
        {cancelTo && (
          <Link className="mt-btn mt-btn--assistive" to={cancelTo}>
            취소
          </Link>
        )}
        <button className="mt-btn mt-btn--solid" type="submit" disabled={saving}>
          {saving && <Icon name="loader" size="s" className="icon--spin" />}
          {saving ? '저장 중…' : '저장'}
        </button>
      </div>
    </form>
  )
}

export default ProfileForm
