import { describe, it, expect, beforeEach, vi } from 'vitest'
import { renderHook } from '@testing-library/react'
import { useEventSource } from './hooks.js'

// Minimal fake EventSource so we can drive message/open/error and assert cleanup.
let created
beforeEach(() => {
  created = []
  global.EventSource = class {
    constructor(url) { this.url = url; this.closed = false; created.push(this) }
    close() { this.closed = true }
  }
})

describe('useEventSource', () => {
  it('parses JSON messages and forwards them to onData', () => {
    const onData = vi.fn()
    renderHook(() => useEventSource('/api/x', onData))
    created[0].onmessage({ data: '{"a":1,"b":"two"}' })
    expect(onData).toHaveBeenCalledWith({ a: 1, b: 'two' })
  })

  it('ignores malformed JSON frames without throwing', () => {
    const onData = vi.fn()
    renderHook(() => useEventSource('/api/x', onData))
    expect(() => created[0].onmessage({ data: 'not json{' })).not.toThrow()
    expect(onData).not.toHaveBeenCalled()
  })

  it('wires onOpen/onError and closes the stream on unmount', () => {
    const onOpen = vi.fn(), onError = vi.fn()
    const { unmount } = renderHook(() => useEventSource('/api/x', () => {}, { onOpen, onError }))
    const es = created[0]
    es.onopen()
    es.onerror()
    expect(onOpen).toHaveBeenCalledOnce()
    expect(onError).toHaveBeenCalledOnce()
    unmount()
    expect(es.closed).toBe(true)
  })

  it('opens exactly one connection to the given url', () => {
    renderHook(() => useEventSource('/api/miner/stream', () => {}))
    expect(created).toHaveLength(1)
    expect(created[0].url).toBe('/api/miner/stream')
  })
})
