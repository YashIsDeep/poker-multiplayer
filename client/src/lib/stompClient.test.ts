import { TickerStrategy } from '@stomp/stompjs'
import { describe, expect, it } from 'vitest'
import { HEARTBEAT_CONFIG } from './stompClient'

describe('STOMP heartbeat policy', () => {
  it('uses symmetric five-second heartbeats with three-interval tolerance', () => {
    expect(HEARTBEAT_CONFIG.heartbeatIncoming).toBe(5_000)
    expect(HEARTBEAT_CONFIG.heartbeatOutgoing).toBe(5_000)
    expect(HEARTBEAT_CONFIG.heartbeatToleranceMultiplier).toBe(3)
  })

  it('keeps background heartbeats alive and discards confirmed dead sockets', () => {
    expect(HEARTBEAT_CONFIG.heartbeatStrategy).toBe(TickerStrategy.Worker)
    expect(HEARTBEAT_CONFIG.discardWebsocketOnCommFailure).toBe(true)
  })
})
