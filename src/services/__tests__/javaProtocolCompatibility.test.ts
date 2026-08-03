import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { readFileSync } from 'node:fs'
import { resolve } from 'node:path'
import { PROTOCOL_VERSION, getMethodChannel } from '@/types/protocol'
import { BackendClient as PythonClient, type DispatcherLike } from '../backendClient'

class MockRendererWebSocket {
  static readonly CONNECTING = 0
  static readonly OPEN = 1
  static readonly CLOSING = 2
  static readonly CLOSED = 3
  static instances: MockRendererWebSocket[] = []

  readonly url: string
  readyState = MockRendererWebSocket.OPEN
  sent: string[] = []
  onopen: ((event: Event) => void) | null = null
  onmessage: ((event: MessageEvent) => void) | null = null
  onclose: ((event: CloseEvent) => void) | null = null
  onerror: ((event: Event) => void) | null = null

  constructor(url: string) {
    this.url = url
    MockRendererWebSocket.instances.push(this)
    queueMicrotask(() => this.onopen?.(new Event('open')))
  }

  send(payload: string): void {
    this.sent.push(payload)
  }

  close(): void {
    this.readyState = MockRendererWebSocket.CLOSED
    this.onclose?.(new Event('close') as CloseEvent)
  }

  receive(message: unknown): void {
    this.onmessage?.(
      new MessageEvent('message', { data: JSON.stringify(message) }),
    )
  }
}

describe('Renderer compatibility with the Java Phase 2 protocol shell', () => {
  const originalWebSocket = globalThis.WebSocket

  beforeEach(() => {
    MockRendererWebSocket.instances = []
    globalThis.WebSocket = MockRendererWebSocket as unknown as typeof WebSocket
  })

  afterEach(() => {
    globalThis.WebSocket = originalWebSocket
  })

  it('is anchored to the canonical shared protocol schema', () => {
    const schemaPath = resolve(
      process.cwd(),
      'java-backend/opengis-common/src/main/resources/opengis/protocol/opengis-protocol-3.0.schema.json',
    )
    const schema = JSON.parse(readFileSync(schemaPath, 'utf8'))

    expect(schema.$defs.protocolVersion.const).toBe(PROTOCOL_VERSION)
    expect(schema.$defs.jsonRpcVersion.const).toBe('2.0')
    expect(schema.$defs.geometryType.enum).toEqual([
      'Point',
      'MultiPoint',
      'LineString',
      'MultiLineString',
      'Polygon',
      'MultiPolygon',
      'GeometryCollection',
      'Raster',
    ])
    expect(getMethodChannel('rpc.system.ping')).toBe('rpc')
    expect(getMethodChannel('chat.message_part')).toBe('chat')
    expect(getMethodChannel('event.phase2.ready')).toBe('event')
  })

  it('connects with the token, completes ping, and renders a Java notification', async () => {
    const rendered: Array<{ method: string; params: unknown }> = []
    const dispatcher: DispatcherLike = {
      handleRequest: vi.fn(async (request) => ({
        jsonrpc: '2.0' as const,
        id: request.id,
        result: { accepted: true },
      })),
      handleNotification: vi.fn(async (notification) => {
        rendered.push({
          method: notification.method,
          params: notification.params,
        })
      }),
    }
    const client = new PythonClient()
    client.setDispatcher(dispatcher)
    client.connect(8765, 'java token')
    await Promise.resolve()

    const socket = MockRendererWebSocket.instances[0]
    expect(socket.url).toBe('ws://127.0.0.1:8765/ws?token=java%20token')
    expect(client.isConnected).toBe(true)

    const pingPromise = client.send<{ status: string; runtime: string }>(
      'rpc.system.ping',
    )
    await Promise.resolve()
    const pingRequest = JSON.parse(socket.sent[0])
    expect(pingRequest).toMatchObject({
      jsonrpc: '2.0',
      method: 'rpc.system.ping',
      params: {},
    })
    socket.receive({
      jsonrpc: '2.0',
      id: pingRequest.id,
      result: { status: 'ok', runtime: 'java' },
    })
    await expect(pingPromise).resolves.toEqual({ status: 'ok', runtime: 'java' })

    socket.receive({
      jsonrpc: '2.0',
      method: 'rpc.ui.chat.show_text',
      params: { text: 'Phase 2 ready' },
    })
    await Promise.resolve()
    await Promise.resolve()

    expect(dispatcher.handleNotification).toHaveBeenCalledTimes(1)
    expect(rendered).toEqual([
      {
        method: 'rpc.ui.chat.show_text',
        params: { text: 'Phase 2 ready' },
      },
    ])
    client.disconnect()
  })
})
