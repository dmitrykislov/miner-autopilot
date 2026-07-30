import { describe, it, expect, vi, beforeEach } from 'vitest'
import { getToken, setToken, clearToken, isAuthed, authHeaders, withSseTicket, login } from './auth.js'

beforeEach(() => {
  localStorage.clear()
  vi.restoreAllMocks()
})

describe('token storage', () => {
  it('stores, reads, and reports a token', () => {
    setToken('abc')
    expect(getToken()).toBe('abc')
    expect(isAuthed()).toBe(true)
    expect(authHeaders()).toEqual({ Authorization: 'Bearer abc' })
  })

  it('has no token / header when absent', () => {
    expect(getToken()).toBeNull()
    expect(isAuthed()).toBe(false)
    expect(authHeaders()).toEqual({})
  })

  it('clears the token', () => {
    setToken('abc')
    clearToken()
    expect(getToken()).toBeNull()
    expect(isAuthed()).toBe(false)
  })

  it('treats a token past its TTL as expired and clears it', () => {
    setToken('abc', -1) // TTL in the past
    expect(getToken()).toBeNull()
    expect(localStorage.getItem('mc_auth_token')).toBeNull()
  })
})

describe('withSseTicket (short-lived SSE credential in the query string)', () => {
  it('mints a ticket with the bearer header and appends it as ?token= / &token=', async () => {
    setToken('t1')
    global.fetch = vi.fn().mockResolvedValue({ ok: true, json: async () => ({ ticket: 'sse.123.sig' }) })

    expect(await withSseTicket('/api/x')).toBe('/api/x?token=sse.123.sig')
    expect(await withSseTicket('/api/x?a=1')).toBe('/api/x?a=1&token=sse.123.sig')
    expect(global.fetch).toHaveBeenCalledWith('/api/auth/sse-ticket',
      expect.objectContaining({ method: 'POST', headers: { Authorization: 'Bearer t1' } }))
  })

  it('url-encodes the ticket', async () => {
    setToken('t1')
    global.fetch = vi.fn().mockResolvedValue({ ok: true, json: async () => ({ ticket: 'a b/c' }) })
    expect(await withSseTicket('/api/x')).toBe('/api/x?token=a%20b%2Fc')
  })

  it('rejects when the mint fails (so the caller can back off)', async () => {
    setToken('t1')
    global.fetch = vi.fn().mockResolvedValue({ ok: false, status: 401 })
    await expect(withSseTicket('/api/x')).rejects.toThrow()
  })

  it('rejects on an empty ticket response', async () => {
    setToken('t1')
    global.fetch = vi.fn().mockResolvedValue({ ok: true, json: async () => ({}) })
    await expect(withSseTicket('/api/x')).rejects.toThrow()
  })
})

describe('login', () => {
  it('stores the token and returns true on success', async () => {
    global.fetch = vi.fn().mockResolvedValue({ ok: true, status: 200, json: async () => ({ token: 'srv' }) })
    const ok = await login('pw')
    expect(ok).toBe(true)
    expect(getToken()).toBe('srv')
    expect(global.fetch).toHaveBeenCalledWith('/api/auth/login', expect.objectContaining({ method: 'POST' }))
  })

  it('returns false and stores nothing on a wrong password (401)', async () => {
    global.fetch = vi.fn().mockResolvedValue({ ok: false, status: 401 })
    const ok = await login('bad')
    expect(ok).toBe(false)
    expect(getToken()).toBeNull()
  })
})
