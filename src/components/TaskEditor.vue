<script setup>
import { reactive, watch } from 'vue'
import { WEEKDAY_LABELS, validateTask } from '../domain/tasks.js'

const props = defineProps({
  task: { type: Object, default: null },
})

const emit = defineEmits(['save', 'cancel'])

const draft = reactive({
  name: '',
  weekdays: [true, false, true, false, true, false, false],
  startTime: '08:00',
  endTime: '22:00',
})

const errors = reactive({ list: [] })

watch(
  () => props.task,
  task => {
    draft.name = task?.name ?? ''
    draft.weekdays = task ? [...task.weekdays] : [true, false, true, false, true, false, false]
    draft.startTime = task?.startTime ?? '08:00'
    draft.endTime = task?.endTime ?? '22:00'
    errors.list = []
  },
  { immediate: true },
)

function submit() {
  errors.list = validateTask(draft)
  if (errors.list.length) return
  emit('save', { ...draft, name: draft.name.trim(), weekdays: [...draft.weekdays] })
}
</script>

<template>
  <form class="editor" @submit.prevent="submit">
    <label class="field">
      <span>任务名称</span>
      <input v-model="draft.name" type="text" maxlength="40" placeholder="例如：晨间阅读" autocomplete="off" />
    </label>

    <fieldset class="field">
      <legend>重复日期</legend>
      <div class="weekday-grid">
        <label v-for="(label, index) in WEEKDAY_LABELS" :key="label" class="weekday">
          <input v-model="draft.weekdays[index]" type="checkbox" />
          <span>{{ label }}</span>
        </label>
      </div>
    </fieldset>

    <div class="time-row">
      <label class="field">
        <span>开始</span>
        <input v-model="draft.startTime" type="time" step="60" />
      </label>
      <span class="range-separator">至</span>
      <label class="field">
        <span>结束</span>
        <input v-model="draft.endTime" type="time" step="60" />
      </label>
    </div>

    <ul v-if="errors.list.length" class="errors" aria-live="polite">
      <li v-for="error in errors.list" :key="error">{{ error }}</li>
    </ul>

    <div class="form-actions">
      <button class="secondary" type="button" @click="emit('cancel')">取消</button>
      <button type="submit">保存</button>
    </div>
  </form>
</template>
