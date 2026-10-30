import { act, fireEvent, render, screen, within } from '@testing-library/react'
import userEvent, { type UserEvent } from '@testing-library/user-event'
import { describe, expect, it, vi } from 'vitest'
import App from './App'
import { createFakeStationGateClient, type FakeClientOptions } from './test/fakeStationGateClient'

// PANEL-DEMO-002 is joined to WO-DEMO-002 on pp_cold_ambient:
// LOADER → COAT → UV → DEPANEL → CURE → ASM → COLD_EOL → AMBIENT_EOL → PACK
const SERIAL = 'PANEL-DEMO-002'
const THROUGH_COLD_EOL = { [SERIAL]: ['COAT', 'UV', 'DEPANEL', 'CURE', 'ASM', 'COLD_EOL'] }
const CAVITY_LABELS = ['PCBA-A1', 'PCBA-A2', 'PCBA-B1', 'PCBA-B2']

function setup(options?: FakeClientOptions) {
  const client = createFakeStationGateClient(options)
  const user = userEvent.setup()
  render(<App client={client} />)
  return { client, user }
}

async function gateCheck(user: UserEvent, stationLabel: string, serial = SERIAL) {
  await user.selectOptions(screen.getByLabelText('Station'), stationLabel)
  const input = screen.getByLabelText('Serial number')
  await user.clear(input)
  await user.type(input, serial)
  await user.click(screen.getByRole('button', { name: 'Gate check' }))
}

/** Split the panel, then run the 5-second cut on a fake clock so the PASS and FAIL buttons show. */
async function reachEyeCheck(client = createFakeStationGateClient()) {
  const user = userEvent.setup({ delay: null })
  render(<App client={client} />)

  await user.selectOptions(screen.getByLabelText('Station'), 'PCB Depaneler')
  await user.type(screen.getByLabelText('Serial number'), 'PANEL-DEMO-002')
  for (const label of CAVITY_LABELS) {
    await user.type(screen.getByLabelText(label), `${label}-0001`)
  }

  vi.useFakeTimers()
  try {
    fireEvent.click(screen.getByRole('button', { name: 'Start Depanel' }))
    await act(async () => {})
    act(() => {
      vi.advanceTimersByTime(5000)
    })
  } finally {
    vi.useRealTimers()
  }
  return user
}

const latest = () => within(screen.getByRole('region', { name: 'Latest result' }))
const sessionLog = () => within(screen.getByRole('region', { name: 'Session log' }))

describe('Station Gate screen (AS3-03)', () => {
  it('marks the screen as demo only', () => {
    setup()
    expect(screen.getByRole('heading', { name: 'Station Gate' })).toBeInTheDocument()
    expect(screen.getByText(/demo only/i)).toBeInTheDocument()
  })

  it('AC 1 — Assembly while Coating is next: denied with expected next Coating', async () => {
    const { user } = setup()

    await gateCheck(user, 'Assembly Station')

    expect(latest().getByText('Denied')).toBeInTheDocument()
    expect(latest().getByText('Coating (COAT)')).toBeInTheDocument()
  })

  it('AC 2 — Coating allowed, only COMPLETE offered; COMPLETE advances the path and is logged', async () => {
    const { user } = setup()

    await gateCheck(user, 'Conformal Coating Machine')

    expect(latest().getByText('Allowed')).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'COMPLETE' })).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: /PASS/ })).not.toBeInTheDocument()
    expect(screen.queryByRole('button', { name: /FAIL/ })).not.toBeInTheDocument()

    await user.click(screen.getByRole('button', { name: 'COMPLETE' }))

    expect(latest().getByText('COMPLETE recorded')).toBeInTheDocument()
    expect(latest().getByText('UV Inspection (UV)')).toBeInTheDocument()
    expect(sessionLog().getAllByRole('listitem')[1]).toHaveTextContent('COMPLETE')
  })

  it('AC 3 — Coating again after COMPLETE: denied as already done, with current expected next', async () => {
    const { user } = setup()
    await gateCheck(user, 'Conformal Coating Machine')
    await user.click(screen.getByRole('button', { name: 'COMPLETE' }))

    await gateCheck(user, 'Conformal Coating Machine')

    expect(latest().getByText('Denied')).toBeInTheDocument()
    expect(latest().getByText('Already done at this station')).toBeInTheDocument()
    expect(latest().getByText('UV Inspection (UV)')).toBeInTheDocument()
  })

  it('AC 4 — serial not on any work order: orphan deny', async () => {
    const { user } = setup()

    await gateCheck(user, 'Conformal Coating Machine', 'PANEL-NOT-JOINED')

    expect(latest().getByText('Denied')).toBeInTheDocument()
    expect(latest().getByText('Serial is not on any work order')).toBeInTheDocument()
  })

  it('AC 5 — Packing while Ambient EOL is still next: denied', async () => {
    const { user } = setup({ completedOps: THROUGH_COLD_EOL })

    await gateCheck(user, 'Packing Station')

    expect(latest().getByText('Denied')).toBeInTheDocument()
    expect(latest().getByText('Ambient EOL (AMBIENT_EOL)')).toBeInTheDocument()
  })

  it('AC 6 — Ambient PASS through this UI, then Packing is allowed and COMPLETE succeeds', async () => {
    const { user } = setup({ completedOps: THROUGH_COLD_EOL })

    await gateCheck(user, 'Ambient EOL Tester')
    await user.click(screen.getByRole('button', { name: 'PASS (stub)' }))
    expect(latest().getByText('PASS recorded (stub measurement)')).toBeInTheDocument()

    await gateCheck(user, 'Packing Station')
    expect(latest().getByText('Allowed')).toBeInTheDocument()

    await user.click(screen.getByRole('button', { name: 'COMPLETE' }))
    expect(latest().getByText('COMPLETE recorded')).toBeInTheDocument()
  })

  it('AC 7 — quality station offers only PASS/FAIL; FAIL keeps expected next, PASS advances', async () => {
    const { user } = setup({ completedOps: { [SERIAL]: ['COAT'] } })

    await gateCheck(user, 'UV Inspection Machine')
    expect(screen.getByRole('button', { name: 'PASS (stub)' })).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'FAIL (stub)' })).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: 'COMPLETE' })).not.toBeInTheDocument()

    await user.click(screen.getByRole('button', { name: 'FAIL (stub)' }))
    expect(latest().getByText('FAIL recorded (stub measurement)')).toBeInTheDocument()
    expect(latest().getByText('UV Inspection (UV)')).toBeInTheDocument()

    await gateCheck(user, 'UV Inspection Machine')
    await user.click(screen.getByRole('button', { name: 'PASS (stub)' }))
    expect(latest().getByText('Depanel (DEPANEL)')).toBeInTheDocument()
  })

  it('Depanel keeps the serial box and shows four PCBA inputs', async () => {
    const { user } = setup()

    await user.selectOptions(screen.getByLabelText('Station'), 'PCB Depaneler')

    expect(screen.getByLabelText('Serial number')).toBeInTheDocument()
    expect(screen.getByLabelText('PCBA-A1')).toBeInTheDocument()
    expect(screen.getByLabelText('PCBA-A2')).toBeInTheDocument()
    expect(screen.getByLabelText('PCBA-B1')).toBeInTheDocument()
    expect(screen.getByLabelText('PCBA-B2')).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: 'Start Depanel' })).not.toBeInTheDocument()
    expect(screen.queryByRole('button', { name: 'Send Depanel Results' })).not.toBeInTheDocument()
  })

  it('shows Start Depanel after four PCBA serials', async () => {
    const { user } = setup()

    await user.selectOptions(screen.getByLabelText('Station'), 'PCB Depaneler')
    await user.type(screen.getByLabelText('PCBA-A1'), 'PCBA-A1-0001')
    await user.type(screen.getByLabelText('PCBA-A2'), 'PCBA-A2-0001')
    await user.type(screen.getByLabelText('PCBA-B1'), 'PCBA-B1-0001')
    expect(screen.queryByRole('button', { name: 'Start Depanel' })).not.toBeInTheDocument()

    await user.type(screen.getByLabelText('PCBA-B2'), 'PCBA-B2-0001')

    expect(screen.getByRole('button', { name: 'Start Depanel' })).toBeInTheDocument()
  })

  it('Start Depanel posts the panel number and four PCBA serials', async () => {
    const client = createFakeStationGateClient()
    const splitDepanel = vi.spyOn(client, 'splitDepanel')
    const user = userEvent.setup()
    render(<App client={client} />)

    await user.selectOptions(screen.getByLabelText('Station'), 'PCB Depaneler')
    await user.type(screen.getByLabelText('Serial number'), 'PANEL-DEMO-002')
    await user.type(screen.getByLabelText('PCBA-A1'), 'PCBA-A1-0001')
    await user.type(screen.getByLabelText('PCBA-A2'), 'PCBA-A2-0001')
    await user.type(screen.getByLabelText('PCBA-B1'), 'PCBA-B1-0001')
    await user.type(screen.getByLabelText('PCBA-B2'), 'PCBA-B2-0001')
    await user.click(screen.getByRole('button', { name: 'Start Depanel' }))

    expect(splitDepanel).toHaveBeenCalledTimes(1)
    expect(splitDepanel.mock.calls[0][0]).toMatchObject({
      panelNumber: 'PANEL-DEMO-002',
      equipmentId: 'AEL-01-DEPANEL',
      cavities: [
        { slot: 'A1', cavityResult: 'PCBA-A1-0001' },
        { slot: 'A2', cavityResult: 'PCBA-A2-0001' },
        { slot: 'B1', cavityResult: 'PCBA-B1-0001' },
        { slot: 'B2', cavityResult: 'PCBA-B2-0001' },
      ],
    })
    expect(latest().getByText('COMPLETE recorded')).toBeInTheDocument()
  })

  it('shows Depanelling in process for 5 seconds, then PASS and FAIL for each PCBA, none selected', async () => {
    const user = userEvent.setup({ delay: null })
    render(<App client={createFakeStationGateClient()} />)

    await user.selectOptions(screen.getByLabelText('Station'), 'PCB Depaneler')
    await user.type(screen.getByLabelText('Serial number'), 'PANEL-DEMO-002')
    for (const label of CAVITY_LABELS) {
      await user.type(screen.getByLabelText(label), `${label}-0001`)
    }

    vi.useFakeTimers()
    try {
      fireEvent.click(screen.getByRole('button', { name: 'Start Depanel' }))
      await act(async () => {})

      expect(screen.getByText('Depanelling in process')).toBeInTheDocument()
      expect(screen.queryByRole('button', { name: /^PASS PCBA/ })).not.toBeInTheDocument()

      act(() => {
        vi.advanceTimersByTime(4999)
      })
      expect(screen.getByText('Depanelling in process')).toBeInTheDocument()

      act(() => {
        vi.advanceTimersByTime(1)
      })
      expect(screen.queryByText('Depanelling in process')).not.toBeInTheDocument()
      for (const label of CAVITY_LABELS) {
        expect(screen.getByRole('button', { name: `PASS ${label}` })).toHaveAttribute('aria-pressed', 'false')
        expect(screen.getByRole('button', { name: `FAIL ${label}` })).toHaveAttribute('aria-pressed', 'false')
      }
    } finally {
      vi.useRealTimers()
    }
  }, 15000)

  it('a click selects PASS or FAIL for one PCBA, and a second click clears it', async () => {
    const user = await reachEyeCheck()
    const passA1 = screen.getByRole('button', { name: 'PASS PCBA-A1' })
    const failA1 = screen.getByRole('button', { name: 'FAIL PCBA-A1' })

    await user.click(passA1)
    expect(passA1).toHaveAttribute('aria-pressed', 'true')
    expect(failA1).toHaveAttribute('aria-pressed', 'false')
    expect(screen.getByRole('button', { name: 'PASS PCBA-A2' })).toHaveAttribute('aria-pressed', 'false')

    await user.click(passA1)
    expect(passA1).toHaveAttribute('aria-pressed', 'false')

    await user.click(failA1)
    expect(failA1).toHaveAttribute('aria-pressed', 'true')
  }, 15000)

  it('a result for fewer than all PCBAs shows no Send Depanel Results', async () => {
    const user = await reachEyeCheck()

    for (const label of CAVITY_LABELS.slice(0, -1)) {
      await user.click(screen.getByRole('button', { name: `PASS ${label}` }))
    }

    expect(screen.queryByRole('button', { name: 'Send Depanel Results' })).not.toBeInTheDocument()
  }, 15000)

  it('a result for each PCBA shows Send Depanel Results', async () => {
    const user = await reachEyeCheck()

    const [failed, ...passed] = CAVITY_LABELS
    await user.click(screen.getByRole('button', { name: `FAIL ${failed}` }))
    for (const label of passed) {
      await user.click(screen.getByRole('button', { name: `PASS ${label}` }))
    }

    expect(screen.getByRole('button', { name: 'Send Depanel Results' })).toBeInTheDocument()
  }, 15000)

  it('Send Depanel Results posts one result for each PCBA and shows Cure for each PASS', async () => {
    const client = createFakeStationGateClient()
    const recordQualityResult = vi.spyOn(client, 'recordQualityResult')
    const user = await reachEyeCheck(client)

    const [failed, ...passed] = CAVITY_LABELS
    await user.click(screen.getByRole('button', { name: `FAIL ${failed}` }))
    for (const label of passed) {
      await user.click(screen.getByRole('button', { name: `PASS ${label}` }))
    }
    await user.click(screen.getByRole('button', { name: 'Send Depanel Results' }))

    expect(recordQualityResult).toHaveBeenCalledTimes(CAVITY_LABELS.length)
    const sent = recordQualityResult.mock.calls.map(([request]) => request)
    expect(sent).toContainEqual(
      expect.objectContaining({ serialNumber: `${failed}-0001`, equipmentId: 'AEL-01-DEPANEL', outcome: 'FAIL' }),
    )
    for (const label of passed) {
      expect(sent).toContainEqual(
        expect.objectContaining({ serialNumber: `${label}-0001`, equipmentId: 'AEL-01-DEPANEL', outcome: 'PASS' }),
      )
    }
    expect(screen.getAllByText('Next station: Curing (CURE)')).toHaveLength(passed.length)
    expect(screen.getByText('Next station: Depanel (DEPANEL)')).toBeInTheDocument()
  }, 15000)

  it('AC 8 — station dropdown lists path stations only, no chambers or Loader', () => {
    setup()

    const options = within(screen.getByLabelText('Station'))
      .getAllByRole('option')
      .map((option) => option.textContent)

    expect(options).toHaveLength(9)
    expect(options).not.toContain('Cold Chamber')
    expect(options).not.toContain('Hot Chamber')
    expect(options).not.toContain('PCB Loader')
  })

  it('AC 9 — gate check sends only serialNumber and equipmentId; no work order field', async () => {
    const client = createFakeStationGateClient()
    const checkGate = vi.spyOn(client, 'checkGate')
    const user = userEvent.setup()
    render(<App client={client} />)

    await gateCheck(user, 'Assembly Station')

    expect(checkGate).toHaveBeenCalledTimes(1)
    expect(checkGate.mock.calls[0][0]).toStrictEqual({
      serialNumber: SERIAL,
      equipmentId: 'AEL-01-ASM',
    })
    expect(screen.queryByLabelText(/work order/i)).not.toBeInTheDocument()
  })

  it('AC 10 — session log adds one line per check and per outcome, and is not labeled MES history', async () => {
    const { user } = setup()

    await gateCheck(user, 'Assembly Station')
    await gateCheck(user, 'Conformal Coating Machine')
    await user.click(screen.getByRole('button', { name: 'COMPLETE' }))

    expect(sessionLog().getAllByRole('listitem')).toHaveLength(3)
    expect(sessionLog().getByText(/clears on refresh/i)).toHaveTextContent('Not MES history')
  })

  it('AC 11 — mocked quality results are labeled as stub', async () => {
    const { user } = setup({ completedOps: { [SERIAL]: ['COAT'] } })

    await gateCheck(user, 'UV Inspection Machine')

    expect(screen.getByRole('button', { name: 'PASS (stub)' })).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'FAIL (stub)' })).toBeInTheDocument()
  })

  it('hides outcome buttons when the serial changes after an allowed check', async () => {
    const { user } = setup()
    await gateCheck(user, 'Conformal Coating Machine')
    expect(screen.getByRole('button', { name: 'COMPLETE' })).toBeInTheDocument()

    await user.type(screen.getByLabelText('Serial number'), 'X')

    expect(screen.queryByRole('button', { name: 'COMPLETE' })).not.toBeInTheDocument()
  })
})
