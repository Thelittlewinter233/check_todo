<script setup>
import { computed, onBeforeUnmount, onMounted, ref } from 'vue'
import TaskEditor from './components/TaskEditor.vue'
import { formatRange, weekIndex } from './domain/time.js'
import { WEEKDAY_LABELS, calculateStreak, canCheckIn, taskStatus } from './domain/tasks.js'
import { checkIn, completedSet, deleteTask, saveTask, store } from './store/useTaskStore.js'

const now = ref(new Date())
const view = ref('today')
const editing = ref(null)
const showEditor = ref(false)
let timer

onMounted(() => {
  timer = window.setInterval(() => {
    now.value = new Date()
  }, 30_000)
})

onBeforeUnmount(() => window.clearInterval(timer))

const todayTasks = computed(() => store.tasks.filter(task => task.weekdays[weekIndex(now.value)]))

const statusLabels = {
  upcoming: '未开始',
  active: '可打卡',
  done: '已完成',
  missed: '已错过',
}

function statusOf(task) {
  return taskStatus(task, completedSet(task.id), now.value)
}

function streakOf(task) {
  return calculateStreak(task, completedSet(task.id), now.value)
}

function todayTitle() {
  return now.value.toLocaleDateString('zh-CN', { month: 'long', day: 'numeric', weekday: 'long' })
}

function weekdayText(task) {
  return task.weekdays.map((selected, index) => (selected ? WEEKDAY_LABELS[index] : null)).filter(Boolean).join(' / ')
}

function openNewTask() {
  editing.value = null
  showEditor.value = true
}

function editTask(task) {
  editing.value = task
  showEditor.value = true
}

function submitTask(draft) {
  saveTask(draft, editing.value?.id)
  showEditor.value = false
  editing.value = null
}

function completeTask(task) {
  if (!canCheckIn(task, completedSet(task.id), now.value)) return
  checkIn(task.id, now.value)
  now.value = new Date()
}

function removeTask(task) {
  if (window.confirm(`删除「${task.name}」及其全部打卡记录？`)) deleteTask(task.id)
}
</script>

<template>
  <div class="app-shell">
    <header class="app-header">
      <div>
        <h1>每日打卡</h1>
        <p>{{ todayTitle() }}</p>
      </div>
      <button v-if="!showEditor" class="primary action" type="button" @click="openNewTask">新建任务</button>
    </header>

    <TaskEditor
      v-if="showEditor"
      :task="editing"
      @save="submitTask"
      @cancel="showEditor = false; editing = null"
    />

    <template v-else>
      <nav class="segmented" aria-label="视图切换">
        <button :class="{ selected: view === 'today' }" type="button" @click="view = 'today'">今日</button>
        <button :class="{ selected: view === 'tasks' }" type="button" @click="view = 'tasks'">全部任务</button>
      </nav>

      <main>
        <section v-if="view === 'today'" class="task-list" aria-label="今日任务">
          <p v-if="todayTasks.length === 0" class="empty">今天没有需要打卡的任务</p>
          <article v-for="task in todayTasks" :key="task.id" class="task-card">
            <div class="task-main">
              <div class="task-title-row">
                <h2>{{ task.name }}</h2>
                <span class="status" :class="statusOf(task)">{{ statusLabels[statusOf(task)] }}</span>
              </div>
              <p>{{ formatRange(task) }}</p>
              <strong>连续 {{ streakOf(task) }} 天</strong>
            </div>
            <button
              v-if="statusOf(task) === 'active'"
              class="check-button"
              type="button"
              @click="completeTask(task)"
            >
              打卡
            </button>
          </article>
        </section>

        <section v-else class="task-list" aria-label="全部任务">
          <p v-if="store.tasks.length === 0" class="empty">还没有任务，先创建一个吧</p>
          <article v-for="task in store.tasks" :key="task.id" class="task-card">
            <div class="task-main">
              <div class="task-title-row">
                <h2>{{ task.name }}</h2>
                <strong>连续 {{ streakOf(task) }} 天</strong>
              </div>
              <p>{{ weekdayText(task) }}</p>
              <p>{{ formatRange(task) }}</p>
            </div>
            <div class="task-actions">
              <button type="button" @click="editTask(task)">编辑</button>
              <button class="danger" type="button" @click="removeTask(task)">删除</button>
            </div>
          </article>
        </section>
      </main>
    </template>
  </div>
</template>
