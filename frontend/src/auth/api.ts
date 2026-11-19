type Member = { id: string; displayName: string | null; role: 'USER' | 'ADMIN' }
type Options = { loginPath: string | null }
type Csrf = { headerName: string; token: string }

export type Account = { loginPath: string | null; member: Member | null }

export async function loadAccount(signal: AbortSignal): Promise<Account> {
  const options = await fetch('/api/auth/options', { signal, cache: 'no-store' })
  if (!options.ok) throw new Error('로그인 설정을 불러오지 못했습니다.')

  const { loginPath } = await options.json() as Options
  if (!loginPath) return { loginPath, member: null }

  const response = await fetch('/api/me', { signal, cache: 'no-store' })
  if (response.status === 401) return { loginPath, member: null }
  if (!response.ok) throw new Error('계정 정보를 불러오지 못했습니다.')
  return { loginPath, member: await response.json() as Member }
}

export async function csrfHeaders(signal?: AbortSignal): Promise<Record<string, string>> {
  const response = await fetch('/api/auth/csrf', { signal, cache: 'no-store' })
  if (response.status === 401) throw new Error('다시 로그인하세요.')
  if (!response.ok) throw new Error('요청에 필요한 인증 정보를 가져오지 못했습니다.')
  const { headerName, token } = await response.json() as Csrf
  return { [headerName]: token }
}

export async function logOut() {
  const response = await fetch('/api/auth/logout', { method: 'POST', headers: await csrfHeaders() })
  if (!response.ok) throw new Error('로그아웃하지 못했습니다.')
}
