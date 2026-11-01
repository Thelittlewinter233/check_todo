export function dateKey(date) {
  const year = date.getFullYear()
  const month = `${date.getMonth() + 1}`.padStart(2, '0')
  const day = `${date.getDate()}`.padStart(2, '0')
  return `${year}-${month}-${day}`
}

export function parseDateKey(key) {
  const [year, month, day] = key.split('-').map(Number)
  return new Date(year, month - 1, day)
}

export function minutesOfDay(date) {
  return date.getHours() * 60 + date.getMinutes()
}

export function parseTime(time) {
  const [hour, minute] = time.split(':').map(Number)
  return hour * 60 + minute
}

export function addDays(date, amount) {
  const result = new Date(date)
  result.setDate(result.getDate() + amount)
  return result
}

export function weekIndex(date) {
  return (date.getDay() + 6) % 7
}

export function formatRange(task) {
  return `${task.startTime} - ${task.endTime}`
}
