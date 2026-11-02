import { useState, type FormEvent } from 'react'
import './App.css'
import { toEquipmentLocalAt } from './api/equipmentLocalAt'
import type {
  DepanelSplitResponse,
  Outcome,
  QualityOutcome,
  StationGateClient,
  StationGateResponse,
} from './api/types'
import LatestResult, { type Attempt } from './components/LatestResult'
import SessionLog, { type LogEntry } from './components/SessionLog'
import type { Action } from './result'
import { STATIONS, findStation, isAtOrAfterCure, opLabel, outcomesFor } from './stations'

interface AppProps {
  client: StationGateClient
}

const CUT_SIMULATION_MS = 8000
/** Cavity slots on this part's panel. The number of cavities comes from this list only. */
const CAVITY_SLOTS = ['A1', 'A2', 'B1', 'B2']

const cavityLabel = (slot: string) => `PCBA-${slot}`
const emptyCavitySerials = () => CAVITY_SLOTS.map(() => '')
const emptyDepanelResults = (): (QualityOutcome | null)[] => CAVITY_SLOTS.map(() => null)

type DepanelStep = 'scan' | 'cutting' | 'eyeCheck'

function App({ client }: AppProps) {
  const [equipmentId, setEquipmentId] = useState(STATIONS[0].equipmentId)
  const [serialNumber, setSerialNumber] = useState('')
  const [cavitySerials, setCavitySerials] = useState(emptyCavitySerials)
  const [depanelStep, setDepanelStep] = useState<DepanelStep>('scan')
  const [depanelResults, setDepanelResults] = useState(emptyDepanelResults)
  const [depanelSent, setDepanelSent] = useState<Attempt[]>([])
  const [splitPanel, setSplitPanel] = useState<string | null>(null)
  const [checkAllowed, setCheckAllowed] = useState(false)
  const [latest, setLatest] = useState<Attempt | null>(null)
  const [log, setLog] = useState<LogEntry[]>([])
  const [submitting, setSubmitting] = useState(false)

  const station = findStation(equipmentId)!
  const serial = serialNumber.trim()
  const isDepanel = station.equipmentId === 'AEL-01-DEPANEL'
  const canStartDepanel =
    isDepanel && depanelStep === 'scan' && cavitySerials.every((value) => value.trim() !== '')
  const cutting = depanelStep === 'cutting'
  /** A stored quality result changes only through rework, so one stored result locks the whole eye check. */
  const anyResultStored = depanelSent.some((attempt) => attempt.response?.allowed === true)
  const canSendDepanelResults =
    isDepanel &&
    depanelStep === 'eyeCheck' &&
    depanelResults.every((result) => result !== null) &&
    !anyResultStored

  async function run(action: Action, call: () => Promise<StationGateResponse>) {
    if (submitting) {
      return
    }
    setSubmitting(true)

    let attempt: Attempt
    try {
      const response = await call()
      attempt = { action, stationLabel: station.label, serialNumber: serial, response }
      setCheckAllowed(action === 'Check' && response.allowed)
    } catch (error) {
      const message = error instanceof Error ? error.message : String(error)
      attempt = { action, stationLabel: station.label, serialNumber: serial, error: message }
      setCheckAllowed(false)
    } finally {
      setSubmitting(false)
    }

    setLatest(attempt)
    const at = new Date().toLocaleTimeString()
    setLog((entries) => [...entries, { ...attempt, id: entries.length + 1, at }])
  }

  function resetDepanel() {
    setCavitySerials(emptyCavitySerials())
    setDepanelResults(emptyDepanelResults())
    setDepanelSent([])
    setDepanelStep('scan')
    setSplitPanel(null)
  }

  /** Runs when a scan in the serial box ends. Keystrokes alone cannot tell a new panel number from the same one. */
  function finishPanelScan() {
    if (splitPanel !== null && serial !== splitPanel) {
      resetDepanel()
    }
  }

  function handleCheck(event: FormEvent) {
    event.preventDefault()
    finishPanelScan()
    // Enter still submits the form at PCB Depaneler, where the serial box holds the panel number.
    if (isDepanel || !serial) {
      return
    }
    void run('Check', () => client.checkGate({ serialNumber: serial, equipmentId }))
  }

  function handleStartDepanel() {
    void run('COMPLETE', async () => {
      const split = await client.splitDepanel({
        panelNumber: serial,
        equipmentId,
        equipmentLocalAt: toEquipmentLocalAt(new Date()),
        cavities: cavitySerials.map((value, index) => ({
          slot: CAVITY_SLOTS[index],
          cavityResult: value.trim(),
        })),
      })
      if (split.stored) {
        setSplitPanel(serial)
        setDepanelResults(emptyDepanelResults())
        setDepanelSent([])
        setDepanelStep('cutting')
        setTimeout(() => setDepanelStep('eyeCheck'), CUT_SIMULATION_MS)
      }
      return toGateResponse(split)
    })
  }

  function toggleDepanelResult(index: number, outcome: QualityOutcome) {
    setDepanelResults((results) =>
      results.map((current, i) => (i === index ? (current === outcome ? null : outcome) : current)),
    )
  }

  async function handleSendDepanelResults() {
    if (submitting) {
      return
    }
    setSubmitting(true)

    const equipmentLocalAt = toEquipmentLocalAt(new Date())
    const attempts = await Promise.all(
      cavitySerials.map(async (value, index): Promise<Attempt> => {
        const pcbaSerial = value.trim()
        const outcome = depanelResults[index]!
        try {
          const response = await client.recordQualityResult({
            serialNumber: pcbaSerial,
            equipmentId,
            equipmentLocalAt,
            outcome,
          })
          return { action: outcome, stationLabel: station.label, serialNumber: pcbaSerial, response }
        } catch (error) {
          const message = error instanceof Error ? error.message : String(error)
          return { action: outcome, stationLabel: station.label, serialNumber: pcbaSerial, error: message }
        }
      }),
    )
    setSubmitting(false)

    setDepanelSent(attempts)
    setLatest(attempts[attempts.length - 1])
    const at = new Date().toLocaleTimeString()
    setLog((entries) => [
      ...entries,
      ...attempts.map((attempt, i) => ({ ...attempt, id: entries.length + i + 1, at })),
    ])
  }

  function handleOutcome(outcome: Outcome) {
    if (isDepanel) {
      return
    }
    const request = {
      serialNumber: serial,
      equipmentId,
      equipmentLocalAt: toEquipmentLocalAt(new Date()),
    }
    void run(outcome, () =>
      outcome === 'COMPLETE'
        ? client.completeStation(request)
        : client.recordQualityResult({ ...request, outcome }),
    )
  }

  return (
    <main className="page">
      <header className="header">
        <h1>Station Gate</h1>
        <p className="demo-note">Demo only — not a floor station screen</p>
      </header>

      <form className="controls" onSubmit={handleCheck}>
        <div className="field">
          <label htmlFor="station">Station</label>
          <select
            id="station"
            value={equipmentId}
            disabled={cutting}
            onChange={(e) => {
              setEquipmentId(e.target.value)
              setCheckAllowed(false)
              if (isAtOrAfterCure(e.target.value)) {
                setSerialNumber('')
              }
            }}
          >
            {STATIONS.map((option) => (
              <option key={option.equipmentId} value={option.equipmentId}>
                {option.label}
              </option>
            ))}
          </select>
        </div>

        <div className="field">
          <label htmlFor="serial-number">Serial number</label>
          <input
            id="serial-number"
            type="text"
            value={serialNumber}
            disabled={cutting}
            onChange={(e) => {
              setSerialNumber(e.target.value)
              setCheckAllowed(false)
            }}
            onBlur={finishPanelScan}
            placeholder={isDepanel ? 'Scan panel number' : 'Scan or type serial number'}
            autoFocus
          />
        </div>

        {isDepanel
          ? cavitySerials.map((value, index) => {
              const label = cavityLabel(CAVITY_SLOTS[index])
              return (
              <div className="field" key={label}>
                <label htmlFor={`pcba-${label}`}>{label}</label>
                <input
                  id={`pcba-${label}`}
                  type="text"
                  value={value}
                  disabled={cutting}
                  onChange={(event) => {
                    const next = [...cavitySerials]
                    next[index] = event.target.value
                    setCavitySerials(next)
                  }}
                />
                {depanelStep === 'eyeCheck' ? (
                  <div className="cavity-results">
                    {(['PASS', 'FAIL'] as const).map((outcome) => (
                      <button
                        key={outcome}
                        type="button"
                        className={`button outcome ${outcome.toLowerCase()}`}
                        aria-label={`${outcome} ${label}`}
                        aria-pressed={depanelResults[index] === outcome}
                        disabled={anyResultStored}
                        onClick={() => toggleDepanelResult(index, outcome)}
                      >
                        {outcome}
                      </button>
                    ))}
                  </div>
                ) : null}
                {nextOp(depanelSent[index]) ? (
                  <p className="cavity-next">
                    {`Next station: ${opLabel(nextOp(depanelSent[index])!)} (${nextOp(depanelSent[index])})`}
                  </p>
                ) : null}
              </div>
              )
            })
          : null}

        {isDepanel && cutting ? (
          <p className="depanel-status" role="status">
            Depanelling in process
          </p>
        ) : null}

        <div className="actions">
          <button
            type="submit"
            className="button check"
            hidden={isDepanel}
            disabled={submitting || !serial}
          >
            Gate check
          </button>
          {canStartDepanel ? (
            <button type="button" className="button" onClick={handleStartDepanel}>
              Start Depanel
            </button>
          ) : null}
          {canSendDepanelResults ? (
            <button
              type="button"
              className="button"
              onClick={() => void handleSendDepanelResults()}
              disabled={submitting}
            >
              Send Depanel Results
            </button>
          ) : null}
          {checkAllowed && !isDepanel
            ? outcomesFor(station.stationClass).map((outcome) => (
                <button
                  key={outcome}
                  type="button"
                  className={`button outcome ${outcome.toLowerCase()}`}
                  onClick={() => handleOutcome(outcome)}
                  disabled={submitting}
                >
                  {outcome === 'COMPLETE' ? outcome : `${outcome} (stub)`}
                </button>
              ))
            : null}
        </div>
      </form>

      <div className="panels">
        <LatestResult latest={latest} />
        <SessionLog entries={log} />
      </div>
    </main>
  )
}

function nextOp(attempt: Attempt | undefined): string | null {
  return attempt?.response?.expectedNext ?? null
}

function toGateResponse(split: DepanelSplitResponse): StationGateResponse {
  return {
    allowed: split.stored,
    workOrderId: null,
    processPathId: null,
    thisOp: 'DEPANEL',
    expectedNext: null,
    // The split endpoint already sends plant wording; a reason code here would swap it for gate-check text.
    reasonCode: null,
    message: split.message,
    outcome: split.stored ? 'COMPLETE' : null,
  }
}

export default App
