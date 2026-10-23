// 알약 모양 그림(목업 IMG-07 .shp, 32×24 격자). 글자 색을 따르고, 옆에 모양 이름이 있어 화면 읽기에서 숨긴다.
const filled = { fill: 'currentColor', stroke: 'currentColor', strokeWidth: 1.5, strokeLinejoin: 'round' }

const shapes = {
  원형: <circle cx="16" cy="12" r="9.5" {...filled} />,
  타원형: <ellipse cx="16" cy="12" rx="13" ry="8.5" {...filled} />,
  장방형: <rect x="2.5" y="5" width="27" height="14" rx="5" {...filled} />,
  반원형: <path d="M4 18.5A12 12 0 0 1 28 18.5Z" {...filled} />,
  삼각형: <path d="M16 2.5 27.5 21.5H4.5Z" {...filled} />,
  사각형: <rect x="6.5" y="2.5" width="19" height="19" rx="3" {...filled} />,
  마름모형: <path d="M16 1.5 27 12 16 22.5 5 12Z" {...filled} />,
  오각형: <path d="M16 2.3 26 9.6 22.2 21.3H9.8L6 9.6Z" {...filled} />,
  육각형: <path d="M16 1.5 25.1 6.75V17.25L16 22.5 6.9 17.25V6.75Z" {...filled} />,
  팔각형: <path d="M12 2.3H20L25.7 8V16L20 21.7H12L6.3 16V8Z" {...filled} />,
  기타: (
    <>
      <rect x="5" y="3" width="22" height="18" rx="5" fill="none" stroke="currentColor" strokeWidth="1.6" strokeDasharray="3 2.6" />
      <circle cx="11" cy="12" r="1.5" fill="currentColor" />
      <circle cx="16" cy="12" r="1.5" fill="currentColor" />
      <circle cx="21" cy="12" r="1.5" fill="currentColor" />
    </>
  ),
}

function PillShapeIcon({ shape, className }) {
  return (
    <svg className={className} viewBox="0 0 32 24" aria-hidden="true" focusable="false">
      {shapes[shape] ?? shapes.기타}
    </svg>
  )
}

export default PillShapeIcon
