import type {
  GateCheckRequest,
  QualityResultRequest,
  StationCompleteRequest,
  StationGateClient,
  StationGateResponse,
} from './types'

export const MES_API_BASE_URL = (
  import.meta.env.VITE_MES_API_BASE_URL ?? 'http://localhost:8080'
).replace(/\/$/, '')

function errorReason(data: unknown, status: number, statusText: string): string {
  if (data && typeof data === 'object') {
    const body = data as Record<string, unknown>
    for (const key of ['detail', 'message', 'error', 'title']) {
      const value = body[key]
      if (typeof value === 'string' && value.trim()) {
        return `MES rejected the request (${status}): ${value}`
      }
    }
  }
  return `MES rejected the request (${status}${statusText ? ` ${statusText}` : ''})`
}

export function createHttpStationGateClient(baseUrl: string = MES_API_BASE_URL): StationGateClient {
  async function post(path: string, body: unknown): Promise<StationGateResponse> {
    let response: Response
    try {
      response = await fetch(`${baseUrl}${path}`, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify(body),
      })
    } catch {
      throw new Error(`Cannot reach MES at ${baseUrl} — is mes-api running?`)
    }

    const data: unknown = await response.json().catch(() => null)
    if (!response.ok) {
      throw new Error(errorReason(data, response.status, response.statusText))
    }
    if (!data || typeof data !== 'object' || typeof (data as StationGateResponse).allowed !== 'boolean') {
      throw new Error(`Unexpected response from MES (${response.status})`)
    }
    return data as StationGateResponse
  }

  return {
    checkGate: (request: GateCheckRequest) => post('/api/station-gate-checks', request),
    completeStation: (request: StationCompleteRequest) => post('/api/station-completes', request),
    recordQualityResult: (request: QualityResultRequest) =>
      post('/api/station-quality-results', request),
  }
}
