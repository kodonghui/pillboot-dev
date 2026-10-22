import { Link } from 'react-router'
import { monthOf, monthWeeks } from '../dates.js'
import { paths, scheduleLink } from '../paths.js'
import { profileColor } from '../profileInfo.js'
import { scheduleKey, scheduleState, weekBars } from '../scheduleInfo.js'
import { dayName } from '../text.js'
import DrugName from './DrugName.jsx'
import Icon from './Icon.jsx'
import VisuallyHidden from './VisuallyHidden.jsx'
import '../profileColors.css'
import './CalendarMonth.css'

const weekdays = ['일', '월', '화', '수', '목', '금', '토']

// SCH-01 복용 달력의 한 달(목업 .cal). 날짜 칸은 그날부터 일정을 추가하는 링크(SCH-03),
// 일정 막대는 일정 상세(SCH-05)를 여는 링크(barLink)다. 막대 색은 사람(프로필) 순서, 끝난 일정은 점선이다.
// schedules: 받기 전이면 null(막대 없이 날짜만). selectedKey: 상세를 연 일정. 없으면 오늘을 고른 날로 보인다(목업 기본 · 비어 있음).
// newKey: 방금 말로 저장한 일정(목업 SCH-04 저장 뒤 .bar.new).
function CalendarMonth({ month, today, schedules, profiles, selectedKey, newKey, barLink, onBarClick }) {
  return (
    <div className="calendar-month__scroll">
      <div className="calendar-month" aria-busy={schedules ? undefined : true}>
        <div className="calendar-month__dow" aria-hidden="true">
          {weekdays.map((name, index) => (
            <span key={name} className={index === 0 ? 'screen-label calendar-month__sunday' : 'screen-label'}>
              {name}
            </span>
          ))}
        </div>

        {monthWeeks(month).map((week) => {
          const { bars, lanes } = weekBars(schedules ?? [], week)
          return (
            <div key={week[0]} className="calendar-month__week" style={{ '--lanes': Math.max(lanes, 1) }}>
              {week.map((date, index) => {
                const isToday = date === today
                const selected = isToday && !selectedKey
                const classes = [
                  'calendar-month__day',
                  index === 0 && 'calendar-month__day--sunday',
                  monthOf(date) !== month && 'calendar-month__day--out',
                  isToday && 'calendar-month__day--today',
                  selected && 'calendar-month__day--selected',
                ]
                return (
                  <div key={date} className={classes.filter(Boolean).join(' ')} style={{ gridColumn: index + 1 }}>
                    <Link
                      className="calendar-month__day-link"
                      to={scheduleLink(paths.scheduleNew, { date, from: 'calendar' })}
                      aria-label={`${dayName(date)}${isToday ? ', 오늘' : ''}${selected ? ', 선택됨' : ''}`}
                      aria-current={isToday ? 'date' : undefined}
                    >
                      <span className="calendar-month__day-head">
                        <span className="calendar-month__day-number">{Number(date.slice(8))}</span>
                        <span className="screen-label calendar-month__add" aria-hidden="true">
                          <Icon name="plus" size="s" />
                          일정 추가
                        </span>
                      </span>
                    </Link>
                  </div>
                )
              })}

              {bars.map((bar) => {
                const { schedule } = bar
                const key = scheduleKey(schedule.profileId, schedule.myDrugId)
                const ended = scheduleState(schedule, today) === 'ended'
                const selected = key === selectedKey
                const classes = [
                  'mt-period',
                  'calendar-month__bar',
                  `profile-color--${profileColor(profiles, schedule.profileId) ?? 1}`,
                  ended && 'mt-period--past',
                  bar.continuesLeft && 'mt-period--cont-l',
                  bar.continuesRight && 'mt-period--cont-r',
                  selected && 'calendar-month__bar--selected',
                  key === newKey && 'calendar-month__bar--new',
                ]
                return (
                  <Link
                    key={key}
                    className={classes.filter(Boolean).join(' ')}
                    to={barLink(schedule)}
                    replace
                    aria-current={selected ? 'true' : undefined}
                    data-schedule={key}
                    style={{ gridColumn: `${bar.from + 1} / ${bar.to + 2}`, gridRow: bar.lane + 2 }}
                    onClick={onBarClick}
                  >
                    <span>
                      <b>{schedule.profileName}</b> <DrugName>{schedule.drugName}</DrugName>
                      {ended && <VisuallyHidden>, 끝난 일정</VisuallyHidden>}
                    </span>
                  </Link>
                )
              })}
            </div>
          )
        })}
      </div>
    </div>
  )
}

export default CalendarMonth
