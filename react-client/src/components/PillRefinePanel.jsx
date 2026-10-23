import { useEffect, useId, useRef, useState } from 'react'
import { errorOf } from '../api/client.js'
import { identifyPillClues } from '../api/photos.js'
import { colorNames, lineNames, pillColors, pillShapes, shapeName, stateLabels } from '../pillInfo.js'
import FieldError from './FieldError.jsx'
import Icon from './Icon.jsx'
import PillShapeIcon from './PillShapeIcon.jsx'
import SelectMenu from './SelectMenu.jsx'
import VisuallyHidden from './VisuallyHidden.jsx'
import './PillRefinePanel.css'

const faces = [
  { key: 'Front', name: '사진 1' },
  { key: 'Back', name: '사진 2' },
]
const printStates = ['READABLE', 'NONE', 'UNREADABLE'].map((value) => ({ value, label: stateLabels[value] }))
const lineOptions = [
  { value: 'NONE', label: stateLabels.NONE },
  ...['MINUS', 'PLUS'].map((value) => ({
    value,
    label: (
      <>
        <span aria-hidden="true">{lineNames[value].symbol}</span>
        <VisuallyHidden>{lineNames[value].name}</VisuallyHidden>
      </>
    ),
  })),
  { value: 'UNREADABLE', label: stateLabels.UNREADABLE },
]
const shapeOptions = pillShapes.map((shape) => ({
  value: shape,
  label: (
    <span className="pill-refine__shape">
      <PillShapeIcon shape={shape} className="pill-refine__shape-icon" />
      <span>{shape}</span>
    </span>
  ),
}))
// 앞 결과가 이미 바뀌었거나 · 끝났거나 · 시간이 지났으면 앞 결과 없이 다시 찾는다.
const staleCodes = ['IDENTIFICATION_REPLACED', 'IDENTIFICATION_NOT_SELECTABLE', 'IDENTIFICATION_EXPIRED']

// 서버 단서를 고치는 값으로. 고를 수 있는 목록에 없는 모양 · 색은 비워 다시 고르게 한다.
function formOf(clues) {
  const line = (side) => {
    const state = clues?.[`line${side}State`]
    return state === 'READABLE' ? (clues[`line${side}`] ?? 'UNREADABLE') : (state ?? null)
  }
  const shape = shapeName(clues?.shape)
  return {
    printFrontState: clues?.printFrontState ?? null,
    printFront: clues?.printFront ?? '',
    printBackState: clues?.printBackState ?? null,
    printBack: clues?.printBack ?? '',
    shape: pillShapes.includes(shape) ? shape : null,
    colors: colorNames(clues?.colorClass1, clues?.colorClass2).filter((color) => pillColors.includes(color)).slice(0, 2),
    lineFront: line('Front'),
    lineBack: line('Back'),
  }
}

// 고친 값을 서버 단서(PillIdentification)로. 읽힘이 아닌 면은 글자를 보내지 않는다.
function cluesOf(form) {
  const print = (side) => (form[`print${side}State`] === 'READABLE' ? form[`print${side}`].trim() : null)
  const lineState = (value) => (value === 'MINUS' || value === 'PLUS' ? 'READABLE' : value)
  const lineValue = (value) => (value === 'MINUS' || value === 'PLUS' ? value : null)
  return {
    printFront: print('Front'),
    printBack: print('Back'),
    printFrontState: form.printFrontState,
    printBackState: form.printBackState,
    shape: form.shape,
    colorClass1: form.colors[0] ?? null,
    colorClass2: form.colors[1] ?? null,
    lineFront: lineValue(form.lineFront),
    lineBack: lineValue(form.lineBack),
    lineFrontState: lineState(form.lineFront),
    lineBackState: lineState(form.lineBack),
  }
}

// 하나만 고르는 작은 선택 버튼 묶음(목업 .seg .opt-s)
function Chips({ label, options, value, onChange }) {
  return (
    <div className="pill-refine__chips" role="group" aria-label={label}>
      {options.map((option) => (
        <button
          key={option.value}
          className="mt-chip mt-chip--sm"
          type="button"
          aria-pressed={option.value === value}
          onClick={() => onChange(option.value)}
        >
          {option.label}
        </button>
      ))}
    </div>
  )
}

function Requirement({ met, children }) {
  return (
    <li className={met ? undefined : 'pill-refine__unmet'}>
      <Icon name={met ? 'check' : 'alertCircle'} size="s" />
      <span>
        {children}
        <VisuallyHidden>{met ? ' · 채웠어요' : ' · 아직 없어요'}</VisuallyHidden>
      </span>
    </li>
  )
}

// 단서 보완하기(IMG-07). 화면 오른쪽 패널(브라우저 <dialog>)에서 각인 · 모양 · 색상 · 분할선을 고쳐 다시 찾는다.
// clues 가 없으면(서버 오류로 단서가 없을 때) 빈 값에서 시작한다. previousId 는 바꿀 앞 결과다.
// caution: 지금 결과에서 후보를 고를 수 있을 때의 안내 · replacing: 지금 결과가 있을 때의 안내
// 다시 찾으면 onDone(새 결과)을 부르고, 닫히면 onClose 를 부른다.
function PillRefinePanel({ clues, profileId, previousId, caution, replacing, onDone, onClose }) {
  const dialogRef = useRef(null)
  const inputRefs = { Front: useRef(null), Back: useRef(null) }
  const id = useId()
  const [form, setForm] = useState(() => formOf(clues))
  const [checked, setChecked] = useState(false)
  const [submitting, setSubmitting] = useState(false)
  const [error, setError] = useState(null)

  useEffect(() => {
    const dialog = dialogRef.current
    if (!dialog.open) dialog.showModal()
  }, [])

  const change = (patch) => {
    setForm((previous) => ({ ...previous, ...patch }))
    setError(null)
  }

  const toggleColor = (color) =>
    change({ colors: form.colors.includes(color) ? form.colors.filter((item) => item !== color) : [...form.colors, color] })

  const readFaces = faces.filter((face) => form[`print${face.key}State`] === 'READABLE' && form[`print${face.key}`].trim())
  const emptyFaces = faces.filter((face) => form[`print${face.key}State`] === 'READABLE' && !form[`print${face.key}`].trim())
  const met = { print: readFaces.length > 0, shape: Boolean(form.shape), color: form.colors.length > 0 }
  const allMet = met.print && met.shape && met.color

  const submit = async (event) => {
    event.preventDefault()
    if (emptyFaces.length > 0) {
      setChecked(true)
      inputRefs[emptyFaces[0].key].current.focus()
      return
    }
    setSubmitting(true)
    setError(null)
    const next = cluesOf(form)
    try {
      let response
      try {
        response = await identifyPillClues(profileId, next, previousId)
      } catch (failure) {
        const { status, code } = errorOf(failure)
        if (!previousId || !(status === 404 || staleCodes.includes(code))) throw failure
        response = await identifyPillClues(profileId, next)
      }
      dialogRef.current.close()
      onDone(response)
    } catch (failure) {
      setError(errorOf(failure).status === 400 ? 'invalid' : 'failed')
      setSubmitting(false)
    }
  }

  return (
    <dialog
      ref={dialogRef}
      className="pill-refine"
      aria-labelledby={`${id}-title`}
      onCancel={(event) => submitting && event.preventDefault()}
      onClose={onClose}
    >
      <form className="pill-refine__panel" noValidate onSubmit={submit}>
        <div className="pill-refine__head">
          <div className="pill-refine__title">
            <h2 className="screen-section" id={`${id}-title`}>
              단서 보완하기
            </h2>
            <p className="screen-caption pill-refine__muted">
              {clues ? '사진에서 읽은 값이에요. 맞지 않는 값을 고쳐 주세요.' : '알약을 보고 각인 · 모양 · 색상을 직접 넣어 주세요.'}
            </p>
          </div>
          <button
            className="pill-refine__close"
            type="button"
            aria-label="닫기"
            disabled={submitting}
            onClick={() => dialogRef.current.close()}
          >
            <Icon name="close" />
          </button>
        </div>

        <div className="pill-refine__body">
          {caution && (
            <div className="mt-notice mt-notice--caution">
              <Icon name="alertTriangle" className="mt-notice__icon" />
              <p className="mt-notice__text">
                값을 고치면 <b>고른 후보가 풀려요.</b> 다시 찾은 결과에서 다시 골라 주세요.
              </p>
            </div>
          )}

          <section className="pill-refine__section" aria-labelledby={`${id}-print`}>
            <h3 className="screen-item" id={`${id}-print`}>
              각인 <span className="screen-caption pill-refine__aside">두 면</span>
            </h3>
            {faces.map((face) => {
              const state = form[`print${face.key}State`]
              const readable = state === 'READABLE'
              const showEmpty = checked && emptyFaces.includes(face)
              const inputId = `${id}-print-${face.key}`
              return (
                <div className="pill-refine__face" key={face.key}>
                  <p className="screen-label pill-refine__neutral">
                    {face.name}
                    {face.key === 'Back' && <span className="screen-caption pill-refine__aside"> 반대쪽 면</span>}
                  </p>
                  <Chips
                    label={`${face.name} 각인 상태`}
                    options={printStates}
                    value={state}
                    onChange={(value) => change({ [`print${face.key}State`]: value })}
                  />
                  <div className="mt-field">
                    <label className="mt-field__label" htmlFor={inputId}>
                      읽힌 글자 · 기호
                    </label>
                    <input
                      ref={inputRefs[face.key]}
                      className="mt-input"
                      id={inputId}
                      type="text"
                      maxLength={50}
                      autoComplete="off"
                      disabled={!readable}
                      placeholder={readable ? undefined : '읽힘을 고르면 입력할 수 있어요'}
                      value={readable ? form[`print${face.key}`] : ''}
                      onChange={(event) => change({ [`print${face.key}`]: event.target.value })}
                      aria-invalid={showEmpty ? true : undefined}
                      aria-describedby={showEmpty ? `${inputId}-error` : undefined}
                    />
                    {showEmpty && <FieldError id={`${inputId}-error`}>읽힌 글자 · 기호를 입력해 주세요.</FieldError>}
                  </div>
                  {face.key === 'Back' && (
                    <p className="screen-caption pill-refine__muted">없음: 각인이 없는 면 · 미판독: 각인은 있지만 읽을 수 없는 면</p>
                  )}
                </div>
              )
            })}
          </section>

          <section className="pill-refine__section" aria-labelledby={`${id}-shape`}>
            <h3 className="screen-item" id={`${id}-shape`}>
              모양
            </h3>
            <div className="mt-field">
              <label className="mt-field__label" htmlFor={`${id}-shape-select`}>
                전체 모양
              </label>
              <SelectMenu
                id={`${id}-shape-select`}
                value={form.shape}
                options={shapeOptions}
                placeholder="모양을 골라 주세요"
                listLabel="전체 모양 선택지"
                onChange={(shape) => change({ shape })}
              />
            </div>
          </section>

          <section className="pill-refine__section" aria-labelledby={`${id}-color`}>
            <div className="pill-refine__section-head">
              <h3 className="screen-item" id={`${id}-color`}>
                색상
              </h3>
              <span className="screen-caption pill-refine__muted" id={`${id}-color-limit`}>
                두 가지까지
              </span>
            </div>
            <div className="pill-refine__chips" role="group" aria-label="색상" aria-describedby={`${id}-color-limit`}>
              {pillColors.map((color) => {
                const pressed = form.colors.includes(color)
                return (
                  <button
                    key={color}
                    className="mt-chip mt-chip--sm"
                    type="button"
                    aria-pressed={pressed}
                    disabled={!pressed && form.colors.length >= 2}
                    onClick={() => toggleColor(color)}
                  >
                    {color}
                  </button>
                )
              })}
            </div>
          </section>

          <section className="pill-refine__section" aria-labelledby={`${id}-line`}>
            <div className="pill-refine__section-head">
              <h3 className="screen-item" id={`${id}-line`}>
                분할선
              </h3>
              <span className="screen-caption pill-refine__muted">선택</span>
            </div>
            {faces.map((face) => (
              <div className="pill-refine__face" key={face.key}>
                <p className="screen-label pill-refine__neutral">{face.name}</p>
                <Chips
                  label={`${face.name} 분할선`}
                  options={lineOptions}
                  value={form[`line${face.key}`]}
                  onChange={(value) => change({ [`line${face.key}`]: value })}
                />
              </div>
            ))}
          </section>

          <section className={allMet ? 'pill-refine__needs pill-refine__needs--met' : 'pill-refine__needs'} aria-labelledby={`${id}-needs`}>
            <p className="screen-label pill-refine__neutral" id={`${id}-needs`}>
              다시 찾으려면 모두 필요해요
            </p>
            <ul>
              <Requirement met={met.print}>
                각인이 읽힌 면 1개 이상
                {readFaces.map((face) => ` · ${face.name} '${form[`print${face.key}`].trim()}'`).join('')}
              </Requirement>
              <Requirement met={met.shape}>모양{form.shape && ` · ${form.shape}`}</Requirement>
              <Requirement met={met.color}>색상 1개 이상{form.colors.length > 0 && ` · ${form.colors.join(', ')}`}</Requirement>
            </ul>
          </section>
        </div>

        <div className="pill-refine__foot">
          {replacing && <p className="screen-caption pill-refine__muted">다시 찾으면 지금 결과는 새 결과로 바뀌어요.</p>}
          {error && (
            <div role="alert">
              <FieldError>
                {error === 'invalid' ? '입력한 단서를 확인해 주세요.' : '다시 찾지 못했어요. 잠시 후 다시 시도해 주세요.'}
              </FieldError>
            </div>
          )}
          <div className="pill-refine__actions">
            <button className="mt-btn mt-btn--assistive" type="button" disabled={submitting} onClick={() => dialogRef.current.close()}>
              취소
            </button>
            <button className="mt-btn mt-btn--solid" type="submit" disabled={!allMet || submitting}>
              <Icon name={submitting ? 'loader' : 'search'} className={submitting ? 'icon--spin' : undefined} />
              다시 찾기
            </button>
          </div>
        </div>
      </form>
    </dialog>
  )
}

export default PillRefinePanel
