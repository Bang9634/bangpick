import type { MouseEventHandler } from 'react'
import type { useAccount } from './useAccount'

type AccountControlsProps = {
  session: ReturnType<typeof useAccount>
  showMemberName?: boolean
  onLogin?: MouseEventHandler<HTMLAnchorElement>
}

export function AccountControls({ session, showMemberName = true, onLogin }: AccountControlsProps) {
  const { account, error, loading, loggingOut, logout } = session

  return (
    <>
      {account?.member ? (
        <div className="account-controls">
          {showMemberName && <span>{account.member.displayName || '로그인됨'}</span>}
          <button type="button" onClick={logout} disabled={loading || loggingOut}>로그아웃</button>
        </div>
      ) : account?.loginPath && (
        <a className="sign-in" href={account.loginPath} onClick={onLogin}>Google로 로그인</a>
      )}
      {error && <p role="alert">{error}</p>}
    </>
  )
}
