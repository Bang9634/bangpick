import { useState } from 'react'
import { AccountControls } from '../auth/AccountControls'
import { getAdminAccessStatus } from '../auth/api'
import { useAccount } from '../auth/useAccount'
import { DEV_AUTH, DEV_HOME, rememberAuthReturn } from './navigation'
import './dev.css'

export function DevHome() {
  return (
    <main className="dev-page">
      <h1>개발 테스트</h1>
      <ul className="dev-links">
        <li><a href={DEV_AUTH}>인증 테스트</a></li>
      </ul>
    </main>
  )
}

const adminStatusText: Partial<Record<number, string>> = {
  401: '로그인 필요',
  403: '관리자 권한 필요',
  204: '관리자 접근 허용',
}

const adminAccessTimeoutMs = 5_000

function AdminAccessCheck({ disabled }: { disabled: boolean }) {
  const [status, setStatus] = useState<number | null>(null)
  const [checking, setChecking] = useState(false)
  const [error, setError] = useState('')

  async function checkAccess() {
    setStatus(null)
    setError('')
    setChecking(true)
    const request = new AbortController()
    const timeout = window.setTimeout(() => request.abort(), adminAccessTimeoutMs)
    try {
      setStatus(await getAdminAccessStatus(request.signal))
    } catch {
      setError(request.signal.aborted
        ? '관리자 접근 요청 시간이 초과됐습니다. 다시 시도하세요.'
        : '관리자 접근 요청을 보내지 못했습니다.')
    } finally {
      window.clearTimeout(timeout)
      setChecking(false)
    }
  }

  return (
    <section aria-labelledby="admin-check">
      <h2 id="admin-check">관리자 접근</h2>
      <code>GET /api/admin/access</code>
      <div className="dev-actions">
        <button type="button" onClick={checkAccess} disabled={disabled || checking}>접근 확인</button>
      </div>
      <p role="status">
        {checking && '확인 중…'}
        {status !== null && `HTTP ${status} · ${adminStatusText[status] ?? '예상 외 응답'}`}
        {error}
      </p>
    </section>
  )
}

export function DevAuth() {
  const session = useAccount()
  const { account, loading, loggingOut, refresh } = session
  const member = account?.member
  const busy = loading || loggingOut

  return (
    <main className="dev-page">
      <nav aria-label="개발 페이지">
        <a href={DEV_HOME}>개발 테스트 목록</a>
      </nav>
      <h1>인증 테스트</h1>
      <section aria-labelledby="member-check">
        <h2 id="member-check">회원 세션</h2>
        <p role="status">
          {loading && '계정 확인 중…'}
          {loggingOut && '로그아웃 중…'}
          {!busy && account && (member ? '로그인됨' : '비로그인')}
          {!busy && account?.loginPath === null && ' · Google 로그인 비활성화'}
          {!busy && !account && '계정 상태를 확인하지 못했습니다.'}
        </p>
        {member && (
          <dl className="dev-member">
            <dt>회원 ID</dt><dd><code>{member.id}</code></dd>
            <dt>표시 이름</dt><dd>{member.displayName || '없음'}</dd>
            <dt>역할</dt><dd><code>{member.role}</code></dd>
          </dl>
        )}
        <div className="dev-actions">
          <AccountControls session={session} onLogin={rememberAuthReturn} showMemberName={false} />
          <button type="button" onClick={refresh} disabled={busy}>회원 정보 다시 조회</button>
        </div>
      </section>
      <AdminAccessCheck key={member ? `${member.id}:${member.role}` : 'anonymous'} disabled={busy} />
    </main>
  )
}
