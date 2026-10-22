// 올릴 수 있는 사진(서버 규칙과 같다): JPG · PNG · WEBP, 10MB 이하. 서버는 확장자와 실제 형식이 맞는지도 본다.
export const photoAccept = 'image/jpeg,image/png,image/webp'

const photoTypes = { 'image/jpeg': /\.jpe?g$/i, 'image/png': /\.png$/i, 'image/webp': /\.webp$/i }
const maxPhotoBytes = 10 * 1024 * 1024

// 올릴 수 없는 까닭(type · size)별 안내
export const photoMessages = {
  type: 'JPG, PNG, WEBP 사진만 올릴 수 있어요.',
  size: '10MB가 넘는 사진은 올릴 수 없어요.',
}

// 고른 사진을 올릴 수 없으면 까닭(type · size), 올릴 수 있으면 null
export function photoProblem(file) {
  if (!photoTypes[file.type]?.test(file.name)) return 'type'
  if (file.size > maxPhotoBytes) return 'size'
  return null
}

// 서버가 사진을 거절한 오류 코드의 까닭. 사진 문제가 아니면 null
export function photoProblemOf(code) {
  if (code === 'UNSUPPORTED_FILE_TYPE') return 'type'
  if (code === 'FILE_TOO_LARGE') return 'size'
  return null
}

// 2516582 → '2.4MB', 51200 → '50KB'
export function photoSize(bytes) {
  if (bytes < 1024 * 1024) return `${Math.max(1, Math.round(bytes / 1024))}KB`
  return `${(bytes / 1024 / 1024).toFixed(1)}MB`
}
