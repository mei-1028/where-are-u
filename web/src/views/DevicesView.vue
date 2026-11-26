<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import { api } from '@/api'
import type { DeviceInfo } from '@/api/types'
import { useAuthStore } from '@/stores/auth'
import { useFamilyStore } from '@/stores/family'
import { useUiStore } from '@/stores/ui'
import { useNow } from '@/composables/useNow'
import { copyText } from '@/lib/clipboard'
import { messageForError } from '@/lib/errors'
import { relativeTime } from '@/lib/format'
import ConfirmDialog from '@/components/ConfirmDialog.vue'
import Icon from '@/components/Icon.vue'
import ModalSheet from '@/components/ModalSheet.vue'
import StateBlock from '@/components/StateBlock.vue'

const router = useRouter()
const auth = useAuthStore()
const family = useFamilyStore()
const ui = useUiStore()
const now = useNow()

const bindOpen = ref(false)
const bindStage = ref<'form' | 'token'>('form')
const bindBusy = ref(false)
const bindError = ref('')
const bindName = ref('')
const bindModel = ref('')
const bindOs = ref('Android 15')
const bindAppVersion = ref('0.1.0')
const createdToken = ref('')
const createdName = ref('')

const renameTarget = ref<DeviceInfo | null>(null)
const renameName = ref('')
const renameBusy = ref(false)
const renameError = ref('')

const unbindTarget = ref<DeviceInfo | null>(null)
const unbindBusy = ref(false)
const unbindError = ref('')

const devices = computed(() => family.devices)
const loading = computed(
  () => (family.loading && !family.family) || (family.devicesLoading && !devices.value.length),
)
const fatalError = computed(
  () => !family.family && Boolean(family.error) && !family.loading,
)
const onlineCount = computed(() => devices.value.filter((device) => device.online).length)

function memberForDevice(device: DeviceInfo) {
  return (
    family.memberViews.find((member) => member.deviceId === device.id) ??
    family.members.find((member) => member.userId === device.userId) ??
    null
  )
}

function ownerName(device: DeviceInfo): string {
  const member = memberForDevice(device)
  if (member) {
    return member.userId === auth.user?.id ? '你' : member.nickname
  }
  return device.ownerNickname ?? (device.userId === auth.user?.id ? '你' : '—')
}

function ownedByMe(device: DeviceInfo): boolean {
  if (device.userId && device.userId === auth.user?.id) return true
  const member = family.memberViews.find((item) => item.deviceId === device.id)
  return Boolean(member && member.userId === auth.user?.id)
}

function canManage(device: DeviceInfo): boolean {
  return family.isAdmin || ownedByMe(device)
}

function lastSeen(device: DeviceInfo): string {
  const iso = device.lastSeenAt ?? device.lastLocation?.recordedAt
  if (!iso) return '暂无心跳'
  return relativeTime(iso, now.value)
}

function openBind(): void {
  bindStage.value = 'form'
  bindError.value = ''
  bindBusy.value = false
  bindName.value = ''
  bindModel.value = ''
  bindOs.value = 'Android 15'
  bindAppVersion.value = '0.1.0'
  createdToken.value = ''
  createdName.value = ''
  bindOpen.value = true
}

function closeBind(): void {
  if (bindBusy.value) return
  bindOpen.value = false
  bindStage.value = 'form'
}

async function createDevice(): Promise<void> {
  if (bindBusy.value) return
  bindError.value = ''
  if (!bindName.value.trim()) {
    bindError.value = '给设备起一个名字，家人才知道是谁的手机。'
    return
  }
  bindBusy.value = true
  try {
    const response = await api.devices.create({
      name: bindName.value.trim(),
      platform: 'android',
      model: bindModel.value.trim() || 'Android 设备',
      osVersion: bindOs.value.trim() || 'Android',
      appVersion: bindAppVersion.value.trim() || '0.1.0',
    })
    const decorated: DeviceInfo = {
      ...response.device,
      userId: response.device.userId ?? auth.user?.id,
      ownerNickname: response.device.ownerNickname ?? auth.user?.nickname,
    }
    family.upsertDevice(decorated)
    createdToken.value = response.deviceToken
    createdName.value = decorated.name
    bindStage.value = 'token'
    ui.toast('设备已绑定', 'success')
  } catch (err) {
    bindError.value = messageForError(err, '绑定失败，请稍后再试')
  } finally {
    bindBusy.value = false
  }
}

function openRename(device: DeviceInfo): void {
  if (!canManage(device)) return
  renameTarget.value = device
  renameName.value = device.name
  renameError.value = ''
}

async function confirmRename(): Promise<void> {
  const target = renameTarget.value
  if (!target || renameBusy.value) return
  const name = renameName.value.trim()
  if (!name) {
    renameError.value = '设备名不能为空。'
    return
  }
  renameBusy.value = true
  renameError.value = ''
  try {
    const response = await api.devices.rename(target.id, name)
    family.upsertDevice({
      ...target,
      ...response.device,
      userId: response.device.userId ?? target.userId,
      ownerNickname: response.device.ownerNickname ?? target.ownerNickname,
    })
    renameTarget.value = null
    ui.toast('设备名已更新', 'success')
  } catch (err) {
    renameError.value = messageForError(err, '重命名失败，请稍后再试')
  } finally {
    renameBusy.value = false
  }
}

function askUnbind(device: DeviceInfo): void {
  if (!canManage(device)) return
  unbindTarget.value = device
  unbindError.value = ''
}

async function confirmUnbind(): Promise<void> {
  const target = unbindTarget.value
  if (!target || unbindBusy.value) return
  unbindBusy.value = true
  unbindError.value = ''
  try {
    await api.devices.remove(target.id)
    family.removeDevice(target.id)
    if (target.userId) {
      await family.load(true)
    }
    unbindTarget.value = null
    ui.toast(`已解绑「${target.name}」`, 'success')
  } catch (err) {
    unbindError.value = messageForError(err, '解绑失败，请稍后再试')
  } finally {
    unbindBusy.value = false
  }
}

async function copyDeviceToken(): Promise<void> {
  const ok = await copyText(createdToken.value)
  if (ok) ui.toast('设备令牌已复制', 'success')
  else ui.toast('复制失败，请手动选择令牌', 'error')
}

async function retry(): Promise<void> {
  if (!family.family) {
    await family.load(true)
    return
  }
  await family.loadDevices(true)
}

onMounted(async () => {
  await family.ensureLoaded()
  if (family.family && !family.devices.length && !family.devicesError) {
    await family.loadDevices(true)
  }
})

function goMap(): void {
  void router.push('/')
}
</script>

<template>
  <div class="page page--wide devices-page">
    <header class="page-head">
      <div class="page-head__meta">
        <p class="micro">DEVICES · 设备</p>
        <h1 class="display-page">设备</h1>
        <p class="page-sub">
          {{ devices.length }} 台设备 · {{ onlineCount }} 台在线。设备令牌只显示一次，请在自己的手机上妥善保存。
        </p>
      </div>
      <div class="page-head__actions">
        <button type="button" class="btn btn--primary" @click="openBind">
          <Icon name="plus" :size="16" />
          绑定新设备
        </button>
      </div>
    </header>

    <div class="devices-note">
      <span class="devices-note__icon">
        <Icon name="info" :size="17" />
      </span>
      <p>
        <strong>解绑后立即生效。</strong>
        该设备会停止位置上报，需要重新在手机上登录并绑定才能恢复。同一成员可以绑定多台设备。
      </p>
    </div>

    <div v-if="family.devicesError && devices.length" class="alert alert--warn devices-warning">
      <Icon name="wifi-off" :size="16" />
      <span>{{ family.devicesError }}</span>
      <button type="button" class="link-btn" @click="retry">重试</button>
    </div>

    <section v-if="loading" class="section-card devices-card">
      <div class="section-card__body">
        <div v-for="index in 4" :key="index" class="device-skeleton-row">
          <span class="skeleton device-skeleton-row__icon" />
          <span class="device-skeleton-row__lines">
            <span class="skeleton skeleton-line" style="width: 28%" />
            <span class="skeleton skeleton-line skeleton-line--thin" style="width: 18%" />
          </span>
          <span class="skeleton skeleton-line" style="width: 12%" />
          <span class="skeleton skeleton-line" style="width: 10%" />
        </div>
      </div>
    </section>

    <section v-else-if="fatalError" class="section-card">
      <StateBlock
        tone="danger"
        icon="alert"
        title="设备列表加载失败"
        :description="family.error ?? '请检查网络连接后重试。'"
        action-label="重新加载"
        :busy="family.loading"
        @action="retry"
      />
    </section>

    <section v-else-if="!family.family" class="section-card">
      <StateBlock
        tone="warn"
        icon="users"
        title="还没有加入家庭"
        description="绑定设备前需要先加入一个家庭。"
        action-label="返回地图"
        @action="goMap"
      />
    </section>

    <section v-else-if="family.devicesError && !devices.length" class="section-card">
      <StateBlock
        tone="danger"
        icon="alert"
        title="设备列表加载失败"
        :description="family.devicesError"
        action-label="重新加载"
        :busy="family.devicesLoading"
        @action="retry"
      />
    </section>

    <section v-else class="section-card devices-card">
      <div v-if="!devices.length" class="devices-empty">
        <StateBlock
          icon="device"
          title="还没有绑定设备"
          description="在 Android 手机上登录后绑定设备，家人的位置才会出现在地图上。"
          action-label="绑定第一台设备"
          @action="openBind"
        />
      </div>

      <div v-else class="table-scroll">
        <table class="data-table devices-table">
          <thead>
            <tr>
              <th scope="col">设备名</th>
              <th scope="col">所属成员</th>
              <th scope="col">型号 / 系统</th>
              <th scope="col">App 版本</th>
              <th scope="col">在线状态</th>
              <th scope="col">最后心跳</th>
              <th scope="col" class="cell-aligned-right">操作</th>
            </tr>
          </thead>
          <tbody>
            <tr
              v-for="device in devices"
              :key="device.id"
              :class="{ 'is-muted': !device.online }"
            >
              <td>
                <div class="device-name-cell">
                  <span class="device-icon">
                    <Icon name="device" :size="17" />
                  </span>
                  <div>
                    <span class="cell-strong">{{ device.name }}</span>
                    <span class="cell-sub">
                      {{ ownedByMe(device) ? '我的设备' : '家庭成员设备' }}
                    </span>
                  </div>
                </div>
              </td>
              <td>{{ ownerName(device) }}</td>
              <td>
                <span class="cell-strong device-model">{{ device.model }}</span>
                <span class="cell-sub">{{ device.osVersion }}</span>
              </td>
              <td>
                <span class="version-chip">{{ device.appVersion }}</span>
              </td>
              <td>
                <span class="device-state">
                  <span
                    class="status-dot"
                    :class="device.online ? 'status-dot--online' : 'status-dot--offline'"
                  />
                  {{ device.online ? '在线' : '离线' }}
                </span>
              </td>
              <td class="tabular">{{ lastSeen(device) }}</td>
              <td>
                <div class="cell-actions">
                  <template v-if="canManage(device)">
                    <button
                      type="button"
                      class="link-btn"
                      @click="openRename(device)"
                    >
                      重命名
                    </button>
                    <button
                      type="button"
                      class="danger-link"
                      @click="askUnbind(device)"
                    >
                      解绑
                    </button>
                  </template>
                  <span
                    v-else
                    class="action-lock"
                    title="仅管理员可以管理他人设备"
                  >
                    <Icon name="lock" :size="12" />
                    仅管理员
                  </span>
                </div>
              </td>
            </tr>
          </tbody>
        </table>
      </div>
      <p class="table-hint">左右滑动查看完整设备表</p>
    </section>

    <!-- Bind device -->
    <ModalSheet
      :open="bindOpen"
      :title="bindStage === 'form' ? '绑定新设备' : '设备令牌'"
      :description="
        bindStage === 'form'
          ? '为 Android 客户端登记一台新设备。绑定后，用设备令牌在手机上登录并开始上报。'
          : `${createdName} 已绑定，把令牌填入手机 App 的设备设置。`
      "
      wide
      @close="closeBind"
    >
      <template v-if="bindStage === 'form'">
        <form class="bind-form" novalidate @submit.prevent="createDevice">
          <div v-if="bindError" class="alert alert--error" role="alert">
            <Icon name="alert" :size="16" />
            <span>{{ bindError }}</span>
          </div>
          <div class="form-grid">
            <div class="field span-2">
              <label class="field__label" for="bind-name">设备名</label>
              <input
                id="bind-name"
                v-model="bindName"
                class="input"
                type="text"
                maxlength="30"
                placeholder="例如：妈妈的手机"
                :disabled="bindBusy"
              />
            </div>
            <div class="field">
              <label class="field__label" for="bind-model">型号</label>
              <input
                id="bind-model"
                v-model="bindModel"
                class="input"
                type="text"
                placeholder="例如：Pixel 8"
                :disabled="bindBusy"
              />
            </div>
            <div class="field">
              <label class="field__label" for="bind-os">系统版本</label>
              <input
                id="bind-os"
                v-model="bindOs"
                class="input"
                type="text"
                placeholder="Android 15"
                :disabled="bindBusy"
              />
            </div>
            <div class="field">
              <label class="field__label" for="bind-app">App 版本</label>
              <input
                id="bind-app"
                v-model="bindAppVersion"
                class="input"
                type="text"
                placeholder="0.1.0"
                :disabled="bindBusy"
              />
            </div>
            <div class="field">
              <span class="field__label">平台</span>
              <div class="platform-static">
                <Icon name="phone" :size="15" />
                Android
              </div>
            </div>
          </div>
        </form>
      </template>

      <template v-else>
        <div class="token-panel">
          <p class="micro">DEVICE TOKEN · 只显示一次</p>
          <div class="token-value">{{ createdToken }}</div>
          <button type="button" class="btn btn--sm" @click="copyDeviceToken">
            <Icon name="copy" :size="14" />
            复制令牌
          </button>
        </div>
        <p class="alert alert--warn token-warning">
          <Icon name="alert" :size="15" />
          关闭这个窗口后将无法再次查看令牌。若遗失，请解绑设备后重新绑定。
        </p>
      </template>

      <template #footer>
        <template v-if="bindStage === 'form'">
          <button type="button" class="btn" :disabled="bindBusy" @click="closeBind">
            取消
          </button>
          <button
            type="button"
            class="btn btn--primary"
            :disabled="bindBusy"
            @click="createDevice"
          >
            <span v-if="bindBusy" class="spinner" />
            {{ bindBusy ? '正在绑定…' : '绑定设备' }}
          </button>
        </template>
        <button v-else type="button" class="btn btn--primary" @click="closeBind">
          完成
        </button>
      </template>
    </ModalSheet>

    <!-- Rename -->
    <ModalSheet
      :open="Boolean(renameTarget)"
      title="重命名设备"
      description="成员在地图与设备列表里都会看到这个名称。"
      @close="renameTarget = null"
    >
      <div class="field">
        <label class="field__label" for="rename-input">设备名</label>
        <input
          id="rename-input"
          v-model="renameName"
          class="input"
          type="text"
          maxlength="30"
          :disabled="renameBusy"
          @keydown.enter.prevent="confirmRename"
        />
      </div>
      <p v-if="renameError" class="field__error" role="alert">{{ renameError }}</p>
      <template #footer>
        <button type="button" class="btn" :disabled="renameBusy" @click="renameTarget = null">
          取消
        </button>
        <button type="button" class="btn btn--primary" :disabled="renameBusy" @click="confirmRename">
          <span v-if="renameBusy" class="spinner" />
          保存名称
        </button>
      </template>
    </ModalSheet>

    <ConfirmDialog
      :open="Boolean(unbindTarget)"
      title="解绑设备？"
      :description="`「${unbindTarget?.name ?? '该设备'}」将立即停止位置上报，需要重新绑定后才能恢复。历史位置不会被删除。`"
      confirm-label="解绑设备"
      tone="danger"
      :busy="unbindBusy"
      @cancel="unbindTarget = null"
      @confirm="confirmUnbind"
    >
      <p v-if="unbindError" class="field__error" role="alert" style="margin-top: 0">
        {{ unbindError }}
      </p>
    </ConfirmDialog>
  </div>
</template>

<style scoped>
.devices-page {
  padding-bottom: 64px;
}

.devices-note {
  display: flex;
  align-items: flex-start;
  gap: 12px;
  margin-bottom: 20px;
  padding: 14px 16px;
  border-radius: var(--r-card);
  background: var(--teal);
  color: var(--teal-ink);
  font-size: 12.5px;
  line-height: 1.6;
}

.devices-note strong {
  font-weight: 600;
}

.devices-note__icon {
  display: grid;
  place-items: center;
  width: 28px;
  height: 28px;
  flex: 0 0 auto;
  border-radius: 50%;
  background: rgba(255, 255, 255, 0.55);
}

.devices-warning {
  display: flex;
  align-items: center;
  gap: 10px;
  margin-bottom: 16px;
}

.devices-warning .link-btn {
  margin-left: auto;
  color: var(--orange-ink);
}

.devices-card {
  overflow: hidden;
}

.devices-table {
  --table-min: 940px;
}

.devices-table thead th:first-child,
.devices-table tbody td:first-child {
  padding-left: 24px;
}

.devices-table thead th:last-child,
.devices-table tbody td:last-child {
  padding-right: 24px;
}

.cell-aligned-right {
  text-align: right;
}

.device-name-cell {
  display: flex;
  align-items: center;
  gap: 10px;
}

.device-icon {
  display: grid;
  place-items: center;
  width: 34px;
  height: 34px;
  flex: 0 0 auto;
  border-radius: 10px;
  background: var(--surface-soft);
  color: var(--slate);
}

.device-model {
  font-weight: 500;
  color: var(--ink);
}

.version-chip {
  display: inline-flex;
  align-items: center;
  min-height: 22px;
  padding: 0 7px;
  border-radius: 5px;
  background: var(--surface-soft);
  color: var(--slate);
  font-size: 11px;
  font-weight: 500;
  font-variant-numeric: tabular-nums;
}

.device-state {
  display: inline-flex;
  align-items: center;
  gap: 7px;
  font-size: 12.5px;
  white-space: nowrap;
}

.device-state .status-dot {
  width: 6px;
  height: 6px;
  box-shadow: none;
}

.action-lock {
  display: inline-flex;
  align-items: center;
  gap: 4px;
  color: var(--ink-muted);
  font-size: 11.5px;
  white-space: nowrap;
}

.devices-empty {
  padding: 16px;
}

.devices-table .cell-actions {
  gap: 16px;
}

.devices-table tbody tr.is-muted {
  opacity: 0.68;
}

.devices-table tbody tr.is-muted:hover {
  opacity: 1;
}

.devices-table tbody tr.is-muted .cell-strong {
  color: var(--slate);
}

.bind-form {
  display: flex;
  flex-direction: column;
  gap: 16px;
}

.platform-static {
  display: flex;
  align-items: center;
  gap: 7px;
  min-height: 48px;
  padding: 0 16px;
  border: 1px solid var(--input-line);
  border-radius: var(--r-btn);
  background: var(--surface-soft);
  color: var(--slate);
  font-size: 14px;
}

.token-panel {
  display: flex;
  flex-direction: column;
  align-items: flex-start;
  gap: 10px;
  padding: 16px;
  border-radius: 12px;
  background: var(--surface-soft);
}

.token-value {
  width: 100%;
  padding: 12px;
  border-radius: 8px;
  background: #fff;
  box-shadow: inset 0 0 0 1px var(--line);
  font-family: 'SFMono-Regular', 'Cascadia Code', Consolas, monospace;
  font-size: 12px;
  line-height: 1.6;
  color: var(--ink);
  word-break: break-all;
  user-select: all;
}

.token-warning {
  display: flex;
  align-items: flex-start;
  gap: 8px;
  margin-top: 14px;
}

.device-skeleton-row {
  display: flex;
  align-items: center;
  gap: 14px;
  padding: 16px 24px;
  border-bottom: 1px solid var(--line);
}

.device-skeleton-row:last-child {
  border-bottom: 0;
}

.device-skeleton-row__icon {
  width: 34px;
  height: 34px;
  border-radius: 10px;
  flex: 0 0 auto;
}

.device-skeleton-row__lines {
  display: flex;
  flex-direction: column;
  gap: 7px;
  flex: 1;
}

.skeleton-line {
  height: 10px;
  border-radius: 4px;
}

.skeleton-line--thin {
  height: 8px;
}

@media (max-width: 600px) {
  .devices-note {
    padding: 12px;
    font-size: 12px;
  }

  .devices-table thead th:first-child,
  .devices-table tbody td:first-child {
    padding-left: 18px;
  }

  .devices-table thead th:last-child,
  .devices-table tbody td:last-child {
    padding-right: 18px;
  }
}
</style>
