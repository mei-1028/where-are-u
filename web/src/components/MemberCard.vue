<script setup lang="ts">
import { computed } from 'vue'
import type { MemberView } from '@/api/types'
import { describeLocation } from '@/lib/geo'
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
const address = computed(() => {
  if (!location.value) return '暂无位置数据'
  return describeLocation(location.value.lat, location.value.lng)
})
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
        </span>
        <span v-if="battery !== null" class="member-card__battery" :title="`电量 ${formatBattery(battery)}`">
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
        <span class="member-card__state">
          <span class="status-dot" :class="statusClass" />
          {{ stateText }}
        </span>
        <span class="member-card__sep">·</span>
        <span>{{ timeText }}</span>
      </span>
    </span>
  </button>
</template>

<style scoped>
.member-card {
  display: flex;
  align-items: center;
  gap: 12px;
  width: 100%;
  padding: 12px;
  border-radius: var(--r-card);
  border: 1px solid transparent;
  background: transparent;
  text-align: left;
  transition:
    background-color 0.16s var(--ease-out),
    box-shadow 0.16s var(--ease-out),
    border-color 0.16s var(--ease-out);
}

.member-card:hover {
  background: var(--surface-soft);
}

.member-card.is-selected {
  background: #f7f9ff;
  border-color: rgba(91, 118, 254, 0.5);
  box-shadow: 0 0 0 1px rgba(91, 118, 254, 0.18);
}

.member-card__avatar {
  position: relative;
  flex: 0 0 auto;
}

.member-card__status {
  position: absolute;
  right: 0;
  bottom: 1px;
  width: 11px;
  height: 11px;
  border: 2px solid #fff;
}

.member-card__body {
  display: flex;
  flex-direction: column;
  gap: 3px;
  min-width: 0;
  flex: 1;
}

.member-card__top {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 10px;
  min-width: 0;
}

.member-card__name {
  display: inline-flex;
  align-items: center;
  gap: 5px;
  font-size: 15px;
  font-weight: 600;
  line-height: 1.3;
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
  padding: 1px 5px;
  border-radius: 4px;
  background: var(--blue-soft);
  color: var(--blue-pressed);
  font-size: 9px;
  font-weight: 600;
  letter-spacing: 0.04em;
}

.member-card__battery {
  display: inline-flex;
  align-items: center;
  gap: 5px;
  flex: 0 0 auto;
}

.member-card__battery-track {
  width: 26px;
  height: 3px;
  border-radius: 999px;
  background: #eceef2;
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
  font-size: 12.5px;
  line-height: 1.4;
  color: var(--slate);
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}

.member-card__meta {
  display: flex;
  align-items: center;
  gap: 5px;
  font-size: 11px;
  line-height: 1.4;
  color: var(--ink-muted);
}

.member-card__state {
  display: inline-flex;
  align-items: center;
  gap: 5px;
  white-space: nowrap;
}

.member-card__state .status-dot {
  width: 5px;
  height: 5px;
  box-shadow: none;
}

.member-card__sep {
  opacity: 0.5;
}

.member-card.is-paused .member-card__address {
  color: var(--ink-muted);
}
</style>
