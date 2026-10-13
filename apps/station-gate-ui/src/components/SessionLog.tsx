import { describeResult } from '../result'
import type { Attempt } from './LatestResult'

export interface LogEntry extends Attempt {
  id: number
  at: string
}

interface SessionLogProps {
  entries: LogEntry[]
}

function summary(entry: LogEntry): string {
  if (!entry.response) {
    return `Request failed: ${entry.error}`
  }
  const view = describeResult(entry.action, entry.response)
  return view.expectedNext ? `${view.verdict} · next ${view.expectedNext}` : view.verdict
}

function SessionLog({ entries }: SessionLogProps) {
  return (
    <section className="panel" aria-labelledby="log-heading">
      <h2 id="log-heading">Session log</h2>
      <p className="hint">This browser session only — clears on refresh. Not MES history.</p>
      {entries.length === 0 ? (
        <p className="empty">No checks yet.</p>
      ) : (
        <ol className="log">
          {entries.map((entry) => (
            <li key={entry.id}>
              <span className="time">{entry.at}</span> {entry.action} · {entry.stationLabel} ·{' '}
              {entry.serialNumber} → {summary(entry)}
            </li>
          ))}
        </ol>
      )}
    </section>
  )
}

export default SessionLog
