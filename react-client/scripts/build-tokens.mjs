// design/pillbadeum-tokens.json 으로 src/tokens.css 를 만든다. 실행: npm run tokens
// 이름과 값은 JSON 그대로 옮기고, JSON 에 없는 값은 채우지 않는다. 꼭 있어야 하는 값이 없으면 오류로 멈춘다.
// 생성 시각 같은 값을 넣지 않아 같은 JSON 이면 몇 번을 실행해도 결과가 같다.
import { readFile, writeFile } from 'node:fs/promises'

const source = new URL('../design/pillbadeum-tokens.json', import.meta.url)
const target = new URL('../src/tokens.css', import.meta.url)

const json = JSON.parse(await readFile(source, 'utf8'))

function listOf(value, where) {
  if (!Array.isArray(value)) throw new Error(`${where} 가 JSON 에 없습니다.`)
  return value
}

function cssName(value, where) {
  if (typeof value !== 'string' || !/^[A-Za-z_][\w-]*$/.test(value)) {
    throw new Error(`${where} 의 이름을 CSS 에 쓸 수 없습니다: ${JSON.stringify(value)}`)
  }
  return value
}

function cssValue(value, where) {
  if (typeof value !== 'number' && (typeof value !== 'string' || value.trim() === '')) {
    throw new Error(`${where} 의 값이 JSON 에 없습니다.`)
  }
  return value
}

const variable = (name, value, where) =>
  `  --${cssName(name, where)}: ${cssValue(value, `${where} ${name}`)};`

// color: light 값은 :root 에, dark 값은 [data-theme="dark"] 에 같은 이름으로 둔다.
// dark 값이 없는 토큰은 [data-theme="dark"] 에 넣지 않아 :root 의 light 값을 그대로 쓴다.
const colors = listOf(json.color?.tokens, 'color.tokens')
const lightColors = colors.map((token) => variable(token.name, token.value?.light, 'color.tokens(light)'))
const darkColors = colors
  .filter((token) => token.value?.dark !== undefined)
  .map((token) => variable(token.name, token.value.dark, 'color.tokens(dark)'))

// type.families.sans → --font-sans
const families = json.type?.families
if (!families) throw new Error('type.families 가 JSON 에 없습니다.')
const fonts = Object.entries(families).map(([key, value]) => variable(`font-${key}`, value, 'type.families'))

// spacing·radius·shadow·breakpoint 는 이름 그대로 :root 에 둔다.
const scales = ['spacing', 'radius', 'shadow', 'breakpoint'].flatMap((group) =>
  listOf(json[group]?.tokens, `${group}.tokens`).map((token) => variable(token.name, token.value, `${group}.tokens`)),
)

// type.groups 의 스타일마다 같은 이름의 클래스를 만든다.
// fontSize 는 꼭 있어야 하고, lineHeight·letterSpacing·fontWeight 는 JSON 에 있을 때만 옮긴다.
const typeClasses = listOf(json.type?.groups, 'type.groups').flatMap((group) => {
  if (!Object.hasOwn(families, group.family)) {
    throw new Error(`type.groups ${group.name} 의 family "${group.family}" 가 type.families 에 없습니다.`)
  }
  return listOf(group.styles, `type.groups ${group.name} 의 styles`).map((style) => {
    const where = `type 스타일 ${style.name}`
    const optional = (property, key) =>
      style[key] === undefined ? [] : [`  ${property}: ${cssValue(style[key], `${where} ${key}`)};`]
    return [
      `.${cssName(style.name, where)} {`,
      `  font-family: var(--font-${group.family});`,
      `  font-size: ${cssValue(style.fontSize, `${where} fontSize`)};`,
      ...optional('line-height', 'lineHeight'),
      ...optional('letter-spacing', 'letterSpacing'),
      ...optional('font-weight', 'fontWeight'),
      '}',
    ].join('\n')
  })
})

const css = [
  '/* 자동 생성 파일: 직접 고치지 않는다. design/pillbadeum-tokens.json 을 고친 뒤 npm run tokens 로 다시 만든다. */',
  [':root {', ...lightColors, ...fonts, ...scales, '}'].join('\n'),
  ['[data-theme="dark"] {', ...darkColors, '}'].join('\n'),
  ...typeClasses,
].join('\n\n')

await writeFile(target, `${css}\n`)
console.log('src/tokens.css 를 만들었습니다.')
