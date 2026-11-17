import { useEffect, useState } from 'react'
import { type Account, loadAccount, logOut } from './api'

export function AccountControls() {
  const [account, setAccount] = useState<Account | null>(null)
  const [error, setError] = useState(() => (
    new URLSearchParams(window.location.search).get('login') === 'failed'
      ? 'Google 로그인에 실패했습니다. 다시 시도하세요.'
      : ''
  ))
  const [loggingOut, setLoggingOut] = useState(false)

  useEffect(() => {
    const request = new AbortController()
    loadAccount(request.signal).then(setAccount).catch((error: Error) => {
      if (!request.signal.aborted) setError(error.message)
    })
    return () => request.abort()
  }, [])

  async function handleLogout() {
    setLoggingOut(true)
    setError('')
    try {
      await logOut()
      setAccount(current => current && { ...current, member: null })
    } catch (error) {
      setError(error instanceof Error ? error.message : '로그아웃하지 못했습니다.')
    } finally {
      setLoggingOut(false)
    }
  }

  return (
    <>
      {account?.member ? (
        <div className="account-controls">
          <span>{account.member.displayName || '로그인됨'}</span>
          <button type="button" onClick={handleLogout} disabled={loggingOut}>로그아웃</button>
        </div>
      ) : account?.loginPath && (
        <a className="sign-in" href={account.loginPath}>Google로 로그인</a>
      )}
      {error && <p role="alert">{error}</p>}
    </>
  )
}
