import { describe, expect, it } from 'vitest'
import { calculateStreak, canCheckIn, taskStatus } from './tasks.js'

const monWedFri = [true, false, true, false, true, false, false]

function task(weekdays = monWedFri, startTime = '08:00', endTime = '22:00') {
  return { id: 'task-1', name: 'Exercise', weekdays, startTime, endTime }
}

describe('task status', () => {
  it('allows check-in only on a repeat day inside the window', () => {
    const wednesday = new Date(2026, 9, 21, 9, 0)
    expect(canCheckIn(task(), new Set(), wednesday)).toBe(true)

    const tuesday = new Date(2026, 9, 20, 9, 0)
    expect(canCheckIn(task(), new Set(), tuesday)).toBe(false)

    const early = new Date(2026, 9, 21, 7, 59)
    expect(taskStatus(task(), new Set(), early)).toBe('upcoming')

    const late = new Date(2026, 9, 21, 22, 0)
    expect(taskStatus(task(), new Set(), late)).toBe('missed')
  })

  it('prevents a second check-in on the same day', () => {
    const wednesday = new Date(2026, 9, 21, 9, 0)
    expect(canCheckIn(task(), new Set(['2026-10-21']), wednesday)).toBe(false)
    expect(taskStatus(task(), new Set(['2026-10-21']), wednesday)).toBe('done')
  })
})

describe('streak calculation', () => {
  it('counts only repeat days and ignores non-repeat days', () => {
    // Mon 19, Wed 21, Fri 23 completed; Thu/Tue are not repeat days.
    const now = new Date(2026, 9, 23, 8, 0)
    const completed = new Set(['2026-10-23', '2026-10-21', '2026-10-19'])
    expect(calculateStreak(task(), completed, now)).toBe(3)
  })

  it('resets after a missed repeat day', () => {
    // Fri 23 pending and still in range; Wed 21 missed; Mon 19 completed.
    const now = new Date(2026, 9, 23, 9, 0)
    const completed = new Set(['2026-10-19'])
    expect(calculateStreak(task(), completed, now)).toBe(0)

    const afterFriday = new Date(2026, 9, 23, 22, 1)
    expect(calculateStreak(task(), completed, afterFriday)).toBe(0)
  })

  it('keeps a yesterday streak while today is upcoming', () => {
    const now = new Date(2026, 9, 23, 7, 0)
    const completed = new Set(['2026-10-21'])
    expect(calculateStreak(task(), completed, now)).toBe(1)
  })
})
