import { useEffect, useState } from 'react'
import { type Account, loadAccount, logOut } from './api'

export function useAccount() {
  const [account, setAccount] = useState<Account | null>(null)
  const [error, setError] = useState(() => (
    new URLSearchParams(window.location.search).get('login') === 'failed'
      ? 'Google 로그인에 실패했습니다. 다시 시도하세요.'
      : ''
  ))
  const [loading, setLoading] = useState(true)
  const [loggingOut, setLoggingOut] = useState(false)
  const [refreshCount, setRefreshCount] = useState(0)

  useEffect(() => {
    const request = new AbortController()
    const { signal } = request

    async function readAccount() {
      try {
        const loaded = await loadAccount(signal)
        if (!signal.aborted) setAccount(loaded)
      } catch (error) {
        if (!signal.aborted) {
          setAccount(null)
          setError(error instanceof Error ? error.message : '계정 정보를 불러오지 못했습니다.')
        }
      } finally {
        if (!signal.aborted) setLoading(false)
      }
    }

    void readAccount()
    return () => request.abort()
  }, [refreshCount])

  function refresh() {
    setError('')
    setLoading(true)
    setRefreshCount(count => count + 1)
  }

  async function logout() {
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

  return { account, error, loading, loggingOut, refresh, logout }
}
