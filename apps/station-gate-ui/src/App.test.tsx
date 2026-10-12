import { render, screen, within } from '@testing-library/react'
import userEvent, { type UserEvent } from '@testing-library/user-event'
import { describe, expect, it, vi } from 'vitest'
import App from './App'
import { createFakeStationGateClient, type FakeClientOptions } from './api/fakeClient'

// PANEL-DEMO-002 is joined to WO-DEMO-002 on pp_cold_ambient:
// LOADER → COAT → UV → DEPANEL → CURE → ASM → COLD_EOL → AMBIENT_EOL → PACK
const SERIAL = 'PANEL-DEMO-002'
const THROUGH_COLD_EOL = { [SERIAL]: ['COAT', 'UV', 'DEPANEL', 'CURE', 'ASM', 'COLD_EOL'] }

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
