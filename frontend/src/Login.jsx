import React, { useState } from 'react'
import { login } from './auth.js'
import { Sun } from './icons.jsx'

/** Password gate shown until a valid token is stored. Calls onSuccess() once authed. */
export default function Login({ onSuccess }) {
  const [password, setPassword] = useState('')
  const [error, setError] = useState('')
  const [busy, setBusy] = useState(false)

  const submit = async (e) => {
    e.preventDefault()
    setBusy(true)
    setError('')
    try {
      const ok = await login(password)
      if (ok) onSuccess()
      else setError('Incorrect password')
    } catch {
      setError('Could not reach the server')
    } finally {
      setBusy(false)
    }
  }

  return (
    <div className="login">
      <form className="login-card" onSubmit={submit}>
        <span className="brand-mark"><Sun size={24} /></span>
        <h1>Solar Monitor</h1>
        <p className="muted">Enter the password to continue</p>
        <input
          type="password"
          value={password}
          autoFocus
          aria-label="Password"
          placeholder="Password"
          onChange={(e) => setPassword(e.target.value)}
        />
        {error && <div className="login-error" role="alert">{error}</div>}
        <button type="submit" disabled={busy || !password}>
          {busy ? 'Checking…' : 'Unlock'}
        </button>
      </form>
    </div>
  )
}
