// Multi-tab arbitration via BroadcastChannel (T10). Newest connected tab wins;
// older tabs go read-only and show the takeover modal. "Use here instead"
// broadcasts a takeover request; the active tab yields, the requester reconnects.
// Browsers without BroadcastChannel degrade to single-tab mode (no arbitration).

type ArbiterMessage =
  | { type: 'tabConnected'; tabId: string; at: number }
  | { type: 'requestTakeover'; tabId: string }
  | { type: 'tabClosed'; tabId: string; wasActive: boolean }

export type TabArbiterCallbacks = {
  /** Another tab took over: close the socket, freeze the UI. */
  onDemoted: () => void
  /** The active tab went away and this read-only tab should reclaim the session. */
  onReclaim: () => void
}

export type TabArbiter = {
  readonly tabId: string
  readonly supported: boolean
  /** Call after a successful STOMP connect to claim the active role. */
  announceConnected: () => void
  /** Call from the takeover modal's "Use here instead". */
  requestTakeover: () => void
  dispose: () => void
}

/** Total ordering prevents same-millisecond connection races from demoting both tabs. */
export function isNewerTab(
  candidateAt: number,
  candidateId: string,
  currentAt: number,
  currentId: string,
): boolean {
  return candidateAt > currentAt || (candidateAt === currentAt && candidateId > currentId)
}

export function createTabArbiter(callbacks: TabArbiterCallbacks): TabArbiter {
  const tabId =
    typeof crypto !== 'undefined' && 'randomUUID' in crypto
      ? crypto.randomUUID()
      : `tab-${Math.random().toString(36).slice(2)}`

  if (typeof BroadcastChannel === 'undefined') {
    return {
      tabId,
      supported: false,
      announceConnected: () => {},
      requestTakeover: () => {},
      dispose: () => {},
    }
  }

  const channel = new BroadcastChannel('poker-room')
  let active = false
  let connectedAt = 0

  const post = (msg: ArbiterMessage) => channel.postMessage(msg)

  channel.onmessage = (event: MessageEvent<ArbiterMessage>) => {
    const msg = event.data
    if (!msg || msg.tabId === tabId) return
    switch (msg.type) {
      case 'tabConnected':
        // A newer tab connected. If we were active (or racing), yield to it.
        if (active && isNewerTab(msg.at, msg.tabId, connectedAt, tabId)) {
          active = false
          callbacks.onDemoted()
        }
        break
      case 'requestTakeover':
        if (active) {
          active = false
          callbacks.onDemoted()
        }
        break
      case 'tabClosed':
        if (!active && msg.wasActive) {
          callbacks.onReclaim()
        }
        break
    }
  }

  const onPageHide = () => {
    post({ type: 'tabClosed', tabId, wasActive: active })
  }
  window.addEventListener('pagehide', onPageHide)

  return {
    tabId,
    supported: true,
    announceConnected() {
      active = true
      connectedAt = Date.now()
      post({ type: 'tabConnected', tabId, at: connectedAt })
    },
    requestTakeover() {
      post({ type: 'requestTakeover', tabId })
    },
    dispose() {
      window.removeEventListener('pagehide', onPageHide)
      channel.close()
    },
  }
}
