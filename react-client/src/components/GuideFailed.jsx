import Icon from './Icon.jsx'
import Notice from './Notice.jsx'

// 약 공식 정보(공공데이터)를 불러오지 못했을 때의 안내와 "다시 불러오기"(목업 MED-04 설명 불러오기 실패)
function GuideFailed({ onRetry }) {
  return (
    <Notice
      variant="caution"
      icon="alertTriangle"
      role="alert"
      action={
        <button className="mt-btn mt-btn--assistive mt-btn--md" type="button" onClick={onRetry}>
          <Icon name="refresh" size="s" />
          다시 불러오기
        </button>
      }
    >
      <b>불러오지 못했어요</b>
      <br />
      효능, 사용법, 주의사항은 공공데이터에서 가져와요. 잠시 후 다시 불러와 주세요.
    </Notice>
  )
}

export default GuideFailed
