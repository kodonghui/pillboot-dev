import { useEffect, useId, useRef, useState } from 'react'
import { flushSync } from 'react-dom'
import { Link } from 'react-router'
import { errorOf } from '../api/client.js'
import { deleteSchedule, stopSchedule } from '../api/schedules.js'
import { doseText, scheduleState } from '../scheduleInfo.js'
import { eulAfter, monthDay, periodText } from '../text.js'
import ConfirmDialog from './ConfirmDialog.jsx'
import DrugName from './DrugName.jsx'
import DrugThumb from './DrugThumb.jsx'
import Icon from './Icon.jsx'
import IconButton from './IconButton.jsx'
import NoValue from './NoValue.jsx'
import Notice from './Notice.jsx'
import ProfileTag from './ProfileTag.jsx'
import './ScheduleDetailPanel.css'

const stopHints = {
  active: '종료일을 오늘로 바꿔요. 지난 기간은 복용 달력에 남아요.',
  ended: '이미 끝난 일정이라 그만 먹기는 할 수 없어요.',
  notStarted: '아직 시작하지 않은 일정이라 그만 먹을 수 없어요. 먹지 않으려면 일정을 삭제해 주세요.',
}

// SCH-05 일정 상세. 복용 달력 오른쪽(좁으면 아래) 패널이다.
// status: loading · ready · notFound(약이나 일정이 지워짐) · failed. ready 이면 drug(내 약 상세 응답)로 그린다.
// 그만 먹기 · 일정 삭제가 끝나면 onDone, 다른 곳에서 바뀌어 할 수 없으면(404 · 409) onStale 을 부른다.
// focusOnOpen: 막대를 눌러 열었으면 제목으로 포커스를 옮긴다(주소로 바로 열었을 때는 옮기지 않는다).
function ScheduleDetailPanel({
  status,
  drug,
  profileId,
  profileName,
  color,
  today,
  editTo,
  focusOnOpen,
  onClose,
  onRetry,
  onRefresh,
  onDone,
  onStale,
}) {
  const titleId = useId()
  const stopHintId = useId()
  const deleteHintId = useId()
  const headingRef = useRef(null)
  const [dialog, setDialog] = useState(null)
  const [working, setWorking] = useState(false)
  const [failed, setFailed] = useState(false)

  useEffect(() => {
    if (focusOnOpen) headingRef.current.focus()
  }, [focusOnOpen])

  const closeDialog = () => {
    setDialog(null)
    setFailed(false)
  }

  // 성공하면 패널이 닫히므로 버튼은 잠근 채로 둔다. 다른 곳에서 이미 바뀌었으면 확인창을 닫고 새로 불러온다.
  // 확인창(모달)이 열려 있는 동안에는 바깥으로 포커스를 옮길 수 없어서, 먼저 닫고 나서 알린다.
  const run = async (request) => {
    setWorking(true)
    setFailed(false)
    try {
      await request(profileId, drug.myDrugId)
      flushSync(() => setDialog(null))
      onDone()
    } catch (error) {
      setWorking(false)
      const { status: code } = errorOf(error)
      if (code === 404 || code === 409) {
        flushSync(closeDialog)
        headingRef.current.focus()
        onStale()
      } else {
        setFailed(true)
      }
    }
  }

  let body = null
  if (status === 'loading') {
    body = (
      <p className="screen-caption schedule-panel__muted" role="status">
        일정 상세를 불러오고 있어요.
      </p>
    )
  } else if (status === 'failed') {
    body = (
      <Notice
        variant="negative"
        role="alert"
        action={
          <button className="mt-btn mt-btn--assistive mt-btn--md" type="button" onClick={onRetry}>
            다시 시도
          </button>
        }
      >
        일정 상세를 불러오지 못했어요. 잠시 후 다시 시도해 주세요.
      </Notice>
    )
  } else if (status === 'notFound') {
    body = (
      <>
        <div className="schedule-panel__missing" role="alert">
          <span className="schedule-panel__missing-icon">
            <Icon name="alertTriangle" size="l" />
          </span>
          <h3 className="screen-item">이 일정을 찾을 수 없어요</h3>
          <p className="screen-body schedule-panel__neutral">
            다른 기기에서 일정이나 약을 지웠을 수 있어요. 복용 달력을 새로 불러오면 지금 상태로 보여 드려요.
          </p>
        </div>
        <button className="mt-btn mt-btn--solid mt-btn--block" type="button" onClick={onRefresh}>
          <Icon name="refresh" />
          복용 달력 새로 불러오기
        </button>
      </>
    )
  } else if (status === 'ready') {
    const name = drug.name
    const state = scheduleState(drug, today)
    const ended = state === 'ended'
    const period = periodText(drug.startDate, drug.endDate)
    const times = drug.doseTimes.map((time) => (
      <span className="mt-badge schedule-panel__number" key={time}>
        {time}
      </span>
    ))
    const edit = (
      <Link className="mt-btn mt-btn--assistive mt-btn--block" to={editTo}>
        <Icon name="pencil" />
        수정
      </Link>
    )

    body = (
      <>
        <div className="schedule-panel__drug">
          <DrugThumb imageUrl={drug.drug?.imageUrl} custom={!drug.drug} alt={`${name} 공식 사진`} />
          <div className="schedule-panel__name">
            <h3 className="screen-item">
              <DrugName>{name}</DrugName>
            </h3>
            {ended && <span className="mt-badge">끝난 일정</span>}
            {!drug.drug && <span className="mt-badge">직접 쓴 이름</span>}
          </div>
        </div>

        <dl className="schedule-panel__kv">
          <div>
            <dt className="screen-label">프로필</dt>
            <dd>
              <ProfileTag color={color}>{profileName}</ProfileTag>
            </dd>
          </div>
          <div>
            <dt className="screen-label">기간</dt>
            <dd>{period}</dd>
          </div>
          <div>
            <dt className="screen-label">복용 시간</dt>
            <dd>{times}</dd>
          </div>
          <div>
            <dt className="screen-label">1회 개수</dt>
            <dd>{drug.dosePerTime != null ? doseText(drug.dosePerTime) : <NoValue />}</dd>
          </div>
          <div>
            <dt className="screen-label">복용 때</dt>
            <dd>{drug.timing || <NoValue />}</dd>
          </div>
        </dl>

        {ended ? (
          <div className="schedule-panel__act">
            {edit}
            <p className="screen-caption schedule-panel__muted">다시 먹으려면 수정에서 기간을 새로 정해 주세요.</p>
          </div>
        ) : (
          edit
        )}

        <div className="schedule-panel__actions">
          <div className="schedule-panel__act">
            <button
              className="mt-btn mt-btn--assistive mt-btn--block"
              type="button"
              disabled={state !== 'active'}
              aria-haspopup="dialog"
              aria-describedby={stopHintId}
              onClick={() => setDialog('stop')}
            >
              <Icon name="stopCircle" />
              그만 먹기
            </button>
            <p className="screen-caption schedule-panel__muted" id={stopHintId}>
              {stopHints[state]}
            </p>
          </div>
          <div className="schedule-panel__act">
            <button
              className="mt-btn mt-btn--danger-outlined mt-btn--block"
              type="button"
              aria-haspopup="dialog"
              aria-describedby={deleteHintId}
              onClick={() => setDialog('delete')}
            >
              <Icon name="trash" />
              일정 삭제
            </button>
            <p className="screen-caption schedule-panel__muted" id={deleteHintId}>
              {ended
                ? '지우면 지난 기간도 복용 달력에서 사라져요. 약은 약 목록에 남아요.'
                : '기간과 복용 시간을 모두 지워요. 약은 약 목록에 남아요.'}
            </p>
          </div>
        </div>

        {dialog === 'stop' && (
          <ConfirmDialog
            title={`${name}${eulAfter(name)} 그만 먹을까요?`}
            description={`종료일이 오늘(${monthDay(today)})로 바뀌어요. 지난 기간은 복용 달력에 남아요.`}
            confirmLabel="그만 먹기"
            confirmDisabled={working}
            error={failed ? '그만 먹기로 바꾸지 못했어요. 잠시 후 다시 시도해 주세요.' : null}
            onConfirm={() => run(stopSchedule)}
            onClose={closeDialog}
          >
            <dl className="schedule-panel__kv">
              <div>
                <dt className="screen-label">지금 기간</dt>
                <dd>{period}</dd>
              </div>
              <div>
                <dt className="screen-label">바뀐 뒤 기간</dt>
                <dd className="schedule-panel__strong">{periodText(drug.startDate, today)}</dd>
              </div>
            </dl>
          </ConfirmDialog>
        )}

        {dialog === 'delete' && (
          <ConfirmDialog
            title="이 일정을 삭제할까요?"
            lead={
              <div className="schedule-panel__summary">
                <ProfileTag color={color}>{profileName}</ProfileTag>
                <div className="schedule-panel__summary-text">
                  <p className="schedule-panel__strong">
                    <DrugName>{name}</DrugName>
                  </p>
                  <p className="screen-caption schedule-panel__muted">{period}</p>
                </div>
                <span className="schedule-panel__times">{times}</span>
              </div>
            }
            description="기간과 복용 시간이 모두 지워져 지난 기간도 복용 달력에서 사라지고 알림이 멈춰요. 약은 약 목록에 '일정 없음'으로 남아요."
            danger
            confirmLabel="삭제"
            confirmDisabled={working}
            error={failed ? '삭제하지 못했어요. 잠시 후 다시 시도해 주세요.' : null}
            onConfirm={() => run(deleteSchedule)}
            onClose={closeDialog}
          />
        )}
      </>
    )
  }

  return (
    <aside className="mt-card schedule-panel" aria-labelledby={titleId}>
      <div className="schedule-panel__head">
        <h2 className="screen-section schedule-panel__title" id={titleId} ref={headingRef} tabIndex={-1}>
          일정 상세
        </h2>
        <IconButton icon="close" label="일정 상세 닫기" onClick={onClose} />
      </div>
      {body}
    </aside>
  )
}

export default ScheduleDetailPanel
