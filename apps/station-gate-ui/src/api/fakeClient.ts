import type {
  GateCheckRequest,
  QualityResultRequest,
  StationCompleteRequest,
  StationGateClient,
  StationGateResponse,
} from './types'

// Stand-in for mes-api until the real client is wired (AS3-03 step 2). It follows the gate order
// in StationGateService so the screen can be walked end to end; it is not a source of truth.

const PROCESS_PATH_OPS: Record<string, string[]> = {
  pp_full_eol: ['LOADER', 'COAT', 'UV', 'DEPANEL', 'CURE', 'ASM', 'COLD_EOL', 'HOT_EOL', 'AMBIENT_EOL', 'PACK'],
  pp_cold_ambient: ['LOADER', 'COAT', 'UV', 'DEPANEL', 'CURE', 'ASM', 'COLD_EOL', 'AMBIENT_EOL', 'PACK'],
  pp_ambient_only: ['LOADER', 'COAT', 'UV', 'DEPANEL', 'CURE', 'ASM', 'AMBIENT_EOL', 'PACK'],
}

const DEMO_JOINS: Record<string, { workOrderId: string; processPathId: string }> = {
  'PANEL-DEMO-001': { workOrderId: 'WO-DEMO-001', processPathId: 'pp_full_eol' },
  'PANEL-DEMO-002': { workOrderId: 'WO-DEMO-002', processPathId: 'pp_cold_ambient' },
  'PANEL-DEMO-003': { workOrderId: 'WO-DEMO-003', processPathId: 'pp_ambient_only' },
}

const EQUIPMENT_TO_OP: Record<string, string> = {
  'AEL-01-LOADER': 'LOADER',
  'AEL-01-COAT': 'COAT',
  'AEL-01-UV': 'UV',
  'AEL-01-DEPANEL': 'DEPANEL',
  'AEL-01-CURE': 'CURE',
  'AEL-01-ASM': 'ASM',
  'AEL-01-COLD-EOL': 'COLD_EOL',
  'AEL-01-HOT-EOL': 'HOT_EOL',
  'AEL-01-AMBIENT-EOL': 'AMBIENT_EOL',
  'AEL-01-PACK': 'PACK',
}

const THROUGH_OPS = new Set(['COAT', 'DEPANEL', 'CURE', 'ASM', 'PACK'])
const QUALITY_OPS = new Set(['UV', 'COLD_EOL', 'HOT_EOL', 'AMBIENT_EOL'])

export interface FakeClientOptions {
  /** Ops already done per serial, beyond LOADER (which the join satisfies). */
  completedOps?: Record<string, string[]>
}

type Kind = 'check' | 'complete' | 'quality'

export function createFakeStationGateClient(options: FakeClientOptions = {}): StationGateClient {
  const done = new Map<string, Set<string>>()
  for (const serial of Object.keys(DEMO_JOINS)) {
    done.set(serial, new Set(['LOADER', ...(options.completedOps?.[serial] ?? [])]))
  }

  function expectedNext(serial: string): string | null {
    const join = DEMO_JOINS[serial]
    const completed = done.get(serial)!
    return PROCESS_PATH_OPS[join.processPathId].find((op) => !completed.has(op)) ?? null
  }

  function evaluate(request: GateCheckRequest, kind: Kind): StationGateResponse {
    const serial = request.serialNumber.trim()
    const thisOp = EQUIPMENT_TO_OP[request.equipmentId]
    if (!thisOp) {
      throw new Error(`Unknown equipmentId: ${request.equipmentId}`)
    }

    const join = DEMO_JOINS[serial]
    if (!join) {
      return deny(null, null, thisOp, 'ORPHAN', null, 'Serial not joined to a work order')
    }

    const next = expectedNext(serial)
    if (kind === 'complete' && !THROUGH_OPS.has(thisOp)) {
      return deny(join.workOrderId, join.processPathId, thisOp, 'WRONG_ENDPOINT', next,
        'This station cannot append COMPLETE via station-completes')
    }
    if (kind === 'quality' && !QUALITY_OPS.has(thisOp)) {
      return deny(join.workOrderId, join.processPathId, thisOp, 'WRONG_ENDPOINT', next,
        'This station cannot append PASS/FAIL via the quality API')
    }
    if (done.get(serial)!.has(thisOp)) {
      return deny(join.workOrderId, join.processPathId, thisOp, 'ALREADY_COMPLETE', next, null)
    }
    if (thisOp !== next) {
      return deny(join.workOrderId, join.processPathId, thisOp, 'WRONG_STATION', next,
        next ? `Expected next: ${next}` : null)
    }
    return {
      allowed: true,
      workOrderId: join.workOrderId,
      processPathId: join.processPathId,
      thisOp,
      expectedNext: next,
      reasonCode: null,
      message: null,
    }
  }

  return {
    async checkGate(request) {
      return evaluate(request, 'check')
    },
    async completeStation(request: StationCompleteRequest) {
      const response = evaluate(request, 'complete')
      if (!response.allowed) {
        return response
      }
      const serial = request.serialNumber.trim()
      done.get(serial)!.add(response.thisOp!)
      return { ...response, expectedNext: expectedNext(serial), outcome: 'COMPLETE' }
    },
    async recordQualityResult(request: QualityResultRequest) {
      const response = evaluate(request, 'quality')
      if (!response.allowed) {
        return response
      }
      const serial = request.serialNumber.trim()
      if (request.outcome === 'PASS') {
        done.get(serial)!.add(response.thisOp!)
      }
      return { ...response, expectedNext: expectedNext(serial), outcome: request.outcome }
    },
  }
}

function deny(
  workOrderId: string | null,
  processPathId: string | null,
  thisOp: string,
  reasonCode: string,
  expectedNext: string | null,
  message: string | null,
): StationGateResponse {
  return { allowed: false, workOrderId, processPathId, thisOp, expectedNext, reasonCode, message }
}
