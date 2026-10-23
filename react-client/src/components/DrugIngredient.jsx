import { ingredientOf } from '../drugInfo.js'
import './DrugIngredient.css'

// 후보 카드의 성분 줄(목업 IMG-04 · 05 .ingr). 값이 없으면 "성분 미확인"을 흐리게 쓴다. 긴 성분명은 자르지 않고 줄을 바꾼다.
function DrugIngredient({ value }) {
  const name = ingredientOf(value)
  if (!name) return <span className="screen-caption drug-ingredient drug-ingredient--none">성분 미확인</span>
  return <span className="screen-caption drug-ingredient">성분: {name}</span>
}

export default DrugIngredient
