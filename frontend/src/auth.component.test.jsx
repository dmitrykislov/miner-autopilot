import { describe, it, expect, vi, beforeEach } from 'vitest'
import { render, screen, fireEvent, waitFor } from '@testing-library/react'
import Login from './Login.jsx'
import App from './App.jsx'
import { setToken, getToken } from './auth.js'

// jsdom has no EventSource; the dashboard opens several, so stub it.
class FakeEventSource { close() {} }

beforeEach(() => {
  localStorage.clear()
  vi.restoreAllMocks()
  global.EventSource = FakeEventSource
  global.fetch = vi.fn().mockResolvedValue({ ok: true, status: 200, json: async () => ({}) })
})

describe('Login', () => {
  it('calls onSuccess on a correct password', async () => {
    global.fetch = vi.fn().mockResolvedValue({ ok: true, status: 200, json: async () => ({ token: 't' }) })
    const onSuccess = vi.fn()
    render(<Login onSuccess={onSuccess} />)
    fireEvent.change(screen.getByLabelText('Password'), { target: { value: 'pw' } })
    fireEvent.click(screen.getByText('Unlock'))
    await waitFor(() => expect(onSuccess).toHaveBeenCalled())
  })

  it('shows an error and does not proceed on a wrong password', async () => {
    global.fetch = vi.fn().mockResolvedValue({ ok: false, status: 401 })
    const onSuccess = vi.fn()
    render(<Login onSuccess={onSuccess} />)
    fireEvent.change(screen.getByLabelText('Password'), { target: { value: 'bad' } })
    fireEvent.click(screen.getByText('Unlock'))
    await waitFor(() => expect(screen.getByRole('alert')).toHaveTextContent(/incorrect/i))
    expect(onSuccess).not.toHaveBeenCalled()
  })
})

describe('App auth gate', () => {
  it('shows the login gate when unauthenticated', () => {
    render(<App />)
    expect(screen.getByLabelText('Password')).toBeInTheDocument()
  })

  it('shows the dashboard (with a logout control) when a token is present', async () => {
    setToken('tok')
    render(<App />)
    expect(await screen.findByText('Log out')).toBeInTheDocument()
    expect(screen.queryByLabelText('Password')).toBeNull()
  })

  it('logout clears the token and returns to the login gate', async () => {
    setToken('tok')
    render(<App />)
    fireEvent.click(await screen.findByText('Log out'))
    await waitFor(() => expect(getToken()).toBeNull())
    expect(screen.getByLabelText('Password')).toBeInTheDocument()
  })

  it('a 401 from the API auto-logs-out and returns to the login gate', async () => {
    // The dashboard's initial fetches get 401 (expired/invalid token) → clear + re-lock.
    global.fetch = vi.fn().mockResolvedValue({ ok: false, status: 401, json: async () => ({}) })
    setToken('stale')
    render(<App />)
    await waitFor(() => expect(getToken()).toBeNull())
    expect(await screen.findByLabelText('Password')).toBeInTheDocument()
  })
})
