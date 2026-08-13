import { useState } from 'react'
import './App.css'

const MES_API_BASE_URL = (
  import.meta.env.VITE_MES_API_BASE_URL ?? 'http://localhost:8080'
).replace(/\/$/, '')

const API_URL = `${MES_API_BASE_URL}/api/panel-registrations`

function denyReason(data: unknown, status: number): string {
  if (data && typeof data === 'object') {
    const body = data as Record<string, unknown>
    if (typeof body.detail === 'string' && body.detail.trim()) {
      return body.detail
    }
    if (typeof body.message === 'string' && body.message.trim()) {
      return body.message
    }
    if (typeof body.title === 'string' && body.title.trim()) {
      return body.title
    }
  }
  return `Registration rejected (${status})`
}

function App() {
  const [panelSerial, setPanelSerial] = useState('')
  const [workOrderId, setWorkOrderId] = useState('')
  const [message, setMessage] = useState('')
  const [messageKind, setMessageKind] = useState<'ok' | 'error' | ''>('')
  const [submitting, setSubmitting] = useState(false)

  async function handleRegister() {
    if (submitting) {
      return
    }

    setSubmitting(true)
    setMessage('Registering...')
    setMessageKind('')

    try {
      const response = await fetch(API_URL, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({
          panelNumber: panelSerial,
          workOrderId: workOrderId,
        }),
      })

      const data = await response.json().catch(() => null)

      if (!response.ok) {
        setMessageKind('error')
        setMessage(denyReason(data, response.status))
        return
      }

      const panelNumber =
        data && typeof data === 'object' && typeof data.panelNumber === 'string'
          ? data.panelNumber
          : panelSerial
      const registeredWorkOrder =
        data && typeof data === 'object' && typeof data.workOrderId === 'string'
          ? data.workOrderId
          : workOrderId

      setMessageKind('ok')
      setMessage(
        `Success! Panel ${panelNumber} registered under work order ${registeredWorkOrder}.`,
      )
      setPanelSerial('')
    } catch (error) {
      setMessageKind('error')
      setMessage('Something went wrong. Please try again.')
      console.error(error)
    } finally {
      setSubmitting(false)
    }
  }

  return (
    <main className="page">
      <h1>Panel Registration</h1>
      <p className="station">Working station: PCB Loader</p>

      <label className="field">
        Work order
        <input
          type="text"
          value={workOrderId}
          onChange={(e) => setWorkOrderId(e.target.value)}
          placeholder="Scan or type work order id"
        />
      </label>

      <label className="field">
        Panel serial number
        <div className="input-row">
          <input
            type="text"
            value={panelSerial}
            onChange={(e) => setPanelSerial(e.target.value)}
            placeholder="Scan or type panel serial"
            autoFocus
          />
          <button
            type="button"
            className="submit"
            onClick={handleRegister}
            disabled={submitting}
          >
            {submitting ? 'Registering...' : 'Register panel'}
          </button>
        </div>
      </label>

      {message ? (
        <p className={`message ${messageKind}`.trim()}>{message}</p>
      ) : null}
    </main>
  )
}

export default App
