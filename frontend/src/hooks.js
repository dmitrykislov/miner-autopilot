import { useEffect, useRef } from 'react'

/**
 * Subscribe to a Server-Sent Events endpoint for the lifetime of the component.
 * Parses each message as JSON and hands it to `onData`; malformed frames are
 * ignored. Optional `onOpen`/`onError` track connection state. The EventSource
 * is closed on unmount.
 *
 * Handlers are read through refs so passing fresh closures each render does not
 * re-subscribe (only a changing `url` does).
 */
export function useEventSource(url, onData, { onOpen, onError } = {}) {
  const onDataRef = useRef(onData)
  const onOpenRef = useRef(onOpen)
  const onErrorRef = useRef(onError)
  onDataRef.current = onData
  onOpenRef.current = onOpen
  onErrorRef.current = onError

  useEffect(() => {
    const es = new EventSource(url)
    es.onopen = () => onOpenRef.current?.()
    es.onerror = () => onErrorRef.current?.()
    es.onmessage = (e) => {
      let data
      try { data = JSON.parse(e.data) } catch { return } // ignore malformed frames
      onDataRef.current(data)
    }
    return () => es.close()
  }, [url])
}
