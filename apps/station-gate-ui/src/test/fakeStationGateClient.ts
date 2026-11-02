import type {
  DepanelSplitRequest,
  DepanelSplitResponse,
  GateCheckRequest,
  QualityResultRequest,
  StationCompleteRequest,
  StationGateClient,
  StationGateResponse,
} from '../api/types'

// Screen-test double only. It mirrors the gate order in StationGateService so screen tests can walk
// a serial end to end; mes-api tests own the gate rules, and this file is not a source of truth.

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

const THROUGH_OPS = new Set(['COAT', 'CURE', 'ASM', 'PACK'])
const QUALITY_OPS = new Set(['UV', 'DEPANEL', 'COLD_EOL', 'HOT_EOL', 'AMBIENT_EOL'])

export interface FakeClientOptions {
  /** Ops already done per serial, beyond LOADER (which the join satisfies). */
  completedOps?: Record<string, string[]>
}

type Kind = 'check' | 'complete' | 'quality'

export function createFakeStationGateClient(options: FakeClientOptions = {}): StationGateClient {
  const joins = new Map(Object.entries(DEMO_JOINS))
  const splitPanels = new Set<string>()
  const done = new Map<string, Set<string>>()
  for (const serial of joins.keys()) {
    done.set(serial, new Set(['LOADER', ...(options.completedOps?.[serial] ?? [])]))
  }

  function expectedNext(serial: string): string | null {
    const join = joins.get(serial)!
    const completed = done.get(serial)!
    return PROCESS_PATH_OPS[join.processPathId].find((op) => !completed.has(op)) ?? null
  }

  function evaluate(request: GateCheckRequest, kind: Kind): StationGateResponse {
    const serial = request.serialNumber.trim()
    const thisOp = EQUIPMENT_TO_OP[request.equipmentId]
    if (!thisOp) {
      throw new Error(`Unknown equipmentId: ${request.equipmentId}`)
    }

    const join = joins.get(serial)
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
    async splitDepanel(request: DepanelSplitRequest) {
      const panelNumber = request.panelNumber.trim()
      const panelJoin = joins.get(panelNumber)
      if (!panelJoin) {
        return refuseSplit(panelNumber, 'ORPHAN', 'Panel is not on a work order. MES stored nothing.')
      }
      if (splitPanels.has(panelNumber)) {
        return refuseSplit(panelNumber, 'ALREADY_COMPLETE', 'This panel is already split. MES stored nothing.')
      }
      const serials = request.cavities.map((cavity) => cavity.cavityResult.trim())
      if (serials.some((serial) => !isPcbaSerial(serial)) || new Set(serials).size !== serials.length) {
        return refuseSplit(panelNumber, 'HOLD', 'Bad cavity. MES stored nothing.')
      }

      splitPanels.add(panelNumber)
      const ops = PROCESS_PATH_OPS[panelJoin.processPathId]
      const beforeDepanel = ops.slice(0, ops.indexOf('DEPANEL'))
      for (const serial of serials) {
        joins.set(serial, panelJoin)
        done.set(serial, new Set(beforeDepanel))
      }
      return {
        stored: true,
        panelNumber,
        reasonCode: null,
        message: null,
        outcome: 'COMPLETE',
      }
    },
  }
}

/** Same shape as DepanelSplitService: XOUT, EMPTY, and UNREADABLE fail this too, because they do not start with PCBA. */
function isPcbaSerial(serial: string): boolean {
  return serial.startsWith('PCBA') && serial.length >= 10 && serial.length <= 64
}

function refuseSplit(panelNumber: string, reasonCode: string, message: string): DepanelSplitResponse {
  return { stored: false, panelNumber, reasonCode, message, outcome: null }
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
