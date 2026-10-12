import type { Outcome, StationGateResponse } from './api/types'
import { opLabel } from './stations'

export type Action = 'Check' | Outcome

export type Tone = 'allowed' | 'denied' | 'fail'

export interface ResultView {
  verdict: string
  tone: Tone
  reason: string | null
  expectedNext: string | null
}

const REASON_TEXT: Record<string, string> = {
  ORPHAN: 'Serial is not on any work order',
  WRONG_STATION: 'Wrong station for this serial — skip-ahead blocked',
  ALREADY_COMPLETE: 'Already done at this station',
}

/** Plant wording for a MES response. MES decides; this only labels what came back. */
export function describeResult(action: Action, response: StationGateResponse): ResultView {
  const expectedNext = response.expectedNext
    ? `${opLabel(response.expectedNext)} (${response.expectedNext})`
    : null

  if (!response.allowed) {
    const reason = response.reasonCode
      ? (REASON_TEXT[response.reasonCode] ?? response.message ?? response.reasonCode)
      : response.message
    return { verdict: 'Denied', tone: 'denied', reason, expectedNext }
  }

  if (action === 'Check') {
    return { verdict: 'Allowed', tone: 'allowed', reason: null, expectedNext }
  }

  const stub = action === 'COMPLETE' ? '' : ' (stub measurement)'
  return {
    verdict: `${action} recorded${stub}`,
    tone: action === 'FAIL' ? 'fail' : 'allowed',
    reason: null,
    expectedNext,
  }
}
