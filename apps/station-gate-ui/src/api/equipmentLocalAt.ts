const pad = (value: number) => String(value).padStart(2, '0')

/** Local wall-clock time without offset, the format mes-api binds to `LocalDateTime`. */
export function toEquipmentLocalAt(date: Date): string {
  return (
    `${date.getFullYear()}-${pad(date.getMonth() + 1)}-${pad(date.getDate())}` +
    `T${pad(date.getHours())}:${pad(date.getMinutes())}:${pad(date.getSeconds())}`
  )
}
