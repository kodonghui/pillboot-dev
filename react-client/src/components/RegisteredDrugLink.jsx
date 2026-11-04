import { Link } from 'react-router'
import { hasSchedule } from '../drugInfo.js'
import { paths, scheduleLink } from '../paths.js'

// '이미 등록한 약'에서 그 약의 일정으로 가는 링크(design/v3-dec-034-web-ux.md 5). 저장 · 취소 뒤에는 약 상세로 돌아간다.
// drug: 이 사람의 약(getMyDrugs 한 줄). 아직 못 찾았으면 아무것도 그리지 않는다.
function RegisteredDrugLink({ profileId, drug, className = 'mt-btn mt-btn--assistive mt-btn--sm' }) {
  if (!drug) return null
  const scheduled = hasSchedule(drug)
  const label = scheduled ? '일정 고치기' : '일정 정하기'
  return (
    <Link
      className={className}
      to={scheduleLink(scheduled ? paths.scheduleEdit : paths.scheduleNew, { profileId, myDrugId: drug.myDrugId, from: 'drug' })}
      aria-label={`${drug.name} ${label}`}
    >
      {label}
    </Link>
  )
}

export default RegisteredDrugLink
