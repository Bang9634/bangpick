import type { MouseEvent } from 'react'

export const DEV_HOME = '/_dev'
export const DEV_AUTH = '/_dev/auth'

const authReturnKey = 'bangpick.dev.auth-return'

export function rememberAuthReturn(event: MouseEvent<HTMLAnchorElement>) {
  if (event.defaultPrevented || event.button !== 0 || event.metaKey || event.ctrlKey
    || event.shiftKey || event.altKey || event.currentTarget.origin !== window.location.origin) return

  try {
    sessionStorage.setItem(authReturnKey, '1')
  } catch {
    // Google login can proceed when the browser blocks access to sessionStorage.
  }
}

export function currentDevPath() {
  const { pathname, search } = window.location
  if (pathname !== '/') return pathname.replace(/\/$/, '')

  try {
    if (sessionStorage.getItem(authReturnKey) !== '1') return pathname
    sessionStorage.removeItem(authReturnKey)
  } catch {
    return pathname
  }

  // The backend sends both OAuth outcomes to the home page.
  window.history.replaceState(window.history.state, '', DEV_AUTH + search)
  return DEV_AUTH
}
