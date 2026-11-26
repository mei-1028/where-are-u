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
const sourceLabel = computed(() =>
  isMockMode ? '本地演示数据 · 无需服务器' : getApiBase(),
)

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
  <div class="page settings-page">
    <header class="page-head settings-head">
      <div class="page-head__meta">
        <p class="micro">SETTINGS · 设置</p>
        <h1 class="display-page">设置</h1>
        <p class="page-sub">账号资料、数据保留与隐私。位置只属于家人，你可以随时退出。</p>
      </div>
      <span class="chip" :class="isAdmin ? 'chip--blue' : ''">
        <Icon :name="isAdmin ? 'shield' : 'user'" :size="13" />
        {{ roleLabel }}
      </span>
    </header>

    <div class="settings-stack">
      <!-- Account -->
      <section class="section-card">
        <div class="section-card__head">
          <div>
            <p class="micro">ACCOUNT · 账号资料</p>
            <h2 class="title-card settings-title">账号</h2>
          </div>
          <span class="settings-family">
            {{ family.family?.name ?? '未加入家庭' }}
          </span>
        </div>

        <div class="section-card__body account-body">
          <div class="profile-row">
            <Avatar
              :name="nickname || auth.user?.nickname || '我'"
              :id="auth.user?.id"
              :size="64"
            />
            <div class="profile-copy">
              <p class="profile-name">{{ auth.user?.nickname ?? '家人' }}</p>
              <p class="profile-username">@{{ auth.user?.username ?? '—' }}</p>
            </div>
          </div>

          <form class="account-form" novalidate @submit.prevent="saveProfile">
            <div class="account-fields">
              <div class="field">
                <label class="field__label" for="settings-nickname">昵称</label>
                <input
                  id="settings-nickname"
                  v-model="nickname"
                  class="input"
                  type="text"
                  maxlength="20"
                  placeholder="家人看到的名字"
                />
                <p class="field__hint">M1 会先把昵称保存在当前浏览器；服务端资料接口尚未开放。</p>
              </div>
              <div class="field">
                <label class="field__label" for="settings-username">用户名</label>
                <input
                  id="settings-username"
                  class="input"
                  type="text"
                  :value="auth.user?.username ?? ''"
                  disabled
                />
                <p class="field__hint">用户名用于登录，M1 暂不支持修改。</p>
              </div>
            </div>
            <div class="account-actions">
              <button
                type="submit"
                class="btn btn--primary"
                :disabled="!nicknameChanged || !nickname.trim() || profileBusy"
              >
                <span v-if="profileBusy" class="spinner" />
                保存资料
              </button>
              <button type="button" class="btn" disabled title="P1 功能">
                修改密码
                <span class="badge-p1">P1</span>
              </button>
            </div>
          </form>
        </div>
      </section>

      <!-- Retention -->
      <section class="section-card">
        <div class="section-card__head">
          <div>
            <p class="micro">DATA · 数据保留</p>
            <h2 class="title-card settings-title">
              数据保留
              <span class="badge-p1">P1</span>
            </h2>
          </div>
          <span class="chip">服务端未开放</span>
        </div>
        <div class="section-card__body">
          <div class="setting-row">
            <div class="setting-row__copy">
              <p class="setting-row__title">历史位置保留时长</p>
              <p class="setting-row__desc">
                服务端尚未实现 retention 接口。开放后管理员可配置 30 / 90 / 180 天或永久保留；当前无法修改，也不会改变服务端数据。
              </p>
            </div>
            <div class="setting-row__control">
              <select
                class="input retention-select"
                disabled
                aria-label="历史位置保留时长（P1 未开放）"
              >
                <option>服务端默认策略</option>
              </select>
            </div>
          </div>
          <div v-if="family.family" class="retention-foot">
            <Icon name="database" :size="14" />
            等待服务端 retention 接口 · P1
          </div>
        </div>
      </section>

      <!-- Notifications P1 -->
      <section class="section-card notifications-section">
        <div class="section-card__head">
          <div>
            <p class="micro">NOTIFICATIONS · 通知</p>
            <h2 class="title-card settings-title">
              通知
              <span class="badge-p1">P1</span>
            </h2>
          </div>
          <span class="chip">即将推出</span>
        </div>
        <div class="section-card__body">
          <div class="setting-row is-disabled">
            <div class="setting-row__copy">
              <p class="setting-row__title">离线提醒</p>
              <p class="setting-row__desc">家人超过 10 分钟没有位置更新时通知你。</p>
            </div>
            <div class="setting-row__control">
              <button
                type="button"
                class="switch"
                role="switch"
                aria-checked="false"
                aria-label="离线提醒（P1，未开放）"
                disabled
              />
            </div>
          </div>
          <div class="setting-row is-disabled">
            <div class="setting-row__copy">
              <p class="setting-row__title">低电量提醒</p>
              <p class="setting-row__desc">家人设备电量低于 20% 时提醒你。</p>
            </div>
            <div class="setting-row__control">
              <button
                type="button"
                class="switch"
                role="switch"
                aria-checked="false"
                aria-label="低电量提醒（P1，未开放）"
                disabled
              />
            </div>
          </div>
          <p class="notifications-note">
            推送通知与地理围栏提醒将在后续版本提供，M1 不发送任何推送。
          </p>
        </div>
      </section>

      <!-- Danger zone -->
      <section class="section-card danger-section">
        <div class="section-card__head danger-head">
          <div>
            <p class="micro danger-micro">DANGER ZONE · 危险操作</p>
            <h2 class="title-card settings-title">危险操作</h2>
          </div>
          <span class="danger-orb">
            <Icon name="alert" :size="20" />
          </span>
        </div>
        <div class="section-card__body">
          <div class="setting-row">
            <div class="setting-row__copy">
              <p class="setting-row__title">清空家庭历史位置</p>
              <p class="setting-row__desc">
                {{
                  isMockMode
                    ? '删除全家所有历史轨迹（演示数据），不可恢复。'
                    : 'M1 服务端契约尚未提供此接口，当前版本不可用。'
                }}
              </p>
            </div>
            <div class="setting-row__control">
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
          </div>

          <div class="setting-row">
            <div class="setting-row__copy">
              <p class="setting-row__title">退出家庭</p>
              <p class="setting-row__desc">
                {{
                  isAdmin
                    ? '管理员不能直接退出家庭。M1 暂不支持转让管理员，请先移除成员或等待后续版本。'
                    : '退出后将无法查看家人的位置与历史轨迹，你的设备会从家庭解绑。'
                }}
              </p>
            </div>
            <div class="setting-row__control">
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
          </div>

          <div class="setting-row">
            <div class="setting-row__copy">
              <p class="setting-row__title">退出登录</p>
              <p class="setting-row__desc">退出当前账号。演示模式下，数据仍保留在本机。</p>
            </div>
            <div class="setting-row__control">
              <button type="button" class="btn btn--sm" @click="logoutOpen = true">
                <Icon name="logout" :size="15" />
                退出登录
              </button>
            </div>
          </div>
        </div>
      </section>

      <div class="settings-meta">
        <span>{{ sourceLabel }}</span>
        <span class="settings-meta__sep" aria-hidden="true" />
        <span>v0.1.0 · M1</span>
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
  max-width: 920px;
  padding-bottom: 72px;
}

.settings-head {
  margin-bottom: 26px;
}

.settings-stack {
  display: flex;
  flex-direction: column;
  gap: 18px;
}

.settings-title {
  margin-top: 6px;
  display: flex;
  align-items: center;
  gap: 8px;
}

.settings-family {
  display: inline-flex;
  align-items: center;
  height: 26px;
  padding: 0 10px;
  border-radius: var(--r-pill);
  background: var(--surface-soft);
  color: var(--slate);
  font-size: 11.5px;
  font-weight: 500;
  white-space: nowrap;
}

/* ---- Account ----------------------------------------------------------- */

.account-body {
  display: flex;
  flex-direction: column;
  gap: 24px;
}

.profile-row {
  display: flex;
  align-items: center;
  gap: 16px;
  padding: 16px;
  border-radius: var(--r-card);
  background: var(--surface-soft);
}

.profile-copy {
  min-width: 0;
}

.profile-name {
  font-size: 20px;
  line-height: 1.25;
  letter-spacing: -0.02em;
  font-weight: 600;
}

.profile-username {
  margin-top: 3px;
  color: var(--ink-muted);
  font-size: 12.5px;
}

.account-form {
  display: flex;
  flex-direction: column;
  gap: 20px;
}

.account-fields {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 18px;
}

.account-actions {
  display: flex;
  align-items: center;
  gap: 10px;
  flex-wrap: wrap;
}

/* ---- Retention --------------------------------------------------------- */

.retention-select {
  width: 170px;
  min-height: 40px;
  font-size: 14px;
}

.retention-foot {
  display: flex;
  align-items: center;
  gap: 6px;
  margin-top: 2px;
  padding-top: 16px;
  border-top: 1px solid var(--line);
  color: var(--ink-muted);
  font-size: 11.5px;
}

.lock-note {
  display: inline-flex;
  align-items: center;
  gap: 5px;
  color: var(--ink-muted);
  font-size: 11.5px;
  white-space: nowrap;
}

/* ---- Notifications ----------------------------------------------------- */

.notifications-section .section-card__head .badge-p1 {
  vertical-align: 3px;
}

.notifications-note {
  margin-top: 4px;
  padding-top: 16px;
  border-top: 1px solid var(--line);
  color: var(--ink-muted);
  font-size: 11.5px;
  line-height: 1.6;
}

.setting-row.is-disabled {
  opacity: 0.62;
}

/* ---- Danger zone ------------------------------------------------------- */

.danger-section {
  position: relative;
}

.danger-section::before {
  content: '';
  position: absolute;
  top: 0;
  left: 0;
  right: 0;
  height: 3px;
  background: var(--red);
  border-radius: var(--r-panel) var(--r-panel) 0 0;
}

.danger-head {
  align-items: center;
}

.danger-micro {
  color: #a35b5b;
}

.danger-orb {
  display: grid;
  place-items: center;
  width: 40px;
  height: 40px;
  border-radius: 12px;
  background: var(--red);
  color: var(--red-ink);
}

.danger-section .setting-row__title {
  color: var(--ink);
}

.settings-meta {
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 8px 4px 0;
  color: var(--ink-muted);
  font-size: 10.5px;
  letter-spacing: 0.01em;
  flex-wrap: wrap;
}

.settings-meta__sep {
  width: 3px;
  height: 3px;
  border-radius: 50%;
  background: #c7cad5;
}

@media (max-width: 900px) {
  .settings-page {
    padding-bottom: 48px;
  }

  .account-fields {
    grid-template-columns: 1fr;
  }

  .retention-select {
    width: 100%;
  }
}

@media (max-width: 600px) {
  .settings-stack {
    gap: 14px;
  }

  .profile-row {
    padding: 14px;
    gap: 12px;
  }

  .account-actions .btn {
    flex: 1 1 auto;
  }

  .settings-family {
    display: none;
  }

  .danger-orb {
    width: 34px;
    height: 34px;
  }
}
</style>
