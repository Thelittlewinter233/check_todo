import { addDays, dateKey, minutesOfDay, parseTime, weekIndex } from './time.js'

export const WEEKDAY_LABELS = ['Mon', 'Tue', 'Wed', 'Thu', 'Fri', 'Sat', 'Sun']

export function isScheduled(task, date) {
  return task.weekdays[weekIndex(date)]
}

export function taskStatus(task, completedDates, now) {
  const today = dateKey(now)
  if (!isScheduled(task, now)) return 'off'
  if (completedDates.has(today)) return 'done'

  const minute = minutesOfDay(now)
  if (minute < parseTime(task.startTime)) return 'upcoming'
  if (minute >= parseTime(task.endTime)) return 'missed'
  return 'active'
}

export function canCheckIn(task, completedDates, now) {
  return taskStatus(task, completedDates, now) === 'active'
}

export function calculateStreak(task, completedDates, now) {
  if (task.weekdays.every(day => !day)) return 0

  let streak = 0
  if (isScheduled(task, now)) {
    if (completedDates.has(dateKey(now))) {
      streak = 1
    } else if (minutesOfDay(now) >= parseTime(task.endTime)) {
      return 0
    }
  }

  let date = addDays(now, -1)
  while (true) {
    if (isScheduled(task, date)) {
      if (!completedDates.has(dateKey(date))) break
      streak += 1
    }
    date = addDays(date, -1)
  }

  return streak
}

export function validateTask(task) {
  const errors = []
  if (!task.name?.trim()) errors.push('Task name is required.')
  if (!task.weekdays.some(Boolean)) errors.push('Select at least one day.')
  if (!/^\d{2}:\d{2}$/.test(task.startTime) || !/^\d{2}:\d{2}$/.test(task.endTime)) {
    errors.push('Time values must be valid.')
  } else if (parseTime(task.startTime) >= parseTime(task.endTime)) {
    errors.push('End time must be later than start time.')
  }
  return errors
}
