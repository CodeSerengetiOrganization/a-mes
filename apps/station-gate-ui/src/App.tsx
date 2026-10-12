import { useState, type FormEvent } from 'react'
import './App.css'
import { toEquipmentLocalAt } from './api/equipmentLocalAt'
import type { Outcome, StationGateClient, StationGateResponse } from './api/types'
import LatestResult, { type Attempt } from './components/LatestResult'
import SessionLog, { type LogEntry } from './components/SessionLog'
import type { Action } from './result'
import { STATIONS, findStation, outcomesFor } from './stations'

interface AppProps {
  client: StationGateClient
}

function App({ client }: AppProps) {
  const [equipmentId, setEquipmentId] = useState(STATIONS[0].equipmentId)
  const [serialNumber, setSerialNumber] = useState('')
  const [checkAllowed, setCheckAllowed] = useState(false)
  const [latest, setLatest] = useState<Attempt | null>(null)
  const [log, setLog] = useState<LogEntry[]>([])
  const [submitting, setSubmitting] = useState(false)

  const station = findStation(equipmentId)!
  const serial = serialNumber.trim()

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

  function handleCheck(event: FormEvent) {
    event.preventDefault()
    if (!serial) {
      return
    }
    void run('Check', () => client.checkGate({ serialNumber: serial, equipmentId }))
  }

  function handleOutcome(outcome: Outcome) {
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
            onChange={(e) => {
              setEquipmentId(e.target.value)
              setCheckAllowed(false)
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
            onChange={(e) => {
              setSerialNumber(e.target.value)
              setCheckAllowed(false)
            }}
            placeholder="Scan or type serial number"
            autoFocus
          />
        </div>

        <div className="actions">
          <button type="submit" className="button check" disabled={submitting || !serial}>
            Gate check
          </button>
          {checkAllowed
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

export default App
