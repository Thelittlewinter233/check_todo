import { reactive, watch } from 'vue'
import { dateKey } from '../domain/time.js'

const STORAGE_KEY = 'check-todo.v1'

function createId() {
  return typeof crypto.randomUUID === 'function'
    ? crypto.randomUUID()
    : `id-${Date.now()}-${Math.random().toString(16).slice(2)}`
}

function load() {
  try {
    const raw = localStorage.getItem(STORAGE_KEY)
    if (!raw) return null
    const parsed = JSON.parse(raw)
    if (!Array.isArray(parsed.tasks) || typeof parsed.records !== 'object') return null
    return parsed
  } catch {
    return null
  }
}

const stored = load()

export const store = reactive({
  tasks: stored?.tasks ?? [],
  records: stored?.records ?? {},
})

function persist() {
  localStorage.setItem(STORAGE_KEY, JSON.stringify({ tasks: store.tasks, records: store.records }))
}

watch(() => [store.tasks, store.records], persist, { deep: true })

export function completedSet(taskId) {
  return new Set(store.records[taskId] ?? [])
}

export function saveTask(draft, taskId) {
  const task = {
    id: taskId ?? createId(),
    name: draft.name.trim(),
    weekdays: [...draft.weekdays],
    startTime: draft.startTime,
    endTime: draft.endTime,
    createdAt: Date.now(),
  }

  const index = store.tasks.findIndex(item => item.id === taskId)
  if (index === -1) {
    store.tasks.push(task)
    store.records[task.id] = []
  } else {
    store.tasks.splice(index, 1, { ...store.tasks[index], ...task })
  }

  return task
}

export function deleteTask(taskId) {
  store.tasks = store.tasks.filter(task => task.id !== taskId)
  const records = { ...store.records }
  delete records[taskId]
  store.records = records
}

export function checkIn(taskId, now) {
  const key = dateKey(now)
  const existing = store.records[taskId] ?? []
  if (existing.includes(key)) return false
  store.records[taskId] = [...existing, key]
  return true
}
