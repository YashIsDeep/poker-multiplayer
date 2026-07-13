const POST_LOGIN_REDIRECT_KEY = 'poker:post-login-redirect'

export function isSafeAppPath(path: string): boolean {
  return path.startsWith('/') && !path.startsWith('//')
}

export function rememberPostLoginRedirect(path: string, storage: Storage): void {
  if (isSafeAppPath(path)) {
    storage.setItem(POST_LOGIN_REDIRECT_KEY, path)
  }
}

export function takePostLoginRedirect(storage: Storage): string | null {
  const path = storage.getItem(POST_LOGIN_REDIRECT_KEY)
  storage.removeItem(POST_LOGIN_REDIRECT_KEY)
  return path && isSafeAppPath(path) ? path : null
}
