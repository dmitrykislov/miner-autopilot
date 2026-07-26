import '@testing-library/jest-dom'
import { afterEach } from 'vitest'
import { cleanup } from '@testing-library/react'

// Unmount React trees between tests so DOM assertions don't leak across cases.
afterEach(() => cleanup())

// jsdom's Storage is unreliable under vitest here; use a simple in-memory localStorage
// so auth.js token storage behaves consistently across tests.
class MemoryStorage {
  constructor() { this.map = new Map() }
  getItem(k) { return this.map.has(k) ? this.map.get(k) : null }
  setItem(k, v) { this.map.set(k, String(v)) }
  removeItem(k) { this.map.delete(k) }
  clear() { this.map.clear() }
}
Object.defineProperty(globalThis, 'localStorage', {
  value: new MemoryStorage(), writable: true, configurable: true,
})
