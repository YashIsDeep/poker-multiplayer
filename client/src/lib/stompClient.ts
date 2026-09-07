// STOMP connection wrapper (T7). One socket per tab; reconnect scheduling is
// owned by the store's resilience machine (T10), so automatic reconnect is
// disabled here. Every CONNECT refetches the CSRF token per the T5 flow.

import { Client, TickerStrategy } from '@stomp/stompjs'
import type { IMessage } from '@stomp/stompjs'

export type StompHandlers = {
  onConnected: () => void
  /** Fired on socket close or STOMP error, whether or not a session was established. */
  onClosed: () => void
  onLobby: (body: unknown) => void
  onAck: (body: unknown) => void
  onRoom: (body: unknown) => void
  onRoomEvent: (body: unknown) => void
  onChat: (body: unknown) => void
  onRanked?: (body: unknown) => void
}

const SUBSCRIPTIONS: Array<{ destination: string; handler: keyof StompHandlers }> = [
  { destination: '/user/queue/ranked', handler: 'onRanked' },
  { destination: '/topic/lobby', handler: 'onLobby' },
  { destination: '/user/queue/acks', handler: 'onAck' },
  { destination: '/user/queue/room', handler: 'onRoom' },
  { destination: '/user/queue/room-events', handler: 'onRoomEvent' },
  { destination: '/user/queue/chat', handler: 'onChat' },
]

export const HEARTBEAT_INTERVAL_MS = 5_000
export const HEARTBEAT_TOLERANCE_MULTIPLIER = 3
export const HEARTBEAT_CONFIG = Object.freeze({
  heartbeatIncoming: HEARTBEAT_INTERVAL_MS,
  heartbeatOutgoing: HEARTBEAT_INTERVAL_MS,
  heartbeatToleranceMultiplier: HEARTBEAT_TOLERANCE_MULTIPLIER,
  heartbeatStrategy: TickerStrategy.Worker,
  discardWebsocketOnCommFailure: true,
})

async function fetchCsrf(): Promise<{ headerName: string; token: string }> {
  const response = await fetch('/api/csrf', { credentials: 'same-origin' })
  if (!response.ok) throw new Error(`csrf fetch failed: ${response.status}`)
  return response.json()
}

function wsUrl(): string {
  const proto = window.location.protocol === 'https:' ? 'wss:' : 'ws:'
  return `${proto}//${window.location.host}/ws`
}

export class PokerStompClient {
  private client: Client | null = null
  private handlers: StompHandlers
  private closedNotified = false

  constructor(handlers: StompHandlers) {
    this.handlers = handlers
  }

  get connected(): boolean {
    return this.client?.connected ?? false
  }

  /**
   * Open a fresh connection (tears down any previous one first).
   * Failures surface through onClosed; the resilience machine schedules retries.
   */
  async connect(): Promise<void> {
    await this.disconnect()
    this.closedNotified = false

    let csrf: { headerName: string; token: string }
    try {
      csrf = await fetchCsrf()
    } catch {
      this.notifyClosed()
      return
    }

    const client = new Client({
      brokerURL: wsUrl(),
      connectHeaders: { [csrf.headerName]: csrf.token },
      reconnectDelay: 0, // resilience machine owns retry scheduling
      ...HEARTBEAT_CONFIG,
      onConnect: () => {
        SUBSCRIPTIONS.forEach(({ destination, handler }) =>
          client.subscribe(destination, (message: IMessage) => {
            let body: unknown
            try {
              body = JSON.parse(message.body)
            } catch {
              return // malformed frame: ignore rather than crash
            }
            ;(this.handlers[handler] as ((body: unknown) => void) | undefined)?.(body)
          }),
        )
        this.handlers.onConnected()
      },
      onWebSocketClose: () => this.notifyClosed(),
      onStompError: () => this.notifyClosed(),
    })
    this.client = client
    client.activate()
  }

  private notifyClosed() {
    if (this.closedNotified) return
    this.closedNotified = true
    this.handlers.onClosed()
  }

  /** Tear down without telling the resilience machine (voluntary close). */
  async disconnect(): Promise<void> {
    const client = this.client
    this.client = null
    this.closedNotified = true
    if (client) {
      try {
        await client.deactivate()
      } catch {
        // already down
      }
    }
  }

  publish(destination: string, body: unknown): boolean {
    if (!this.client?.connected) return false
    this.client.publish({ destination, body: JSON.stringify(body) })
    return true
  }
}
