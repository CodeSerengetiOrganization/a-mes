import type { StationGateResponse } from '../api/types'
import { describeResult, type Action } from '../result'

export interface Attempt {
  action: Action
  stationLabel: string
  serialNumber: string
  response?: StationGateResponse
  error?: string
}

interface LatestResultProps {
  latest: Attempt | null
}

function LatestResult({ latest }: LatestResultProps) {
  return (
    <section className="panel" aria-labelledby="latest-heading">
      <h2 id="latest-heading">Latest result</h2>
      {latest ? (
        <ResultBody attempt={latest} />
      ) : (
        <p className="empty">Pick a station, enter a serial number, then run a gate check.</p>
      )}
    </section>
  )
}

function ResultBody({ attempt }: { attempt: Attempt }) {
  if (!attempt.response) {
    return (
      <>
        <p className="verdict denied">Request failed</p>
        <p className="reason">{attempt.error}</p>
      </>
    )
  }

  const view = describeResult(attempt.action, attempt.response)
  const { workOrderId, processPathId } = attempt.response

  return (
    <>
      <p className={`verdict ${view.tone}`}>{view.verdict}</p>
      <dl className="details">
        <dt>Station</dt>
        <dd>{attempt.stationLabel}</dd>
        <dt>Serial number</dt>
        <dd>{attempt.serialNumber}</dd>
        {view.reason ? (
          <>
            <dt>Reason</dt>
            <dd>{view.reason}</dd>
          </>
        ) : null}
        {view.expectedNext ? (
          <>
            <dt>Expected next</dt>
            <dd>{view.expectedNext}</dd>
          </>
        ) : null}
        {workOrderId ? (
          <>
            <dt>Work order</dt>
            <dd>{workOrderId}</dd>
          </>
        ) : null}
        {processPathId ? (
          <>
            <dt>Process path</dt>
            <dd>{processPathId}</dd>
          </>
        ) : null}
      </dl>
    </>
  )
}

export default LatestResult
