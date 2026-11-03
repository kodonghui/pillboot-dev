import { useId } from 'react'
import { releases } from '../changelog.js'
import { yearMonthDay } from '../text.js'
import './ChangelogPage.css'

// CMN-04 업데이트 기록(design/cmn-04-changelog.md). 비로그인 · 로그인 모두 연다(router.js PublicLayout).
// 가장 최근 판만 이름표를 초록으로 둔다.
function ChangelogPage() {
  const baseId = useId()

  return (
    <div className="changelog">
      <div className="changelog__head">
        <h1 className="screen-title">업데이트 기록</h1>
        <p className="changelog__neutral">필받음에 새로 생기거나 바뀐 것을 최근 것부터 적었어요.</p>
      </div>

      <ol className="changelog__list">
        {releases.map((release, index) => {
          const titleId = `${baseId}-${index}`
          return (
            <li key={`${release.date}-${release.label}`}>
              <article className="mt-card mt-card--wide changelog__release" aria-labelledby={titleId}>
                <div className="changelog__meta">
                  <time className="screen-caption changelog__date" dateTime={release.date}>
                    {yearMonthDay(release.date)}
                  </time>
                  <span className={index === 0 ? 'mt-badge mt-badge--primary' : 'mt-badge'}>{release.label}</span>
                </div>
                <div className="changelog__title">
                  <h2 className="screen-section" id={titleId}>
                    {release.title}
                  </h2>
                  <p className="changelog__neutral">{release.summary}</p>
                </div>
                <ul className="changelog__changes">
                  {release.changes.map((change) => (
                    <li className="changelog__change" key={change.title}>
                      <h3 className="screen-item">{change.title}</h3>
                      <p className="changelog__neutral">{change.text}</p>
                    </li>
                  ))}
                </ul>
              </article>
            </li>
          )
        })}
      </ol>
    </div>
  )
}

export default ChangelogPage
