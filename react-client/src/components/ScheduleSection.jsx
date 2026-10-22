import './ScheduleSection.css'

// 일정 추가 · 수정의 입력 묶음(목업 .panel.sec). 제목 옆 mark 는 "선택" · "하루 1–3번" 같은 짧은 표시다.
function ScheduleSection({ titleId, title, mark, children }) {
  return (
    <section className="mt-card schedule-section" aria-labelledby={titleId}>
      <div className="schedule-section__head">
        <h2 className="screen-item" id={titleId}>
          {title}
        </h2>
        {mark && <span className="screen-caption schedule-section__mark">{mark}</span>}
      </div>
      {children}
    </section>
  )
}

export default ScheduleSection
