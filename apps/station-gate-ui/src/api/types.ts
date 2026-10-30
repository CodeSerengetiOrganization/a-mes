// Shapes mirror services/mes-api/docs/api/station-skip-ahead-gate.md,
// station-completes.md and station-quality-results.md.

export type QualityOutcome = 'PASS' | 'FAIL'
export type Outcome = 'COMPLETE' | QualityOutcome

export interface GateCheckRequest {
  serialNumber: string
  equipmentId: string
}

export interface StationCompleteRequest extends GateCheckRequest {
  /** Station local time, no offset — e.g. `2026-08-26T08:15:00` (Java LocalDateTime). */
  equipmentLocalAt: string
}

export interface QualityResultRequest extends StationCompleteRequest {
  outcome: QualityOutcome
}

/** Same body on all three endpoints. Plant denies are `200` with `allowed: false`. */
export interface StationGateResponse {
  allowed: boolean
  workOrderId: string | null
  processPathId: string | null
  thisOp: string | null
  expectedNext: string | null
  /** `ORPHAN` · `WRONG_STATION` · `ALREADY_COMPLETE` · `WRONG_ENDPOINT`; `null` on allow. */
  reasonCode: string | null
  message: string | null
  /** Set only when evidence was appended. */
  outcome?: Outcome | null
}

export interface DepanelCavity {
  slot: string
  cavityResult: string
}

export interface DepanelSplitRequest {
  panelNumber: string
  equipmentId: string
  equipmentLocalAt: string
  cavities: DepanelCavity[]
}

/** `POST /api/depanel-splits`. `stored` is the plant fact. This body has no `allowed` field. */
export interface DepanelSplitResponse {
  stored: boolean
  panelNumber: string
  reasonCode: string | null
  message: string | null
  outcome: string | null
}

export interface StationGateClient {
  checkGate(request: GateCheckRequest): Promise<StationGateResponse>
  completeStation(request: StationCompleteRequest): Promise<StationGateResponse>
  recordQualityResult(request: QualityResultRequest): Promise<StationGateResponse>
  splitDepanel(request: DepanelSplitRequest): Promise<DepanelSplitResponse>
}
