<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import { isMockMode } from '@/api'
import { api } from '@/api'
import { useAuthStore } from '@/stores/auth'
import { useFamilyStore } from '@/stores/family'
import { useUiStore } from '@/stores/ui'
import { messageForError } from '@/lib/errors'
import { getApiBase } from '@/lib/session'
import {
  notificationPermission,
  notificationsSupported,
  requestNotificationPermission,
} from '@/lib/notify'
import Avatar from '@/components/Avatar.vue'
import ConfirmDialog from '@/components/ConfirmDialog.vue'
import Icon from '@/components/Icon.vue'


const auth = useAuthStore()
const family = useFamilyStore()
const ui = useUiStore()
const router = useRouter()

const nickname = ref(auth.user?.nickname ?? '')
const clearOpen = ref(false)
const clearBusy = ref(false)
const leaveOpen = ref(false)
const leaveBusy = ref(false)
const logoutOpen = ref(false)

const isAdmin = computed(() => family.isAdmin)
const roleLabel = computed(() => (isAdmin.value ? '管理员' : '成员'))
const profileBusy = ref(false)
const nicknameChanged = computed(
  () => nickname.value.trim() !== (auth.user?.nickname ?? ''),
)
const canClearHistory = computed(() => isAdmin.value && isMockMode)
const canLeave = computed(() => Boolean(family.family) && !isAdmin.value)

const notifySupported = notificationsSupported()
const notifyPermission = ref(notificationPermission())
const notifyBusy = ref(false)

async function enableDesktopNotifications(): Promise<void> {
  if (notifyBusy.value) return
  notifyBusy.value = true
  try {
    const granted = await requestNotificationPermission()
    notifyPermission.value = notificationPermission()
    ui.toast(
      granted ? '已开启桌面通知' : '未获得通知权限，可在浏览器设置中开启',
      granted ? 'success' : 'error',
    )
  } finally {
    notifyBusy.value = false
  }
}

onMounted(async () => {
  await family.ensureLoaded()
  if (!nickname.value) nickname.value = auth.user?.nickname ?? ''
})

async function saveProfile(): Promise<void> {
  if (profileBusy.value) return
  const next = nickname.value.trim()
  if (!next) {
    ui.toast('昵称不能为空', 'error')
    return
  }
  if (next.length > 20) {
    ui.toast('昵称最多 20 个字符', 'error')
    return
  }
  profileBusy.value = true
  try {
    if (isMockMode) {
      const { mockUpdateNickname } = await import('@mock-runtime')
      await mockUpdateNickname(next)
    }
    auth.updateNickname(next)
    const me = family.members.find((member) => member.userId === auth.user?.id)
    if (me) me.nickname = next
    nickname.value = next
    ui.toast(isMockMode ? '资料已保存' : '资料已保存（本机）', 'success')
  } catch (err) {
    ui.toast(messageForError(err, '保存失败，请稍后再试'), 'error')
  } finally {
    profileBusy.value = false
  }
}

async function clearHistory(): Promise<void> {
  if (!canClearHistory.value || clearBusy.value) return
  clearBusy.value = true
  try {
    const { mockClearFamilyHistory } = await import('@mock-runtime')
    await mockClearFamilyHistory()
    clearOpen.value = false
    ui.toast('家庭历史位置已清空', 'success')
  } catch (err) {
    ui.toast(messageForError(err, '清空失败，请稍后再试'), 'error')
  } finally {
    clearBusy.value = false
  }
}

async function leaveFamily(): Promise<void> {
  if (!canLeave.value || leaveBusy.value) return
  leaveBusy.value = true
  try {
    await api.families.leave()
    auth.setFamilyId(null)
    family.reset()
    leaveOpen.value = false
    ui.toast('已退出家庭', 'success')
    await router.replace('/onboarding')
  } catch (err) {
    ui.toast(messageForError(err, '退出失败，请稍后再试'), 'error')
  } finally {
    leaveBusy.value = false
  }
}

function confirmLogout(): void {
  logoutOpen.value = false
  auth.logout()
  void router.replace('/login')
}
</script>

<template>
  <div class="page-shell">
    <div class="page-card settings-page">
      <header class="settings-head">
        <div class="settings-head__copy">
          <h1 class="settings-title">设置</h1>
          <p class="settings-sub">账号资料、数据保留与危险操作。</p>
        </div>
        <button
          type="button"
          class="btn btn--primary"
          :disabled="!nicknameChanged || !nickname.trim() || profileBusy"
          @click="saveProfile"
        >
          <span v-if="profileBusy" class="spinner" />
          保存更改
        </button>
      </header>

      <div class="settings-grid">
        <div class="settings-col">
          <section class="card account-card">
            <p class="card-label">账号资料</p>

            <div class="account-head">
              <Avatar
                :name="nickname || auth.user?.nickname || '我'"
                :id="auth.user?.id"
                :size="64"
              />
              <div class="account-head__copy">
                <p class="account-name">{{ auth.user?.nickname ?? '家人' }}</p>
                <p class="account-meta">
                  @{{ auth.user?.username ?? '—' }} · {{ roleLabel }}
                </p>
              </div>
              <button
                type="button"
                class="btn btn--sm account-avatar-btn"
                disabled
                title="头像上传将在后续版本提供"
              >
                <Icon name="edit" :size="13" />
                更换头像
              </button>
            </div>

            <div class="card-divider" />

            <form class="account-form" novalidate @submit.prevent="saveProfile">
              <label class="field">
                <span class="field__label">昵称</span>
                <input
                  v-model="nickname"
                  class="input"
                  type="text"
                  maxlength="20"
                  placeholder="家人看到的名字"
                />
              </label>
              <label class="field">
                <span class="field__label">用户名</span>
                <span class="input-wrap">
                  <input
                    class="input input--readonly"
                    type="text"
                    :value="auth.user?.username ?? ''"
                    disabled
                  />
                  <Icon name="lock" :size="13" class="input-wrap__icon" />
                </span>
              </label>
            </form>

            <div class="inline-row">
              <span class="inline-row__copy">
                <Icon name="key" :size="14" />
                修改密码
              </span>
              <span class="tag">P1 · 后续版本</span>
            </div>
          </section>

          <section class="card">
            <div class="card-head">
              <span class="card-head__title">
                <span class="card-label">数据保留</span>
                <span class="tag">P1</span>
              </span>
              <span class="tag tag--blue">仅管理员</span>
            </div>
            <div class="setting-row">
              <div class="setting-row__copy">
                <p class="setting-row__title">历史位置保留时长</p>
                <p class="setting-row__desc">服务端 retention 接口尚未开放</p>
              </div>
              <div class="select-fake" aria-disabled="true">
                服务端默认策略
                <Icon name="chevron-down" :size="13" />
              </div>
            </div>
          </section>

          <section class="card">
            <div class="card-head">
              <span class="card-head__title">
                <span class="card-label">通知提醒</span>
                <span class="tag tag--blue">关注提醒</span>
              </span>
            </div>
            <div class="setting-row">
              <div class="setting-row__copy">
                <p class="setting-row__title">桌面通知</p>
                <p class="setting-row__desc">
                  页面打开时，关注的家人有变动可以在桌面弹出通知。具体提醒规则在成员详情里设置。
                </p>
              </div>
              <button
                v-if="notifySupported && notifyPermission === 'granted'"
                type="button"
                class="switch is-on"
                role="switch"
                aria-checked="true"
                disabled
              />
              <button
                v-else
                type="button"
                class="btn btn--sm"
                :disabled="!notifySupported || notifyBusy || notifyPermission === 'denied'"
                @click="enableDesktopNotifications"
              >
                {{ notifyPermission === 'denied' ? '浏览器已拒绝' : '开启' }}
              </button>
            </div>
            <div class="setting-row">
              <div class="setting-row__copy">
                <p class="setting-row__title">站内消息中心</p>
                <p class="setting-row__desc">所有提醒都会保留在「消息」里，App 被系统清理也不会丢</p>
              </div>
              <RouterLink to="/alerts" class="btn btn--ghost btn--sm">查看消息</RouterLink>
            </div>
          </section>
        </div>

        <aside class="settings-col settings-col--side">
          <section class="card about-card">
            <p class="card-label">关于</p>
            <div class="about-row">
              <span>版本</span>
              <strong>v0.1 · M1 原型</strong>
            </div>
            <div class="about-row">
              <span>服务器</span>
              <strong>{{ isMockMode ? '本地演示数据' : getApiBase() }}</strong>
            </div>
            <div class="about-row">
              <span>客户端</span>
              <strong>Web · Android</strong>
            </div>
            <div class="about-row">
              <span>部署方式</span>
              <strong>自建 · 家中服务器</strong>
            </div>
          </section>

          <section class="card danger-card">
            <div class="danger-head">
              <div>
                <p class="danger-title">危险操作</p>
                <p class="danger-sub">以下操作不可撤销</p>
              </div>
              <span class="danger-orb">
                <Icon name="alert" :size="16" />
              </span>
            </div>

            <div class="card-divider" />

            <div class="danger-row">
              <div class="danger-row__copy">
                <p class="danger-row__title">清空家庭历史位置</p>
                <p class="danger-row__desc">
                  {{
                    isMockMode
                      ? '删除全家所有历史轨迹，不可恢复。'
                      : 'M1 服务端尚未提供此接口。'
                  }}
                </p>
              </div>
              <button
                v-if="isAdmin"
                type="button"
                class="btn btn--danger btn--sm"
                :disabled="!canClearHistory"
                :title="canClearHistory ? '清空家庭历史位置' : '服务端接口尚未提供'"
                @click="clearOpen = true"
              >
                清空历史
              </button>
              <span v-else class="lock-note">
                <Icon name="lock" :size="12" />
                仅管理员
              </span>
            </div>

            <div class="danger-row">
              <div class="danger-row__copy">
                <p class="danger-row__title">退出家庭</p>
                <p class="danger-row__desc">
                  {{
                    isAdmin
                      ? '管理员需先在家庭管理中转让管理员。'
                      : '退出后无法查看家人位置与轨迹。'
                  }}
                </p>
              </div>
              <button
                v-if="!isAdmin"
                type="button"
                class="btn btn--danger btn--sm"
                :disabled="!canLeave"
                @click="leaveOpen = true"
              >
                退出家庭
              </button>
              <span v-else class="lock-note">
                <Icon name="lock" :size="12" />
                管理员不可退出
              </span>
            </div>

            <div class="danger-row">
              <div class="danger-row__copy">
                <p class="danger-row__title">退出登录</p>
                <p class="danger-row__desc">退出当前账号，演示数据保留在本机。</p>
              </div>
              <button type="button" class="btn btn--sm" @click="logoutOpen = true">
                退出登录
              </button>
            </div>
          </section>
        </aside>
      </div>
    </div>

    <ConfirmDialog
      :open="clearOpen"
      title="清空家庭历史位置？"
      description="全家所有成员的历史轨迹都会被删除，且无法恢复。当前位置与设备绑定不受影响。"
      confirm-label="清空历史"
      tone="danger"
      :busy="clearBusy"
      @cancel="clearOpen = false"
      @confirm="clearHistory"
    />
    <ConfirmDialog
      :open="leaveOpen"
      title="退出家庭？"
      description="退出后将无法查看家人的位置与历史轨迹，你的设备也会从家庭解绑。之后可以重新用邀请码加入。"
      confirm-label="退出家庭"
      tone="danger"
      :busy="leaveBusy"
      @cancel="leaveOpen = false"
      @confirm="leaveFamily"
    />
    <ConfirmDialog
      :open="logoutOpen"
      title="退出登录？"
      description="下次需要重新输入用户名和密码。演示数据会保留在本机浏览器中。"
      confirm-label="退出登录"
      @cancel="logoutOpen = false"
      @confirm="confirmLogout"
    />
  </div>
</template>

<style scoped>
.settings-page {
  display: flex;
  flex-direction: column;
  padding-bottom: 32px;
}

.settings-head {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 24px;
  margin-bottom: 20px;
}

.settings-title {
  font-size: 46px;
  line-height: 1.12;
  letter-spacing: -0.028em;
  font-weight: 500;
}

.settings-sub {
  margin-top: 8px;
  color: var(--slate);
  font-size: 13px;
}

.settings-grid {
  display: grid;
  grid-template-columns: minmax(0, 1fr) 380px;
  gap: 28px;
  align-items: start;
}

.settings-col {
  display: flex;
  flex-direction: column;
  gap: 14px;
  min-width: 0;
}

.card {
  padding: 18px;
  border-radius: 16px;
  background: var(--white);
  box-shadow: inset 0 0 0 1px var(--ring);
}

.card-label {
  font-size: 10.5px;
  font-weight: 600;
  letter-spacing: 0.1em;
  text-transform: uppercase;
  color: var(--ink-muted);
}

.card-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
}

.card-head__title {
  display: inline-flex;
  align-items: center;
  gap: 8px;
}

.card-divider {
  height: 1px;
  margin: 14px 0;
  background: var(--input-line);
}

/* ---- Account ----------------------------------------------------------- */

.account-head {
  display: flex;
  align-items: center;
  gap: 16px;
  margin-top: 14px;
}

.account-head__copy {
  min-width: 0;
  flex: 1;
}

.account-name {
  font-size: 18px;
  line-height: 1.3;
  letter-spacing: -0.02em;
  font-weight: 600;
}

.account-meta {
  margin-top: 3px;
  color: var(--ink-muted);
  font-size: 12px;
}

.account-avatar-btn {
  flex: 0 0 auto;
}

.account-form {
  display: flex;
  flex-direction: column;
  gap: 14px;
}

.field {
  display: flex;
  flex-direction: column;
  gap: 8px;
}

.field__label {
  font-size: 12.5px;
  font-weight: 500;
  color: var(--slate);
  letter-spacing: 0;
  text-transform: none;
}

.input {
  width: 100%;
  height: 42px;
  min-height: 42px;
  padding: 0 14px;
  border: 1px solid var(--input-line);
  border-radius: 8px;
  background: var(--white);
  font-size: 14px;
  color: var(--ink);
  transition: border-color 0.16s, box-shadow 0.16s;
}

.input:focus {
  outline: none;
  border-color: var(--blue);
  box-shadow: 0 0 0 3px rgba(91, 118, 254, 0.14);
}

.input-wrap {
  position: relative;
  display: block;
}

.input--readonly {
  background: var(--surface-soft);
  color: var(--ink-muted);
  padding-right: 40px;
}

.input-wrap__icon {
  position: absolute;
  top: 50%;
  right: 14px;
  transform: translateY(-50%);
  color: var(--ink-muted);
}

.inline-row {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  height: 42px;
  margin-top: 14px;
  padding: 0 14px;
  border-radius: 8px;
  background: var(--surface-soft);
}

.inline-row__copy {
  display: inline-flex;
  align-items: center;
  gap: 8px;
  color: var(--ink);
  font-size: 13px;
  font-weight: 500;
}

.tag {
  display: inline-flex;
  align-items: center;
  height: 20px;
  padding: 0 7px;
  border-radius: 6px;
  background: var(--surface-soft);
  color: var(--ink-muted);
  font-size: 10.5px;
  font-weight: 600;
  white-space: nowrap;
}

.tag--blue {
  background: var(--blue-soft);
  color: var(--blue);
}

/* ---- Setting rows ------------------------------------------------------ */

.setting-row {
  display: flex;
  flex-direction: row;
  align-items: center;
  justify-content: space-between;
  gap: 16px;
  margin-top: 12px;
  padding: 0;
  border-top: 0;
}

.setting-row:first-child {
  padding-top: 0;
}

.setting-row.is-muted {
  opacity: 0.62;
}

.setting-row__copy {
  min-width: 0;
}

.setting-row__title {
  font-size: 12.5px;
  font-weight: 600;
  line-height: 1.25;
  color: var(--ink);
}

.setting-row__desc {
  margin-top: 2px;
  color: var(--ink-muted);
  font-size: 10.5px;
  line-height: 1.35;
}

.select-fake {
  display: inline-flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  width: 180px;
  height: 38px;
  padding: 0 12px;
  border-radius: 8px;
  background: var(--surface-soft);
  color: var(--ink-muted);
  font-size: 12px;
  flex: 0 0 auto;
}

.switch {
  position: relative;
  width: 36px;
  height: 20px;
  border-radius: 10px;
  background: var(--ring);
  flex: 0 0 auto;
}

.switch::after {
  content: '';
  position: absolute;
  top: 2px;
  left: 2px;
  width: 16px;
  height: 16px;
  border-radius: 50%;
  background: var(--white);
}

/* ---- About ------------------------------------------------------------- */

.about-card {
  display: flex;
  flex-direction: column;
  gap: 4px;
}

.about-row {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 16px;
  height: 36px;
  font-size: 11px;
}

.about-row span {
  color: var(--slate);
}

.about-row strong {
  color: var(--ink);
  font-weight: 500;
  font-variant-numeric: tabular-nums;
  text-align: right;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

/* ---- Danger ------------------------------------------------------------ */

.danger-card {
  position: relative;
  padding: 20px;
  overflow: hidden;
}

.danger-card::before {
  content: '';
  position: absolute;
  top: 0;
  left: 0;
  right: 0;
  height: 3px;
  background: var(--red);
}

.danger-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
}

.danger-title {
  color: var(--red-ink);
  font-size: 13px;
  font-weight: 600;
}

.danger-sub {
  margin-top: 2px;
  color: var(--ink-muted);
  font-size: 10.5px;
}

.danger-orb {
  display: grid;
  place-items: center;
  width: 32px;
  height: 32px;
  border-radius: 8px;
  background: var(--red);
  color: var(--red-ink);
}

.danger-row {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 16px;
  padding: 12px 0;
  border-top: 1px solid var(--line);
}

.danger-card .card-divider + .danger-row {
  border-top: 0;
}

.danger-row__copy {
  min-width: 0;
}

.danger-row__title {
  font-size: 12.5px;
  font-weight: 600;
  color: var(--ink);
}

.danger-row__desc {
  margin-top: 3px;
  color: var(--ink-muted);
  font-size: 10.5px;
  line-height: 1.5;
}

.lock-note {
  display: inline-flex;
  align-items: center;
  gap: 5px;
  color: var(--ink-muted);
  font-size: 11px;
  white-space: nowrap;
  flex: 0 0 auto;
}

@media (max-width: 1100px) {
  .settings-grid {
    grid-template-columns: minmax(0, 1fr);
  }
}

@media (max-width: 600px) {
  .settings-title {
    font-size: 34px;
  }

  .settings-head {
    flex-direction: column;
    align-items: stretch;
  }

  .account-head {
    flex-wrap: wrap;
  }

  .select-fake {
    width: 140px;
  }

  .setting-row {
    flex-direction: column;
    align-items: stretch;
    gap: 10px;
  }

  .select-fake {
    width: 100%;
  }

  .account-avatar-btn {
    display: none;
  }
}
</style>
