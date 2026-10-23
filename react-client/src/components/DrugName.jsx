// 약 이름 속 함량 앞에서 줄을 바꿀 수 있게 한다(목업 "조코정<wbr>20밀리그램").
// 이름이 숫자로 시작하거나 숫자가 없으면 그대로 둔다.
function DrugName({ children }) {
  const index = children.search(/\d/)
  if (index <= 0) return children
  return (
    <>
      {children.slice(0, index)}
      <wbr />
      {children.slice(index)}
    </>
  )
}

export default DrugName
