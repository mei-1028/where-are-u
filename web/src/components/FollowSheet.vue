<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import type {
  AlertRuleInfo,
  AlertRuleType,
  GeofenceRuleConfig,
  GeofenceWindow,
} from '@/api/types'
import { useFollowsStore } from '@/stores/follows'
import { usePlacesStore } from '@/stores/places'
import { useUiStore } from '@/stores/ui'
import { messageForError } from '@/lib/errors'
import Icon from './Icon.vue'
import ModalSheet from './ModalSheet.vue'

const props = defineProps<{
  open: boolean
  member: { userId: string; nickname: string }
}>()
const emit = defineEmits<{ close: [] }>()

const follows = useFollowsStore()
const places = usePlacesStore()
const ui = useUiStore()

const busy = ref(false)
const confirmingUnfollow = ref(false)
const confirmingGeofence = ref<string | null>(null)
const draftTypes = ref<AlertRuleType[]>(['MOVEMENT', 'STALE'])

const follow = computed(() => follows.byTarget(props.member.userId))
const rules = computed(() => follow.value?.rules ?? [])

const RULE_TYPES: Array<{
  type: AlertRuleType
  title: string
  desc: string
}> = [
  {
    type: 'MOVEMENT',
    title: '位置变动',
    desc: '移动超过设定距离时提醒；冷却时间内不重复',
  },
  {
    type: 'STALE',
    title: '长时间未更新',
    desc: '超过设定时间没有收到新位置时提醒',
  },
  {
    type: 'LOW_BATTERY',
    title: '低电量',
    desc: '电量低于设定值时提醒（夜间只进消息中心）',
  },
]

const THRESHOLDS = [200, 500, 1000, 2000]
const COOLDOWNS = [15, 30, 60]
const STALE_HOURS = [6, 12, 24, 48]
const BATTERY_PERCENTS = [10, 20, 30]

const MOVEMENT_DEFAULTS = { thresholdMeters: 500, cooldownMinutes: 30 }

const DAY_LABELS = ['一', '二', '三', '四', '五', '六', '日']
const GEOFENCE_EVENTS: Array<{ key: GeofenceRuleConfig['event']; label: string }> = [
  { key: 'ENTER', label: '到达' },
  { key: 'LEAVE', label: '离开' },
  { key: 'BOTH', label: '到达和离开' },
]

const geofenceRules = computed(() =>
  rules.value.filter((rule) => rule.type === 'GEOFENCE'),
)
const usedPlaceIds = computed(
  () => new Set(geofenceRules.value.map((rule) => geofenceConfig(rule).placeId)),
)
const canAddGeofence = computed(
  () =>
    rules.value.length < 10 &&
    places.items.some((place) => !usedPlaceIds.value.has(place.id)),
)

watch(
  () => props.open,
  (open) => {
    if (!open) {
      confirmingUnfollow.value = false
      confirmingGeofence.value = null
      return
    }
    draftTypes.value = ['MOVEMENT', 'STALE']
    void follows.ensureLoaded()
    void places.ensureLoaded()
  },
)

function ruleOf(type: AlertRuleType): AlertRuleInfo | null {
  return rules.value.find((rule) => rule.type === type) ?? null
}

function isDraftType(type: AlertRuleType): boolean {
  return draftTypes.value.includes(type)
}

function toggleDraft(type: AlertRuleType): void {
  draftTypes.value = isDraftType(type)
    ? draftTypes.value.filter((item) => item !== type)
    : [...draftTypes.value, type]
}

async function run(task: () => Promise<unknown>, fallback: string): Promise<void> {
  if (busy.value) return
  busy.value = true
  try {
    await task()
  } catch (err) {
    ui.toast(messageForError(err, fallback), 'error')
  } finally {
    busy.value = false
  }
}

function startFollowing(): void {
  if (draftTypes.value.length === 0) {
    ui.toast('至少选择一种提醒', 'error')
    return
  }
  void run(
    () =>
      follows.follow(
        props.member.userId,
        draftTypes.value.map((type) => ({ type })),
      ),
    '关注失败，请稍后重试',
  )
}

function toggleRule(type: AlertRuleType): void {
  const existing = ruleOf(type)
  if (!existing) {
    if (!follow.value) return
    void run(
      () => follows.addRule(follow.value!.id, type),
      '开启提醒失败',
    )
    return
  }
  void run(
    () => follows.updateRule(follow.value!.id, existing.id, { enabled: !existing.enabled }),
    '更新提醒失败',
  )
}

function setMovementThreshold(rule: AlertRuleInfo, value: number): void {
  if (!follow.value) return
  void run(
    () =>
      follows.updateRule(follow.value!.id, rule.id, {
        config: { thresholdMeters: value },
      }),
    '更新提醒失败',
  )
}

function setMovementCooldown(rule: AlertRuleInfo, value: number): void {
  if (!follow.value) return
  void run(
    () =>
      follows.updateRule(follow.value!.id, rule.id, {
        config: { cooldownMinutes: value },
      }),
    '更新提醒失败',
  )
}

function setStaleHours(rule: AlertRuleInfo, value: number): void {
  if (!follow.value) return
  void run(
    () => follows.updateRule(follow.value!.id, rule.id, { config: { hours: value } }),
    '更新提醒失败',
  )
}

function setBatteryPercent(rule: AlertRuleInfo, value: number): void {
  if (!follow.value) return
  void run(
    () => follows.updateRule(follow.value!.id, rule.id, { config: { percent: value } }),
    '更新提醒失败',
  )
}

/* ---- Geofence rules ----------------------------------------------------- */

function geofenceConfig(rule: AlertRuleInfo): GeofenceRuleConfig {
  const config = rule.config as Partial<GeofenceRuleConfig>
  return {
    placeId: typeof config.placeId === 'string' ? config.placeId : '',
    event:
      config.event === 'ENTER' || config.event === 'LEAVE'
        ? config.event
        : 'BOTH',
    window: config.window ?? null,
  }
}

function placeNameOf(rule: AlertRuleInfo): string {
  return (
    rule.place?.name ??
    places.byId(geofenceConfig(rule).placeId)?.name ??
    '地点已删除'
  )
}

function windowLabel(window: GeofenceWindow): string {
  const sorted = [...window.days].sort((a, b) => a - b)
  let days: string
  if (sorted.length === 0 || sorted.length === 7) days = '每天'
  else if (sorted.join(',') === '1,2,3,4,5') days = '工作日'
  else if (sorted.join(',') === '6,7') days = '周末'
  else days = sorted.map((day) => `周${DAY_LABELS[day - 1]}`).join('、')
  const overnight = window.start > window.end ? '（跨天）' : ''
  return `${days} ${window.start}–${window.end}${overnight}`
}

function addGeofence(): void {
  const current = follow.value
  if (!current) return
  const place = places.items.find((item) => !usedPlaceIds.value.has(item.id))
  if (!place) {
    ui.toast('先在地图上长按添加一个地点', 'error')
    return
  }
  void run(
    () =>
      follows.addRule(current.id, 'GEOFENCE', {
        placeId: place.id,
        event: 'BOTH',
        window: null,
      }),
    '添加围栏提醒失败',
  )
}

function updateGeofence(
  rule: AlertRuleInfo,
  patch: Partial<GeofenceRuleConfig>,
): void {
  const current = follow.value
  if (!current) return
  const config: GeofenceRuleConfig = { ...geofenceConfig(rule), ...patch }
  void run(
    () => follows.updateRule(current.id, rule.id, { config: { ...config } }),
    '更新围栏提醒失败',
  )
}

function setGeofenceEvent(
  rule: AlertRuleInfo,
  event: GeofenceRuleConfig['event'],
): void {
  updateGeofence(rule, { event })
}

function enableWindow(rule: AlertRuleInfo): void {
  if (geofenceConfig(rule).window) return
  updateGeofence(rule, {
    window: { days: [1, 2, 3, 4, 5], start: '07:00', end: '09:00' },
  })
}

function setGeofenceAllDay(rule: AlertRuleInfo): void {
  updateGeofence(rule, { window: null })
}

function toggleDay(rule: AlertRuleInfo, day: number): void {
  const window = geofenceConfig(rule).window
  if (!window) return
  const days = window.days.includes(day)
    ? window.days.filter((item) => item !== day)
    : [...window.days, day].sort((a, b) => a - b)
  updateGeofence(rule, { window: { ...window, days } })
}

function setWindowTime(
  rule: AlertRuleInfo,
  key: 'start' | 'end',
  value: string,
): void {
  const window = geofenceConfig(rule).window
  if (!window || !value) return
  updateGeofence(rule, { window: { ...window, [key]: value } })
}

function removeGeofence(rule: AlertRuleInfo): void {
  const current = follow.value
  if (!current) return
  void run(async () => {
    await follows.removeRule(current.id, rule.id)
    confirmingGeofence.value = null
  }, '删除围栏提醒失败')
}

function valueOf(rule: AlertRuleInfo, key: string, fallback = 0): number {
  const config = rule.config as unknown as Record<string, unknown>
  const value = config[key]
  return typeof value === 'number' ? value : fallback
}

function movementValue(rule: AlertRuleInfo, key: 'thresholdMeters' | 'cooldownMinutes'): number {
  return valueOf(rule, key, MOVEMENT_DEFAULTS[key])
}

function unfollow(): void {
  if (!follow.value) return
  void run(async () => {
    await follows.unfollow(follow.value!.id)
    confirmingUnfollow.value = false
    ui.toast(`已取消关注${props.member.nickname}`, 'success')
  }, '取消关注失败')
}
</script>

<template>
  <ModalSheet
    :open="open"
    :title="`关注 ${member.nickname}`"
    description="关注后，对方的位置变化会出现在消息中心，并可弹出系统通知。"
    @close="emit('close')"
  >
    <div v-if="!follow" class="follow-intro">
      <div class="follow-intro__icon">
        <Icon name="bell" :size="20" />
      </div>
      <p>
        选择你关心的提醒。关注不需要对方同意，对方可以在「谁在关注我」里看到你。
      </p>
    </div>

    <div v-if="!follow" class="rule-list">
      <button
        v-for="item in RULE_TYPES"
        :key="item.type"
        type="button"
        class="rule-card"
        :class="{ 'is-on': isDraftType(item.type) }"
        @click="toggleDraft(item.type)"
      >
        <span class="rule-card__copy">
          <span class="rule-card__title">{{ item.title }}</span>
          <span class="rule-card__desc">{{ item.desc }}</span>
        </span>
        <span
          class="switch"
          :class="{ 'is-on': isDraftType(item.type) }"
          role="switch"
          :aria-checked="isDraftType(item.type)"
          :aria-label="`${item.title}提醒`"
        />
      </button>
    </div>

    <div v-else class="rule-list">
      <div v-for="item in RULE_TYPES" :key="item.type" class="rule-block">
        <div class="rule-card rule-card--static">
          <span class="rule-card__copy">
            <span class="rule-card__title">{{ item.title }}</span>
            <span class="rule-card__desc">{{ item.desc }}</span>
          </span>
          <button
            type="button"
            class="switch"
            :class="{ 'is-on': ruleOf(item.type)?.enabled }"
            role="switch"
            :aria-checked="Boolean(ruleOf(item.type)?.enabled)"
            :aria-label="`${item.title}提醒`"
            :disabled="busy"
            @click="toggleRule(item.type)"
          />
        </div>

        <div v-if="ruleOf(item.type)?.enabled" class="rule-config">
          <template v-if="item.type === 'MOVEMENT'">
            <div class="config-row">
              <span class="config-label">移动超过</span>
              <div class="chips">
                <button
                  v-for="value in THRESHOLDS"
                  :key="value"
                  type="button"
                  class="chip"
                  :class="{ 'chip--blue': movementValue(ruleOf(item.type)!, 'thresholdMeters') === value }"
                  :disabled="busy"
                  @click="setMovementThreshold(ruleOf(item.type)!, value)"
                >
                  {{ value >= 1000 ? `${value / 1000} 公里` : `${value} 米` }}
                </button>
              </div>
            </div>
            <div class="config-row">
              <span class="config-label">提醒冷却</span>
              <div class="chips">
                <button
                  v-for="value in COOLDOWNS"
                  :key="value"
                  type="button"
                  class="chip"
                  :class="{ 'chip--blue': movementValue(ruleOf(item.type)!, 'cooldownMinutes') === value }"
                  :disabled="busy"
                  @click="setMovementCooldown(ruleOf(item.type)!, value)"
                >
                  {{ value }} 分钟
                </button>
              </div>
            </div>
          </template>

          <div v-else-if="item.type === 'STALE'" class="config-row">
            <span class="config-label">超过</span>
            <div class="chips">
              <button
                v-for="value in STALE_HOURS"
                :key="value"
                type="button"
                class="chip"
                :class="{ 'chip--blue': valueOf(ruleOf(item.type)!, 'hours') === value }"
                :disabled="busy"
                @click="setStaleHours(ruleOf(item.type)!, value)"
              >
                {{ value }} 小时
              </button>
            </div>
          </div>

          <div v-else class="config-row">
            <span class="config-label">低于</span>
            <div class="chips">
              <button
                v-for="value in BATTERY_PERCENTS"
                :key="value"
                type="button"
                class="chip"
                :class="{ 'chip--blue': valueOf(ruleOf(item.type)!, 'percent') === value }"
                :disabled="busy"
                @click="setBatteryPercent(ruleOf(item.type)!, value)"
              >
                {{ value }}%
              </button>
            </div>
          </div>
        </div>
      </div>

      <section class="geofence-block">
        <div class="geofence-head">
          <span class="rule-card__copy">
            <span class="rule-card__title">到达 / 离开某地</span>
            <span class="rule-card__desc">家人进出家庭地点时提醒，可只在指定时段生效</span>
          </span>
          <button
            type="button"
            class="btn btn--ghost btn--sm"
            :disabled="busy || !canAddGeofence"
            @click="addGeofence"
          >
            <Icon name="plus" :size="14" />
            添加
          </button>
        </div>

        <p v-if="places.items.length === 0" class="geofence-empty">
          还没有家庭地点。先在地图上长按（桌面端右键）添加一个地点。
        </p>
        <p v-else-if="geofenceRules.length === 0 && !canAddGeofence" class="geofence-empty">
          每个地点只能设置一条围栏提醒。
        </p>

        <div v-for="rule in geofenceRules" :key="rule.id" class="geofence-card">
          <div class="geofence-card__head">
            <span class="geofence-card__place">
              <Icon name="pin" :size="14" />
              {{ placeNameOf(rule) }}
            </span>
            <button
              v-if="confirmingGeofence === rule.id"
              type="button"
              class="btn btn--danger btn--sm"
              :disabled="busy"
              @click="removeGeofence(rule)"
            >
              确认删除
            </button>
            <button
              v-else
              type="button"
              class="geofence-card__remove"
              aria-label="删除这条围栏提醒"
              @click="confirmingGeofence = rule.id"
            >
              <Icon name="trash" :size="14" />
            </button>
          </div>

          <div class="config-row">
            <span class="config-label">地点</span>
            <div class="chips">
              <button
                v-for="place in places.items"
                :key="place.id"
                type="button"
                class="chip"
                :class="{ 'chip--blue': geofenceConfig(rule).placeId === place.id }"
                :disabled="
                  busy ||
                  (usedPlaceIds.has(place.id) &&
                    geofenceConfig(rule).placeId !== place.id)
                "
                @click="updateGeofence(rule, { placeId: place.id })"
              >
                {{ place.name }}
              </button>
            </div>
          </div>

          <div class="config-row">
            <span class="config-label">提醒</span>
            <div class="chips">
              <button
                v-for="event in GEOFENCE_EVENTS"
                :key="event.key"
                type="button"
                class="chip"
                :class="{ 'chip--blue': geofenceConfig(rule).event === event.key }"
                :disabled="busy"
                @click="setGeofenceEvent(rule, event.key)"
              >
                {{ event.label }}
              </button>
            </div>
          </div>

          <div class="config-row">
            <span class="config-label">时段</span>
            <div class="chips">
              <button
                type="button"
                class="chip"
                :class="{ 'chip--blue': !geofenceConfig(rule).window }"
                :disabled="busy"
                @click="setGeofenceAllDay(rule)"
              >
                全天
              </button>
              <button
                type="button"
                class="chip"
                :class="{ 'chip--blue': Boolean(geofenceConfig(rule).window) }"
                :disabled="busy"
                @click="enableWindow(rule)"
              >
                指定时段
              </button>
            </div>
          </div>

          <div v-if="geofenceConfig(rule).window" class="geofence-window">
            <div class="chips">
              <button
                v-for="(label, index) in DAY_LABELS"
                :key="label"
                type="button"
                class="chip day-chip"
                :class="{
                  'chip--blue': geofenceConfig(rule).window!.days.includes(index + 1),
                }"
                :disabled="busy"
                @click="toggleDay(rule, index + 1)"
              >
                {{ label }}
              </button>
            </div>
            <div class="geofence-times">
              <input
                type="time"
                class="time-input"
                :value="geofenceConfig(rule).window!.start"
                :disabled="busy"
                @change="
                  setWindowTime(
                    rule,
                    'start',
                    ($event.target as HTMLInputElement).value,
                  )
                "
              />
              <span>至</span>
              <input
                type="time"
                class="time-input"
                :value="geofenceConfig(rule).window!.end"
                :disabled="busy"
                @change="
                  setWindowTime(
                    rule,
                    'end',
                    ($event.target as HTMLInputElement).value,
                  )
                "
              />
              <span class="geofence-times__hint">北京时间</span>
            </div>
            <p class="geofence-window__hint">
              {{ windowLabel(geofenceConfig(rule).window!) }}
            </p>
          </div>
        </div>
      </section>
    </div>

    <template #footer>
      <div class="follow-footer">
        <div v-if="follow" class="unfollow">
          <template v-if="confirmingUnfollow">
            <span class="unfollow__hint">取消后不再收到 {{ member.nickname }} 的提醒</span>
            <button type="button" class="btn btn--danger btn--sm" :disabled="busy" @click="unfollow">
              确认取消
            </button>
            <button
              type="button"
              class="btn btn--ghost btn--sm"
              @click="confirmingUnfollow = false"
            >
              再想想
            </button>
          </template>
          <button
            v-else
            type="button"
            class="member-detail__link"
            @click="confirmingUnfollow = true"
          >
            取消关注
          </button>
        </div>
        <button
          v-if="!follow"
          type="button"
          class="btn btn--primary btn--block"
          :disabled="busy"
          @click="startFollowing"
        >
          <Icon name="bell" :size="16" />
          开始关注
        </button>
        <button v-else type="button" class="btn btn--block" @click="emit('close')">
          完成
        </button>
      </div>
    </template>
  </ModalSheet>
</template>

<style scoped>
.follow-intro {
  display: flex;
  gap: 12px;
  padding: 14px 16px;
  margin-bottom: 14px;
  border-radius: var(--r-card);
  background: var(--blue-soft);
  color: var(--slate);
  font-size: 13px;
  line-height: 1.6;
}

.follow-intro__icon {
  flex: 0 0 auto;
  width: 34px;
  height: 34px;
  display: inline-flex;
  align-items: center;
  justify-content: center;
  border-radius: 10px;
  background: var(--white);
  color: var(--blue);
}

.rule-list {
  display: flex;
  flex-direction: column;
  gap: 10px;
}

.rule-block {
  display: flex;
  flex-direction: column;
  gap: 8px;
}

.rule-card {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 16px;
  width: 100%;
  padding: 14px 16px;
  border-radius: var(--r-card);
  background: var(--surface-soft);
  text-align: left;
  transition: background-color 0.16s;
}

.rule-card:hover {
  background: var(--surface-sunken);
}

.rule-card--static {
  cursor: default;
}

.rule-card.is-on {
  background: var(--blue-soft);
}

.rule-card__copy {
  display: flex;
  flex-direction: column;
  gap: 3px;
  min-width: 0;
}

.rule-card__title {
  font-size: 14px;
  font-weight: 600;
  color: var(--ink);
}

.rule-card__desc {
  font-size: 12px;
  color: var(--ink-muted);
  line-height: 1.5;
}

.rule-config {
  display: flex;
  flex-direction: column;
  gap: 8px;
  padding: 0 4px 4px;
}

.config-row {
  display: flex;
  align-items: center;
  gap: 10px;
}

.config-label {
  flex: 0 0 auto;
  font-size: 12px;
  color: var(--slate);
}

.chips {
  display: flex;
  flex-wrap: wrap;
  gap: 6px;
}

.chips .chip {
  border: 0;
  cursor: pointer;
}

.chips .chip:disabled {
  opacity: 0.55;
  cursor: default;
}

.follow-footer {
  display: flex;
  flex-direction: column;
  gap: 12px;
  width: 100%;
}

.unfollow {
  display: flex;
  align-items: center;
  gap: 10px;
  flex-wrap: wrap;
}

.unfollow__hint {
  flex: 1 1 100%;
  font-size: 12px;
  color: var(--ink-muted);
}

.member-detail__link {
  font-size: 12px;
  color: var(--danger);
}

.member-detail__link:hover {
  text-decoration: underline;
}

/* ---- Geofence section --------------------------------------------------- */

.geofence-block {
  display: flex;
  flex-direction: column;
  gap: 8px;
  padding-top: 10px;
  margin-top: 2px;
  border-top: 1px dashed var(--line);
}

.geofence-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  padding: 4px 2px;
}

.geofence-empty {
  padding: 4px 2px;
  font-size: 12px;
  line-height: 1.6;
  color: var(--ink-muted);
}

.geofence-card {
  display: flex;
  flex-direction: column;
  gap: 8px;
  padding: 12px 14px;
  border-radius: var(--r-card);
  background: var(--surface-soft);
}

.geofence-card__head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
}

.geofence-card__place {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  font-size: 13px;
  font-weight: 600;
  color: var(--ink);
}

.geofence-card__remove {
  display: inline-flex;
  padding: 4px;
  border: 0;
  background: none;
  color: var(--ink-muted);
  cursor: pointer;
}

.geofence-card__remove:hover {
  color: var(--danger);
}

.geofence-window {
  display: flex;
  flex-direction: column;
  gap: 8px;
  padding-top: 2px;
}

.geofence-times {
  display: flex;
  align-items: center;
  gap: 8px;
  font-size: 12px;
  color: var(--slate);
}

.time-input {
  padding: 6px 8px;
  border: 1px solid var(--input-line);
  border-radius: 8px;
  background: var(--white);
  color: var(--ink);
  font-size: 13px;
}

.day-chip {
  min-width: 30px;
  justify-content: center;
}

.geofence-window__hint,
.geofence-times__hint {
  font-size: 11px;
  color: var(--ink-muted);
}
</style>
