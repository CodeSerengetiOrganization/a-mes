import { describe, expect, it } from 'vitest'
import { toEquipmentLocalAt } from './equipmentLocalAt'

describe('toEquipmentLocalAt', () => {
  it('formats local wall-clock time with no offset, zero-padded', () => {
    expect(toEquipmentLocalAt(new Date(2026, 7, 6, 8, 5, 9))).toBe('2026-08-06T08:05:09')
  })
})
