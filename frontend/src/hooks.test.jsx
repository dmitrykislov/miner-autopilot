import { describe, it, expect, beforeEach, vi } from 'vitest'
import { renderHook } from '@testing-library/react'

// hooks.js mints an SSE ticket via auth.js; mock it so the tests control the URL/outcome.
const ticketMock = vi.fn()
vi.mock('./auth.js', () => ({ withSseTicket: (...a) => ticketMock(...a) }))

import { useEventSource } from './hooks.js'

// Minimal fake EventSource so we can drive message/open/error and assert cleanup.
let created
beforeEach(() => {
  created = []
  global.EventSource = class {
    constructor(url) { this.url = url; this.closed = false; created.push(this) }
    close() { this.closed = true }
  }
  ticketMock.mockReset()
  ticketMock.mockImplementation(async (path) => path + '?token=TICKET')
})

// Drain the microtask queue so the async connect() (ticket mint → new EventSource) completes.
const flush = async () => { for (let i = 0; i < 5; i++) await Promise.resolve() }

describe('useEventSource', () => {
  it('mints a ticket, opens the token-carrying URL, and forwards JSON messages', async () => {
    const onData = vi.fn()
    renderHook(() => useEventSource('/api/x', onData))
    await flush()
    expect(ticketMock).toHaveBeenCalledWith('/api/x')
    expect(created).toHaveLength(1)
    expect(created[0].url).toBe('/api/x?token=TICKET')
    created[0].onmessage({ data: '{"a":1,"b":"two"}' })
    expect(onData).toHaveBeenCalledWith({ a: 1, b: 'two' })
  })

  it('ignores malformed JSON frames without throwing', async () => {
    const onData = vi.fn()
    renderHook(() => useEventSource('/api/x', onData))
    await flush()
    expect(() => created[0].onmessage({ data: 'not json{' })).not.toThrow()
    expect(onData).not.toHaveBeenCalled()
  })

  it('wires onOpen and closes the stream on unmount', async () => {
    const onOpen = vi.fn()
    const { unmount } = renderHook(() => useEventSource('/api/x', () => {}, { onOpen }))
    await flush()
    const es = created[0]
    es.onopen()
    expect(onOpen).toHaveBeenCalledOnce()
    unmount()
    expect(es.closed).toBe(true)
  })

  it('reconnects with a fresh ticket after an error, after a backoff', async () => {
    vi.useFakeTimers()
    try {
      const onError = vi.fn()
      renderHook(() => useEventSource('/api/x', () => {}, { onError }))
      await flush()
      expect(created).toHaveLength(1)

      created[0].onerror()                 // stream dropped
      expect(onError).toHaveBeenCalledOnce()
      expect(created[0].closed).toBe(true) // we close it ourselves (no native retry on a stale URL)
      expect(created).toHaveLength(1)      // nothing yet — waits out the backoff

      await vi.advanceTimersByTimeAsync(1000)
      await flush()
      expect(created).toHaveLength(2)                  // reconnected
      expect(created[1].url).toBe('/api/x?token=TICKET') // with a freshly minted ticket
    } finally {
      vi.useRealTimers()
    }
  })

  it('backs off exponentially across repeated failures and resets once a connection opens', async () => {
    vi.useFakeTimers()
    try {
      renderHook(() => useEventSource('/api/x', () => {}))
      await flush()
      expect(created).toHaveLength(1)

      created[0].onerror()                       // 1st drop → retry after 1000ms
      await vi.advanceTimersByTimeAsync(999)
      await flush()
      expect(created).toHaveLength(1)             // not yet
      await vi.advanceTimersByTimeAsync(1)
      await flush()
      expect(created).toHaveLength(2)             // reconnected at 1000ms

      created[1].onerror()                        // 2nd drop → backoff doubled to 2000ms
      await vi.advanceTimersByTimeAsync(1000)
      await flush()
      expect(created).toHaveLength(2)             // still waiting (only 1000ms elapsed)
      await vi.advanceTimersByTimeAsync(1000)
      await flush()
      expect(created).toHaveLength(3)             // reconnected at 2000ms

      created[2].onopen()                         // a healthy connection resets the backoff…
      created[2].onerror()                        // …so the next drop retries after 1000ms again
      await vi.advanceTimersByTimeAsync(1000)
      await flush()
      expect(created).toHaveLength(4)
    } finally {
      vi.useRealTimers()
    }
  })

  it('does not reconnect after unmount', async () => {
    vi.useFakeTimers()
    try {
      const { unmount } = renderHook(() => useEventSource('/api/x', () => {}))
      await flush()
      created[0].onerror() // schedules a reconnect
      unmount()            // must cancel the pending timer
      await vi.advanceTimersByTimeAsync(5000)
      await flush()
      expect(created).toHaveLength(1) // no new connection
    } finally {
      vi.useRealTimers()
    }
  })

  it('backs off and retries when the ticket mint fails (e.g. logged out)', async () => {
    vi.useFakeTimers()
    try {
      ticketMock.mockRejectedValueOnce(new Error('mint failed'))
      renderHook(() => useEventSource('/api/x', () => {}))
      await flush()
      expect(created).toHaveLength(0) // mint failed → no EventSource opened

      await vi.advanceTimersByTimeAsync(1000)
      await flush()
      expect(created).toHaveLength(1) // retried; second mint succeeded
    } finally {
      vi.useRealTimers()
    }
  })
})
