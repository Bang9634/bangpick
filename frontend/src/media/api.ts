import { csrfHeaders } from '../auth/api'

export type Transfer = { url: string; method: 'GET' | 'PUT'; expiresAt: string; sizeBytes: number }
export type Job = { jobId: string; status: 'UPLOADING' | 'QUEUED' | 'PROCESSING' | 'RESULT_READY' | 'FAILED' }
type Started = { jobId: string; transfer: Transfer }
type MediaRequest = { method?: 'GET' | 'POST'; payload?: object; signal?: AbortSignal }

async function requestMedia<T>(token: string | null, path: string,
  { method = 'GET', payload, signal }: MediaRequest = {}): Promise<T> {
  const headers: Record<string, string> = token !== null
    ? { Authorization: `Bearer ${token.trim()}` }
    : method === 'POST' ? await csrfHeaders(signal) : {}
  if (payload) headers['Content-Type'] = 'application/json'
  const response = await fetch(`/api/media/jobs${path}`, {
    method,
    body: payload ? JSON.stringify(payload) : undefined,
    headers, signal, cache: 'no-store', redirect: 'error',
  })
  if (!response.ok) {
    const messages: Record<number, string> = {
      400: '입력 형식 또는 파일 크기를 확인하세요.',
      401: token === null ? '다시 로그인하세요.' : '개발 접근 토큰을 확인하세요.',
      404: '작업을 찾을 수 없거나 백엔드의 파일 전송 기능이 꺼져 있습니다.',
      409: '작업 상태나 업로드된 파일을 확인하세요. 업로드가 만료됐다면 다시 시작하세요.',
      502: '스토리지 요청에 실패했습니다. 백엔드의 OCI 설정과 권한을 확인하세요.',
    }
    throw new Error(messages[response.status] ?? '백엔드 요청에 실패했습니다.')
  }
  return response.json() as Promise<T>
}

function videoContentType(file: File): string {
  if (file.type) return file.type
  const name = file.name.toLowerCase()
  if (name.endsWith('.mp4')) return 'video/mp4'
  if (name.endsWith('.mov')) return 'video/quicktime'
  if (name.endsWith('.webm')) return 'video/webm'
  throw new Error('MP4, MOV 또는 WebM 영상을 선택하세요.')
}

// 저장 확인만 실패했을 때 영상을 다시 보내지 않고 확인 요청만 재시도할 수 있다.
export async function uploadSourceVideo(token: string | null, file: File, signal?: AbortSignal): Promise<string> {
  const { jobId, transfer } = await requestMedia<Started>(token, '', {
    method: 'POST', payload: { sizeBytes: file.size, contentType: videoContentType(file) }, signal,
  })
  await objectTransfer(transfer, 'PUT', file, signal)
  return jobId
}

export function confirmVideoUpload(token: string | null, jobId: string, signal?: AbortSignal): Promise<Job> {
  return requestMedia(token, `/${encodeURIComponent(jobId)}/input-complete`, { method: 'POST', signal })
}

export function getMediaJob(token: string | null, jobId: string, signal?: AbortSignal): Promise<Job> {
  return requestMedia(token, `/${encodeURIComponent(jobId)}`, { signal })
}

export function requestResultPreview(token: string | null, jobId: string, signal?: AbortSignal): Promise<Transfer> {
  return requestMedia(token, `/${encodeURIComponent(jobId)}/preview`, { method: 'POST', signal })
}

export async function objectTransfer(grant: Transfer, method: Transfer['method'], body?: File, signal?: AbortSignal) {
  const url = new URL(grant.url)
  if (url.protocol !== 'https:' || url.username || url.password || grant.method !== method) {
    throw new Error('파일 전송 URL이 올바르지 않습니다.')
  }
  try {
    // 임시 URL이 스토리지 인증을 대신하므로 백엔드 토큰이나 쿠키를 보내지 않는다.
    const response = await fetch(url, {
      method, body, signal, credentials: 'omit', referrerPolicy: 'no-referrer',
      cache: 'no-store', redirect: 'error',
    })
    if (!response.ok) throw new Error()
    return response
  } catch {
    throw new Error('스토리지 전송에 실패했습니다. 새 URL을 발급하거나 브라우저 네트워크 설정을 확인하세요.')
  }
}
