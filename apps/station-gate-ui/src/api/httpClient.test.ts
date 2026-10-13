import { afterEach, describe, expect, it, vi } from 'vitest'
import { createHttpStationGateClient } from './httpClient'
import type { StationGateResponse } from './types'

const BASE_URL = 'http://mes.test'

const DENY: StationGateResponse = {
  allowed: false,
  workOrderId: 'WO-DEMO-002',
  processPathId: 'pp_cold_ambient',
  thisOp: 'ASM',
  expectedNext: 'COAT',
  reasonCode: 'WRONG_STATION',
  message: 'Expected next: COAT',
}

type FetchMock = ReturnType<typeof stubFetch>

function stubFetch(status: number, body: unknown, statusText = '') {
  const fetchMock = vi.fn<typeof fetch>(async () =>
    new Response(body === undefined ? null : JSON.stringify(body), { status, statusText }),
  )
  vi.stubGlobal('fetch', fetchMock)
  return fetchMock
}

function sent(fetchMock: FetchMock, index: number) {
  const [url, init] = fetchMock.mock.calls[index]
  return { url: String(url), init: init!, body: JSON.parse(String(init!.body)) }
}

afterEach(() => {
  vi.unstubAllGlobals()
})

describe('createHttpStationGateClient', () => {
  it('posts the gate check as JSON and returns a plant deny as data, not an error', async () => {
    const fetchMock = stubFetch(200, DENY)
    const client = createHttpStationGateClient(BASE_URL)

    const result = await client.checkGate({ serialNumber: 'PANEL-DEMO-002', equipmentId: 'AEL-01-ASM' })

    expect(result).toStrictEqual(DENY)
    const { url, init, body } = sent(fetchMock, 0)
    expect(url).toBe('http://mes.test/api/station-gate-checks')
    expect(init.method).toBe('POST')
    expect(init.headers).toStrictEqual({ 'Content-Type': 'application/json' })
    expect(body).toStrictEqual({
      serialNumber: 'PANEL-DEMO-002',
      equipmentId: 'AEL-01-ASM',
    })
  })

  it('sends COMPLETE to station-completes and PASS/FAIL to station-quality-results', async () => {
    const fetchMock = stubFetch(200, { ...DENY, allowed: true, reasonCode: null, message: null })
    const client = createHttpStationGateClient(BASE_URL)
    const request = {
      serialNumber: 'PANEL-DEMO-002',
      equipmentId: 'AEL-01-COAT',
      equipmentLocalAt: '2026-10-11T21:30:00',
    }

    await client.completeStation(request)
    await client.recordQualityResult({ ...request, equipmentId: 'AEL-01-UV', outcome: 'FAIL' })

    expect(sent(fetchMock, 0).url).toBe('http://mes.test/api/station-completes')
    expect(sent(fetchMock, 0).body).toStrictEqual(request)
    expect(sent(fetchMock, 1).url).toBe('http://mes.test/api/station-quality-results')
    expect(sent(fetchMock, 1).body).toStrictEqual({
      ...request,
      equipmentId: 'AEL-01-UV',
      outcome: 'FAIL',
    })
  })

  it('turns a 400 into an error carrying the MES reason', async () => {
    stubFetch(400, { status: 400, error: 'Bad Request', message: 'Unknown EQUIPMENT_ID: AEL-01-X' })
    const client = createHttpStationGateClient(BASE_URL)

    await expect(
      client.checkGate({ serialNumber: 'PANEL-DEMO-002', equipmentId: 'AEL-01-X' }),
    ).rejects.toThrow('MES rejected the request (400): Unknown EQUIPMENT_ID: AEL-01-X')
  })

  it('falls back to the HTTP status when the error body is empty', async () => {
    stubFetch(500, undefined, 'Internal Server Error')
    const client = createHttpStationGateClient(BASE_URL)

    await expect(
      client.checkGate({ serialNumber: 'PANEL-DEMO-002', equipmentId: 'AEL-01-COAT' }),
    ).rejects.toThrow('MES rejected the request (500 Internal Server Error)')
  })

  it('reports an unreachable MES clearly', async () => {
    vi.stubGlobal('fetch', vi.fn().mockRejectedValue(new TypeError('Failed to fetch')))
    const client = createHttpStationGateClient(BASE_URL)

    await expect(
      client.checkGate({ serialNumber: 'PANEL-DEMO-002', equipmentId: 'AEL-01-COAT' }),
    ).rejects.toThrow('Cannot reach MES at http://mes.test — is mes-api running?')
  })

  it('strips a trailing slash from the configured base URL', async () => {
    vi.stubEnv('VITE_MES_API_BASE_URL', 'http://mes.test/')
    vi.resetModules()
    const { createHttpStationGateClient: create } = await import('./httpClient')
    const fetchMock = stubFetch(200, DENY)

    await create().checkGate({ serialNumber: 'PANEL-DEMO-002', equipmentId: 'AEL-01-ASM' })

    expect(sent(fetchMock, 0).url).toBe('http://mes.test/api/station-gate-checks')
    vi.unstubAllEnvs()
  })
})
