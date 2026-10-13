import type { Outcome } from './api/types'

export type StationClass = 'through' | 'quality'

export interface Station {
  equipmentId: string
  label: string
  stationClass: StationClass
}

/**
 * Path stations on AEL-01, in line order. Loader is Panel Registration's screen; Cold/Hot Chamber
 * are left out for the demo (AS3-03 decision 2026-10-11).
 */
export const STATIONS: readonly Station[] = [
  { equipmentId: 'AEL-01-COAT', label: 'Conformal Coating Machine', stationClass: 'through' },
  { equipmentId: 'AEL-01-UV', label: 'UV Inspection Machine', stationClass: 'quality' },
  { equipmentId: 'AEL-01-DEPANEL', label: 'PCB Depaneler', stationClass: 'through' },
  { equipmentId: 'AEL-01-CURE', label: 'Curing Oven', stationClass: 'through' },
  { equipmentId: 'AEL-01-ASM', label: 'Assembly Station', stationClass: 'through' },
  { equipmentId: 'AEL-01-COLD-EOL', label: 'Cold EOL Tester', stationClass: 'quality' },
  { equipmentId: 'AEL-01-HOT-EOL', label: 'Hot EOL Tester', stationClass: 'quality' },
  { equipmentId: 'AEL-01-AMBIENT-EOL', label: 'Ambient EOL Tester', stationClass: 'quality' },
  { equipmentId: 'AEL-01-PACK', label: 'Packing Station', stationClass: 'through' },
]

const OP_LABELS: Record<string, string> = {
  LOADER: 'PCB Loader',
  COAT: 'Coating',
  UV: 'UV Inspection',
  DEPANEL: 'Depanel',
  CURE: 'Curing',
  ASM: 'Assembly',
  COLD_EOL: 'Cold EOL',
  HOT_EOL: 'Hot EOL',
  AMBIENT_EOL: 'Ambient EOL',
  PACK: 'Packing',
}

/** Plant label for a path op code from MES; unknown codes are shown as-is. */
export function opLabel(opCode: string): string {
  return OP_LABELS[opCode] ?? opCode
}

export function findStation(equipmentId: string): Station | undefined {
  return STATIONS.find((station) => station.equipmentId === equipmentId)
}

export function outcomesFor(stationClass: StationClass): Outcome[] {
  return stationClass === 'through' ? ['COMPLETE'] : ['PASS', 'FAIL']
}
