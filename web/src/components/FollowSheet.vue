<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import type { AlertRuleInfo, AlertRuleType } from '@/api/types'
import { useFollowsStore } from '@/stores/follows'
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
const ui = useUiStore()

const busy = ref(false)
const confirmingUnfollow = ref(false)
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

watch(
  () => props.open,
  (open) => {
    if (!open) {
      confirmingUnfollow.value = false
      return
    }
    draftTypes.value = ['MOVEMENT', 'STALE']
    void follows.ensureLoaded()
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
</style>
