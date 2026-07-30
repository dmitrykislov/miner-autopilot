import { useEffect, useRef } from 'react'
import { withSseTicket } from './auth.js'

/**
 * Subscribe to a Server-Sent Events endpoint for the lifetime of the component.
 * `path` is the token-less stream path (e.g. '/api/power/stream'); each connection
 * mints a fresh short-lived SSE ticket and appends it as ?token=.
 *
 * We manage reconnection ourselves instead of relying on EventSource's built-in
 * retry: on error we close and, after a growing backoff, re-mint a ticket and
 * reconnect. Native retry would reuse the original URL — whose ticket has since
 * expired — and loop on 401 forever. Backoff resets once a connection opens.
 *
 * Parses each message as JSON and hands it to `onData`; malformed frames are
 * ignored. Optional `onOpen`/`onError` track connection state. Everything is torn
 * down on unmount (and only a changing `path` re-subscribes).
 *
 * Handlers are read through refs so passing fresh closures each render does not
 * re-subscribe.
 */
export function useEventSource(path, onData, { onOpen, onError } = {}) {
  const onDataRef = useRef(onData)
  const onOpenRef = useRef(onOpen)
  const onErrorRef = useRef(onError)
  onDataRef.current = onData
  onOpenRef.current = onOpen
  onErrorRef.current = onError

  useEffect(() => {
    let cancelled = false
    let es = null
    let timer = null
    let backoffMs = 1000

    const scheduleReconnect = () => {
      if (cancelled || timer) return
      timer = setTimeout(() => { timer = null; connect() }, backoffMs)
      backoffMs = Math.min(backoffMs * 2, 30000) // cap at 30s
    }

    const connect = async () => {
      if (cancelled) return
      let url
      try {
        url = await withSseTicket(path) // fresh ticket per (re)connect
      } catch {
        scheduleReconnect() // logged out / mint failed — back off and try again
        return
      }
      if (cancelled) return
      es = new EventSource(url)
      es.onopen = () => { backoffMs = 1000; onOpenRef.current?.() }
      es.onerror = () => {
        onErrorRef.current?.()
        if (es) { es.close(); es = null }
        scheduleReconnect()
      }
      es.onmessage = (e) => {
        let data
        try { data = JSON.parse(e.data) } catch { return } // ignore malformed frames
        onDataRef.current(data)
      }
    }

    connect()
    return () => {
      cancelled = true
      if (timer) clearTimeout(timer)
      if (es) es.close()
    }
  }, [path])
}
