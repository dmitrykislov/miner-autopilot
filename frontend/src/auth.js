// Client-side access control: a bearer token obtained from /api/auth/login is kept in
// localStorage for a month. Every API call carries it (header for fetch, ?token= for SSE
// since EventSource can't set headers). Logout just clears it — the app then re-locks.

const TOKEN_KEY = 'mc_auth_token'
const EXP_KEY = 'mc_auth_expires'
const DEFAULT_TTL_DAYS = 30

/** The stored token if present and not past its local expiry, else null (clears if expired). */
export function getToken() {
  try {
    const token = localStorage.getItem(TOKEN_KEY)
    if (!token) return null
    const exp = Number(localStorage.getItem(EXP_KEY) || 0)
    if (exp && Date.now() > exp) { clearToken(); return null }
    return token
  } catch {
    return null
  }
}

export function isAuthed() {
  return getToken() !== null
}

export function setToken(token, ttlDays = DEFAULT_TTL_DAYS) {
  try {
    localStorage.setItem(TOKEN_KEY, token)
    localStorage.setItem(EXP_KEY, String(Date.now() + ttlDays * 24 * 60 * 60 * 1000))
  } catch {
    /* ignore storage errors (private mode etc.) */
  }
}

export function clearToken() {
  try {
    localStorage.removeItem(TOKEN_KEY)
    localStorage.removeItem(EXP_KEY)
  } catch {
    /* ignore */
  }
}

/** Authorization header for fetch(), or {} when logged out. */
export function authHeaders() {
  const t = getToken()
  return t ? { Authorization: `Bearer ${t}` } : {}
}

/**
 * Build a stream URL carrying a short-lived, single-use-ish SSE ticket in the query string.
 * EventSource can't send an Authorization header, so streams authenticate via ?token=. We don't
 * put the long-lived bearer token there (URLs leak into logs/history); instead we ask the server
 * for a ~60s ticket that only works on the stream endpoints. Called fresh on every (re)connect.
 * Rejects if we're logged out or the mint fails, so the caller can back off and retry.
 */
export async function withSseTicket(path) {
  const res = await fetch('/api/auth/sse-ticket', { method: 'POST', headers: authHeaders() })
  if (!res.ok) throw new Error(`sse-ticket failed: HTTP ${res.status}`)
  const data = await res.json()
  const ticket = data && data.ticket
  if (!ticket) throw new Error('sse-ticket: empty response')
  return path + (path.includes('?') ? '&' : '?') + 'token=' + encodeURIComponent(ticket)
}

/**
 * Exchange a password for a token and store it. Returns true on success, false on a
 * wrong password (401). Throws only on network/unexpected errors.
 */
export async function login(password) {
  const res = await fetch('/api/auth/login', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ password }),
  })
  if (res.status === 401) return false
  if (!res.ok) throw new Error(`login failed: HTTP ${res.status}`)
  const data = await res.json()
  if (!data || !data.token) return false
  setToken(data.token)
  return true
}
