<script setup lang="ts">
import { computed } from 'vue'
import type { MemberView } from '@/api/types'
import { usePlaceLabel } from '@/composables/usePlaceLabel'
import { formatBattery, relativeTime } from '@/lib/format'
import Avatar from './Avatar.vue'

const props = withDefaults(
  defineProps<{
    member: MemberView
    selected?: boolean
    locating?: boolean
    currentUserId?: string | null
    now?: number
  }>(),
  {
    selected: false,
    locating: false,
    currentUserId: null,
    now: Date.now(),
  },
)

const emit = defineEmits<{ select: [] }>()

const isSelf = computed(() => props.member.userId === props.currentUserId)
const offline = computed(() => !props.member.online)
const paused = computed(() => props.member.sharingPaused)
const location = computed(() => props.member.lastLocation)
const { label: address } = usePlaceLabel(location)
const timeText = computed(() => {
  const iso = location.value?.recordedAt ?? props.member.lastSeenAt
  if (!iso) return '暂无更新'
  return relativeTime(iso, props.now)
})
const stateText = computed(() => {
  if (props.locating) return '定位中'
  if (paused.value) return '已暂停'
  if (offline.value) return '离线'
  return '在线'
})
const statusClass = computed(() => {
  if (props.locating) return 'status-dot--locating'
  if (paused.value) return 'status-dot--paused'
  if (offline.value) return 'status-dot--offline'
  return 'status-dot--online'
})
const battery = computed(() => location.value?.battery ?? null)
const batteryColor = computed(() => {
  if (battery.value === null) return 'var(--success)'
  if (battery.value <= 20) return '#d97878'
  if (battery.value <= 40) return '#e5a454'
  return 'var(--success)'
})
const batteryWidth = computed(() => {
  if (battery.value === null) return '0%'
  return `${Math.min(100, Math.max(0, battery.value))}%`
})
</script>

<template>
  <button
    type="button"
    class="member-card"
    :class="{ 'is-selected': selected, 'is-offline': offline, 'is-paused': paused }"
    :aria-pressed="selected"
    :aria-label="`${member.nickname}${isSelf ? '，我' : ''}，${stateText}，${address}`"
    @click="emit('select')"
  >
    <span class="member-card__avatar">
      <Avatar :name="member.nickname" :id="member.userId" :size="44" :muted="offline" />
      <span class="status-dot member-card__status" :class="statusClass" />
    </span>

    <span class="member-card__body">
      <span class="member-card__top">
        <span class="member-card__name">
          {{ member.nickname }}
          <span v-if="isSelf" class="member-card__self">我</span>
          <span v-else-if="paused" class="member-card__paused">已暂停</span>
        </span>
        <span
          v-if="battery !== null"
          class="member-card__battery"
          :title="`电量 ${formatBattery(battery)}`"
        >
          <span class="member-card__battery-track">
            <span
              class="member-card__battery-fill"
              :style="{ width: batteryWidth, background: batteryColor }"
            />
          </span>
          <span class="member-card__battery-label">{{ formatBattery(battery) }}</span>
        </span>
      </span>

      <span class="member-card__address">{{ address }}</span>

      <span class="member-card__meta">
        <span class="member-card__time">{{ timeText }}</span>
      </span>
    </span>
  </button>
</template>

<style scoped>
.member-card {
  display: flex;
  align-items: center;
  gap: 10px;
  width: 100%;
  min-height: 82px;
  padding: 12px 14px;
  border-radius: 14px;
  border: 1px solid var(--ring);
  background: var(--white);
  text-align: left;
  transition:
    background-color 0.16s var(--ease-out),
    border-color 0.16s var(--ease-out),
    box-shadow 0.16s var(--ease-out);
}

.member-card:hover {
  background: var(--surface-soft);
}

.member-card.is-selected {
  background: var(--blue-soft);
  border-color: var(--blue);
  box-shadow: 0 0 0 1px rgba(91, 118, 254, 0.14);
}

.member-card.is-selected:hover {
  background: var(--blue-soft);
}

.member-card__avatar {
  position: relative;
  flex: 0 0 auto;
}

.member-card__status {
  position: absolute;
  right: -1px;
  bottom: 1px;
  width: 9px;
  height: 9px;
  border: 2px solid #fff;
  box-shadow: none;
}

.member-card__body {
  display: flex;
  flex-direction: column;
  gap: 2px;
  min-width: 0;
  flex: 1;
}

.member-card__top {
  display: flex;
  align-items: center;
  gap: 8px;
  min-width: 0;
}

.member-card__name {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  font-size: 13.5px;
  font-weight: 600;
  line-height: 1.35;
  letter-spacing: -0.01em;
  color: var(--ink);
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}

.member-card.is-offline .member-card__name {
  color: var(--slate);
}

.member-card__self {
  display: inline-flex;
  align-items: center;
  height: 16px;
  padding: 0 6px;
  border-radius: 8px;
  background: var(--white);
  color: var(--blue);
  font-size: 10.5px;
  font-weight: 600;
  letter-spacing: 0.02em;
}

.member-card__paused {
  color: var(--orange-ink);
  font-size: 10.5px;
  font-weight: 600;
}

.member-card__battery {
  display: inline-flex;
  align-items: center;
  gap: 5px;
  margin-left: auto;
  flex: 0 0 auto;
}

.member-card__battery-track {
  width: 22px;
  height: 4px;
  border-radius: 2px;
  background: var(--input-line);
  overflow: hidden;
}

.member-card__battery-fill {
  display: block;
  height: 100%;
  border-radius: inherit;
  transition: width 0.3s var(--ease-out);
}

.member-card__battery-label {
  font-size: 10px;
  color: var(--ink-muted);
  font-variant-numeric: tabular-nums;
}

.member-card__address {
  font-size: 10.5px;
  line-height: 1.45;
  color: var(--slate);
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}

.member-card__meta {
  display: flex;
  align-items: center;
  gap: 6px;
  margin-top: 1px;
  font-size: 10.5px;
  line-height: 1.4;
  color: var(--ink-muted);
}

.member-card__top {
  align-items: center;
}

.member-card__sep {
  opacity: 0.6;
}

.member-card__time {
  white-space: nowrap;
}

.member-card.is-paused .member-card__address {
  color: var(--ink-muted);
}
</style>
