import test from 'node:test'
import assert from 'node:assert/strict'
import { answerBlocks, boldParts, sourceGroups, splitAnswer } from '../src/chatInfo.js'

test('표 제목·셀·빈 칸·정렬과 뒤 문단을 보존함', () => {
  const blocks = answerBlocks('설명\n\n| 약 | 시각 | 메모 |\n| :--- | ---: | :---: |\n| **가상약** | 08:00 | |\n\n마침')
  assert.equal(blocks.length, 3)
  assert.deepEqual(blocks[1], { kind: 'table', header: ['약', '시각', '메모'], rows: [['**가상약**', '08:00', '']], align: ['left', 'right', 'center'] })
  assert.deepEqual(boldParts(blocks[1].rows[0][0]), [{ text: '가상약', bold: true }])
  assert.deepEqual(blocks[2].lines, ['마침'])
})

test('바깥 파이프 없는 표와 셀 안의 이스케이프 파이프를 처리함', () => {
  const blocks = answerBlocks('구분 | 내용\n--- | ---\n기록 | A\\|B')
  assert.deepEqual(blocks[0].rows, [['기록', 'A|B']])
})

test('표 구분선이 없거나 열 수가 다르면 원문을 버리지 않음', () => {
  assert.equal(answerBlocks('A | B\n-- | ---\nC | D')[0].kind, 'paragraph')
  const blocks = answerBlocks('| A | B |\n| --- | --- |\n| C | D | E |')
  assert.equal(blocks[0].kind, 'table')
  assert.deepEqual(blocks[1].lines, ['| C | D | E |'])
})

test('기존 굵게·번호·하위 목록과 표를 함께 표시할 수 있음', () => {
  const blocks = answerBlocks('**설명**\n\n1. 첫째\n   - 아래\n\n| A | B |\n| --- | --- |\n| C | D |')
  assert.deepEqual(blocks.map((block) => block.kind), ['paragraph', 'list', 'table'])
  assert.equal(blocks[1].ordered, true)
  assert.equal(blocks[1].items[0].lists[0].items[0].lines[0], '아래')
})

test('HTML은 셀의 글자로 남고 서버 근거 분리를 유지함', () => {
  const sources = [{ itemSeq: '1', drugName: '가상약', source: 'PERMIT', ok: true }, { itemSeq: '1', drugName: '가상약', source: 'USER_INPUT', ok: true }]
  const split = splitAnswer('| A | B |\n| --- | --- |\n| <script>시험</script> | 기록 |\n@@SOURCES@@\n' + JSON.stringify(sources))
  assert.equal(answerBlocks(split.body)[0].rows[0][0], '<script>시험</script>')
  assert.equal(sourceGroups(split.sources).official.length, 1)
  assert.equal(sourceGroups(split.sources).mine.length, 1)
})
