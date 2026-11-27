<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import qrcode from 'qrcode-generator'
import { api } from '@/api'
import type { MemberView } from '@/api/types'
import { useAuthStore } from '@/stores/auth'
import { useFamilyStore } from '@/stores/family'
import { useUiStore } from '@/stores/ui'
import { useNow } from '@/composables/useNow'
import { copyText } from '@/lib/clipboard'
import { messageForError } from '@/lib/errors'
import { expiresLabel, relativeTime } from '@/lib/format'
import Avatar from '@/components/Avatar.vue'
import ConfirmDialog from '@/components/ConfirmDialog.vue'
import Icon from '@/components/Icon.vue'
import StateBlock from '@/components/StateBlock.vue'

const router = useRouter()
const auth = useAuthStore()
const family = useFamilyStore()
const ui = useUiStore()
const now = useNow()

const inviteCard = ref<HTMLElement | null>(null)
const resetOpen = ref(false)
const resetBusy = ref(false)
const removeTarget = ref<MemberView | null>(null)
const removeBusy = ref(false)
const removeError = ref('')

const members = computed(() => family.memberViews)
const isAdmin = computed(() => family.isAdmin)
const inviteCode = computed(() => family.family?.inviteCode ?? '')
const inviteExpiresAt = computed(() => family.family?.inviteExpiresAt ?? null)
const inviteExpired = computed(() => {
  if (!inviteExpiresAt.value) return false
  const time = new Date(inviteExpiresAt.value).getTime()
  return Number.isFinite(time) && time <= now.value
})
const inviteExpiryText = computed(() => {
  if (!inviteExpiresAt.value) return '邀请码 24 小时内有效'
  if (inviteExpired.value) return '邀请码已过期，请重置后分享'
  return expiresLabel(inviteExpiresAt.value)
})
const inviteUrl = computed(() =>
  inviteCode.value
    ? `${window.location.origin}/register?invite=${encodeURIComponent(inviteCode.value)}`
    : '',
)
const onlineCount = computed(() => members.value.filter((member) => member.online).length)
const loading = computed(() => family.loading && !family.family)
const fatalError = computed(() => !family.family && Boolean(family.error))

const qrSvg = computed(() => {
  if (!inviteUrl.value) return ''
  try {
    const qr = qrcode(0, 'M')
    qr.addData(inviteUrl.value)
    qr.make()
    return qr.createSvgTag({ cellSize: 4, margin: 0, scalable: true })
  } catch {
    return ''
  }
})

function scrollToInvite(): void {
  inviteCard.value?.scrollIntoView({ behavior: 'smooth', block: 'center' })
}

function goOnboarding(): void {
  void router.push('/onboarding')
}

function memberLastSeen(member: MemberView): string {
  const iso = member.lastSeenAt ?? member.lastLocation?.recordedAt
  if (!iso) return '暂无记录'
  return member.online ? '在线' : relativeTime(iso, now.value)
}

function canRemove(member: MemberView): boolean {
  return isAdmin.value && member.userId !== auth.user?.id
}

function askRemove(member: MemberView): void {
  if (!canRemove(member)) return
  removeTarget.value = member
  removeError.value = ''
}

async function copyInviteCode(): Promise<void> {
  if (!inviteCode.value) return
  if (inviteExpired.value) {
    ui.toast('邀请码已过期，请先重置', 'error')
    return
  }
  const ok = await copyText(inviteCode.value)
  if (ok) ui.toast('邀请码已复制', 'success')
  else ui.toast('复制失败，请长按选择邀请码', 'error')
}

function openReset(): void {
  if (!isAdmin.value) return
  resetOpen.value = true
}

async function resetInvite(): Promise<void> {
  if (resetBusy.value) return
  resetBusy.value = true
  try {
    const response = await api.families.resetInvite()
    if (family.family) {
      family.family.inviteCode = response.inviteCode
      family.family.inviteExpiresAt = response.expiresAt
    }
    resetOpen.value = false
    ui.toast('邀请码已重置，旧码已失效', 'success')
  } catch (err) {
    ui.toast(messageForError(err, '重置失败，请稍后再试'), 'error')
  } finally {
    resetBusy.value = false
  }
}

async function confirmRemove(): Promise<void> {
  const target = removeTarget.value
  if (!target || removeBusy.value) return
  removeBusy.value = true
  removeError.value = ''
  try {
    await api.families.removeMember(target.userId)
    await family.load(true)
    removeTarget.value = null
    ui.toast(`已移除${target.nickname}`, 'success')
  } catch (err) {
    removeError.value = messageForError(err, '移除失败，请稍后再试')
  } finally {
    removeBusy.value = false
  }
}

async function retry(): Promise<void> {
  await family.load(true)
}

onMounted(() => {
  void family.ensureLoaded()
})
</script>

<template>
  <div class="page-shell">
    <div class="page-card family-page">
    <header class="page-head family-head">
      <div class="page-head__meta">
        <h1 class="page-title">家庭管理</h1>
        <p class="page-sub">管理成员、邀请码与家庭信息。</p>
      </div>
      <div class="page-head__actions">
        <button
          v-if="isAdmin"
          type="button"
          class="btn btn--primary"
          @click="scrollToInvite"
        >
          <Icon name="users" :size="16" />
          邀请家人
        </button>
        <span v-else class="lock-note">
          <Icon name="lock" :size="12" />
          邀请由管理员发起
        </span>
      </div>
    </header>

    <template v-if="loading">
      <div class="family-grid">
        <section class="section-card">
          <div class="section-card__head">
            <div style="width: 100%">
              <div class="skeleton skeleton-line skeleton-line--short" />
            </div>
          </div>
          <div class="section-card__body">
            <div v-for="index in 4" :key="index" class="member-skeleton-row">
              <span class="skeleton member-skeleton-row__avatar" />
              <span class="member-skeleton-row__lines">
                <span class="skeleton skeleton-line" style="width: 36%" />
                <span class="skeleton skeleton-line skeleton-line--thin" style="width: 22%" />
              </span>
            </div>
          </div>
        </section>
        <aside class="invite-card invite-card--loading">
          <div class="skeleton skeleton-line skeleton-line--short" />
          <div class="skeleton skeleton-title" />
          <div class="skeleton skeleton-line" style="width: 88%" />
          <div class="skeleton skeleton-code" />
          <div class="skeleton skeleton-qr" />
        </aside>
      </div>
    </template>

    <template v-else-if="fatalError">
      <section class="section-card">
        <StateBlock
          tone="danger"
          icon="alert"
          title="家庭信息加载失败"
          :description="family.error ?? '请检查网络连接后重试。'"
          action-label="重新加载"
          :busy="family.loading"
          @action="retry"
        />
      </section>
    </template>

    <template v-else-if="!family.family">
      <section class="section-card">
        <StateBlock
          tone="warn"
          icon="users"
          title="还没有加入家庭"
          description="创建一个家庭，或者用邀请码加入家人的家庭。"
          action-label="前往加入家庭"
          @action="goOnboarding"
        />
      </section>
    </template>

    <template v-else>
      <div class="family-grid">
        <section class="section-card members-card">
          <div class="section-card__head">
            <div>
              <p class="micro">MEMBERS</p>
              <h2 class="title-card members-title">成员</h2>
            </div>
            <span class="chip chip--dot" :class="onlineCount ? 'chip--success' : ''">
              {{ onlineCount }} / {{ members.length }} 在线
            </span>
          </div>

          <div class="section-card__body members-body">
            <div class="table-scroll">
              <table class="data-table family-table">
                <thead>
                  <tr>
                    <th scope="col">成员</th>
                    <th scope="col">角色</th>
                    <th scope="col">共享状态</th>
                    <th scope="col">最后在线</th>
                    <th v-if="isAdmin" scope="col" class="cell-aligned-right">操作</th>
                  </tr>
                </thead>
                <tbody>
                  <tr v-for="member in members" :key="member.userId">
                    <td>
                      <div class="member-cell">
                        <span class="member-cell__avatar">
                          <Avatar
                            :name="member.nickname"
                            :id="member.userId"
                            :size="34"
                            :muted="!member.online"
                          />
                          <span
                            class="status-dot member-cell__dot"
                            :class="
                              member.sharingPaused
                                ? 'status-dot--paused'
                                : member.online
                                  ? 'status-dot--online'
                                  : 'status-dot--offline'
                            "
                          />
                        </span>
                        <div class="member-cell__copy">
                          <span class="cell-strong">
                            {{ member.nickname }}
                            <span v-if="member.userId === auth.user?.id" class="self-tag">我</span>
                          </span>
                          <span class="cell-sub">@{{ member.username }}</span>
                        </div>
                      </div>
                    </td>
                    <td>
                      <span class="chip" :class="member.role === 'admin' ? 'chip--blue' : ''">
                        {{ member.role === 'admin' ? '管理员' : '成员' }}
                      </span>
                    </td>
                    <td>
                      <span class="sharing-state">
                        <span
                          class="status-dot"
                          :class="member.sharingPaused ? 'status-dot--paused' : 'status-dot--online'"
                        />
                        {{ member.sharingPaused ? '已暂停' : '共享中' }}
                      </span>
                    </td>
                    <td>
                      <span :class="{ 'online-text': member.online }">
                        {{ memberLastSeen(member) }}
                      </span>
                    </td>
                    <td v-if="isAdmin">
                      <div class="cell-actions">
                        <button
                          v-if="canRemove(member)"
                          type="button"
                          class="danger-link"
                          @click="askRemove(member)"
                        >
                          移除
                        </button>
                        <span
                          v-else
                          class="action-hint"
                          title="不能移除自己"
                        >
                          <Icon name="lock" :size="12" />
                          自己
                        </span>
                      </div>
                    </td>
                  </tr>
                </tbody>
              </table>
            </div>
            <p class="table-hint">左右滑动查看完整成员表</p>

            <div v-if="members.length <= 1" class="members-invite-note">
              <span class="members-invite-note__art accent-teal">
                <Icon name="users" :size="18" />
              </span>
              <div>
                <p class="members-invite-note__title">把家人邀请进来</p>
                <p class="members-invite-note__copy">
                  复制右侧的邀请码，或让家人扫描二维码注册。
                </p>
              </div>
            </div>
          </div>
        </section>

        <div class="family-side">
        <section class="card family-info-card">
          <p class="card-label">家庭名称</p>
          <p class="family-info__name">{{ family.family?.name ?? '家庭' }}</p>
          <p class="family-info__meta">
            {{ members.length }} 位成员 · {{ onlineCount }} 人在线
          </p>
        </section>

        <aside
          v-if="isAdmin"
          ref="inviteCard"
          class="invite-card"
          :class="{ 'is-expired': inviteExpired }"
          aria-label="邀请家人"
        >
          <div class="invite-head">
            <span class="invite-label">邀请码</span>
            <span class="invite-badge" :class="{ 'is-expired': inviteExpired }">
              {{ inviteExpired ? '已过期' : '24H' }}
            </span>
          </div>

          <div class="invite-code-row">
            <span class="invite-code__value">{{ inviteCode || '······' }}</span>
            <button
              type="button"
              class="invite-copy"
              :disabled="!inviteCode || inviteExpired"
              @click="copyInviteCode"
            >
              <Icon name="copy" :size="13" />
              复制
            </button>
          </div>

          <div class="qr-row">
            <div class="qr-frame" v-html="qrSvg" />
            <div class="qr-copy">
              <p class="qr-copy__title">家人在 App 扫码加入</p>
              <p class="qr-copy__meta">{{ inviteExpiryText }}</p>
              <button type="button" class="qr-copy__link" @click="openReset">
                重置邀请码
              </button>
            </div>
          </div>

          <p v-if="inviteExpired" class="invite-expired" role="alert">
            <Icon name="alert" :size="14" />
            <span>邀请码已过期，请重置后分享。</span>
          </p>
        </aside>

        <aside v-else ref="inviteCard" class="invite-card invite-card--restricted" aria-label="邀请权限提示">
          <span class="restricted-orb accent-rose">
            <Icon name="lock" :size="22" />
          </span>
          <p class="micro">INVITE · 权限受限</p>
          <h2 class="invite-card__title">邀请码仅管理员可见</h2>
          <p class="invite-card__copy">
            请让管理员在家庭页复制邀请码或分享二维码。你仍可以查看成员与家庭位置。
          </p>
          <div class="restricted-note">
            <Icon name="shield" :size="15" />
            <span>管理员可以移除成员、重置邀请码，并管理家庭设置。</span>
          </div>
        </aside>

        <section class="card danger-mini">
          <p class="danger-mini__title">危险区</p>
          <RouterLink to="/settings" class="danger-mini__row">
            <span class="danger-mini__label">
              <Icon name="logout" :size="14" />
              退出家庭
            </span>
            <Icon name="chevron-right" :size="14" />
          </RouterLink>
        </section>
        </div>
      </div>

    </template>

    <ConfirmDialog
      :open="resetOpen"
      title="重置邀请码？"
      description="旧邀请码会立即失效，新邀请码 24 小时内有效。已经加入的家人不受影响。"
      confirm-label="重置邀请码"
      :busy="resetBusy"
      @cancel="resetOpen = false"
      @confirm="resetInvite"
    />

    <ConfirmDialog
      :open="Boolean(removeTarget)"
      title="移除成员？"
      :description="`移除后，${removeTarget?.nickname ?? '该成员'}将立即失去家庭位置与历史轨迹的访问权限，其设备也会解绑。`"
      confirm-label="移除成员"
      tone="danger"
      :busy="removeBusy"
      @cancel="removeTarget = null"
      @confirm="confirmRemove"
    >
      <p v-if="removeError" class="field__error" role="alert" style="margin-top: 0">
        {{ removeError }}
      </p>
    </ConfirmDialog>
    </div>
  </div>
</template>

<style scoped>
.family-side {
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

.family-info__name {
  margin-top: 6px;
  font-size: 24px;
  line-height: 1.2;
  letter-spacing: -0.02em;
  font-weight: 500;
}

.family-info__meta {
  margin-top: 4px;
  color: var(--ink-muted);
  font-size: 10.5px;
}

.danger-mini {
  background: var(--red);
  box-shadow: none;
}

.danger-mini__title {
  color: var(--red-ink);
  font-size: 10.5px;
  font-weight: 600;
  letter-spacing: 0.1em;
  text-transform: uppercase;
}

.danger-mini__row {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  margin-top: 10px;
  color: var(--red-ink);
  font-size: 12.5px;
  font-weight: 600;
}

.danger-mini__label {
  display: inline-flex;
  align-items: center;
  gap: 7px;
}
.family-page {
  display: flex;
  flex-direction: column;
}

.family-head {
  align-items: center;
  margin-bottom: 24px;
}

.page-title {
  font-size: 48px;
  line-height: 1.12;
  letter-spacing: -0.028em;
  font-weight: 500;
}

.family-head .page-sub {
  margin-top: 8px;
  font-size: 13px;
}

.family-grid {
  display: grid;
  grid-template-columns: minmax(0, 1fr) 380px;
  gap: 28px;
  align-items: start;
}

.members-card {
  min-width: 0;
}

.members-title {
  margin-top: 6px;
}

.members-body {
  padding: 18px 0 0;
}

.family-table {
  --table-min: 660px;
}

.family-table thead th:first-child,
.family-table tbody td:first-child {
  padding-left: 28px;
}

.family-table thead th:last-child,
.family-table tbody td:last-child {
  padding-right: 28px;
}

.cell-aligned-right {
  text-align: right;
}

.member-cell {
  display: flex;
  align-items: center;
  gap: 10px;
  min-width: 0;
}

.member-cell__avatar {
  position: relative;
  flex: 0 0 auto;
}

.member-cell__dot {
  position: absolute;
  right: -1px;
  bottom: 0;
  width: 9px;
  height: 9px;
  border: 2px solid #fff;
}

.member-cell__copy {
  min-width: 0;
}

.self-tag {
  display: inline-flex;
  padding: 1px 4px;
  margin-left: 3px;
  border-radius: 4px;
  background: var(--blue-soft);
  color: var(--blue-pressed);
  font-size: 9px;
  font-weight: 600;
  vertical-align: 1px;
}

.member-cell .cell-strong {
  display: flex;
  align-items: center;
}

.sharing-state {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  font-size: 13px;
  white-space: nowrap;
}

.sharing-state .status-dot {
  width: 6px;
  height: 6px;
  box-shadow: none;
}

.online-text {
  color: var(--success);
  font-weight: 500;
}

.action-hint {
  display: inline-flex;
  align-items: center;
  gap: 4px;
  color: var(--ink-muted);
  font-size: 11.5px;
  white-space: nowrap;
}

.members-invite-note {
  display: flex;
  align-items: flex-start;
  gap: 12px;
  margin: 8px 24px 24px;
  padding: 14px;
  border-radius: var(--r-card);
  background: var(--surface-soft);
}

.members-invite-note__art {
  display: grid;
  place-items: center;
  width: 34px;
  height: 34px;
  border-radius: 10px;
  flex: 0 0 auto;
}

.members-invite-note__title {
  font-size: 13.5px;
  font-weight: 600;
}

.members-invite-note__copy {
  margin-top: 2px;
  color: var(--ink-muted);
  font-size: 12px;
  line-height: 1.5;
}

/* ---- Invite card -------------------------------------------------------- */

.invite-card {
  padding: 20px;
  border-radius: 16px;
  background: var(--teal);
  color: var(--teal-ink);
}

.invite-card__head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
}

.invite-card .micro {
  color: rgba(15, 95, 92, 0.66);
}

.invite-card.is-expired .invite-code__value,
.invite-card.is-expired .qr-frame {
  opacity: 0.5;
}

.invite-card__badge.is-expired {
  background: var(--red);
  color: var(--red-ink);
}

.invite-expired {
  display: flex;
  align-items: flex-start;
  gap: 8px;
  margin-top: 10px;
  padding: 10px 12px;
  border-radius: 9px;
  background: rgba(251, 212, 212, 0.7);
  color: var(--red-ink);
  font-size: 11.5px;
  line-height: 1.55;
}

.invite-expiry.is-expired {
  color: var(--red-ink);
  font-weight: 600;
}

.invite-card__badge {
  display: inline-flex;
  align-items: center;
  height: 22px;
  padding: 0 8px;
  border-radius: 6px;
  background: rgba(255, 255, 255, 0.55);
  color: var(--orange-ink);
  font-size: 9px;
  font-weight: 700;
  letter-spacing: 0.08em;
}

.invite-card__title {
  margin-top: 8px;
  font-size: 26px;
  line-height: 1.2;
  letter-spacing: -0.025em;
  font-weight: 500;
  color: var(--orange-ink);
}

.invite-card__copy {
  margin-top: 8px;
  font-size: 12.5px;
  line-height: 1.6;
  color: rgba(90, 48, 10, 0.78);
}

.invite-code {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 10px;
  margin-top: 18px;
  padding: 12px 12px 12px 16px;
  border-radius: 12px;
  background: #fff;
}

.invite-code__value {
  font-size: 30px;
  line-height: 1;
  font-weight: 600;
  letter-spacing: 0.18em;
  color: var(--ink);
  font-variant-numeric: tabular-nums;
}

.invite-code__copy {
  display: grid;
  place-items: center;
  width: 34px;
  height: 34px;
  flex: 0 0 auto;
  border-radius: 50%;
  color: var(--slate);
  background: var(--surface-soft);
  transition:
    background-color 0.14s,
    color 0.14s;
}

.invite-code__copy:hover:not(:disabled) {
  background: var(--blue-soft);
  color: var(--blue-pressed);
}

.invite-expiry {
  display: flex;
  align-items: center;
  gap: 5px;
  margin-top: 9px;
  font-size: 10.5px;
  color: rgba(122, 67, 17, 0.72);
  font-variant-numeric: tabular-nums;
}

.qr-frame {
  width: 108px;
  height: 108px;
  margin: 14px auto 0;
  padding: 8px;
  border-radius: 12px;
  background: #fff;
}

.qr-frame :deep(svg) {
  width: 100%;
  height: 100%;
  display: block;
}

.qr-frame :deep(path) {
  fill: #1c1c1e;
}

.qr-caption {
  margin-top: 8px;
  text-align: center;
  font-size: 10px;
  color: rgba(122, 67, 17, 0.66);
}

.invite-card__actions {
  margin-top: 18px;
  padding-top: 16px;
  border-top: 1px solid rgba(122, 67, 17, 0.14);
}

.invite-copy-btn {
  background: #fff;
  border-color: transparent;
  color: var(--orange-ink);
}

.invite-copy-btn:hover:not(:disabled) {
  background: rgba(255, 255, 255, 0.78);
}

.invite-card__links {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  margin-top: 10px;
}

.invite-card__links .link-btn {
  color: var(--orange-ink);
  font-size: 12px;
}

.invite-card__links .link-btn:hover {
  color: #542c08;
}

.invite-reset {
  opacity: 0.8;
}

.invite-card--restricted {
  display: flex;
  flex-direction: column;
  align-items: flex-start;
  gap: 6px;
  background: var(--white);
  box-shadow: var(--ring-shadow);
}

.restricted-orb {
  display: grid;
  place-items: center;
  width: 48px;
  height: 48px;
  margin-bottom: 14px;
  border-radius: 14px;
}

.invite-card--restricted .micro {
  color: var(--ink-muted);
}

.invite-card--restricted .invite-card__title {
  color: var(--ink);
  font-size: 24px;
}

.invite-card--restricted .invite-card__copy {
  color: var(--slate);
  font-size: 13px;
}

.restricted-note {
  display: flex;
  align-items: flex-start;
  gap: 8px;
  margin-top: 14px;
  padding: 12px;
  width: 100%;
  border-radius: 10px;
  background: var(--surface-soft);
  color: var(--ink-muted);
  font-size: 11.5px;
  line-height: 1.55;
}

.lock-note {
  display: inline-flex;
  align-items: center;
  gap: 5px;
  color: var(--ink-muted);
  font-size: 11.5px;
  white-space: nowrap;
}

.family-footer-note {
  margin-top: 24px;
  color: var(--ink-muted);
  font-size: 12px;
  line-height: 1.6;
}

.family-footer-note .link-btn {
  font-size: 12px;
}

/* ---- Loading skeletons -------------------------------------------------- */

.skeleton-line {
  height: 10px;
  border-radius: 4px;
}

.skeleton-line--short {
  width: 30%;
}

.skeleton-line--thin {
  height: 8px;
}

.skeleton-title {
  width: 62%;
  height: 24px;
  border-radius: 6px;
  margin-top: 12px;
}

.skeleton-code {
  height: 54px;
  border-radius: 12px;
  margin-top: 18px;
}

.skeleton-qr {
  width: 164px;
  height: 164px;
  border-radius: 12px;
  margin: 18px auto 0;
}

.member-skeleton-row {
  display: flex;
  align-items: center;
  gap: 12px;
  padding: 12px 28px;
}

.member-skeleton-row__avatar {
  width: 34px;
  height: 34px;
  border-radius: 50%;
  flex: 0 0 auto;
}

.member-skeleton-row__lines {
  display: flex;
  flex-direction: column;
  gap: 7px;
  flex: 1;
}

.invite-card--loading {
  display: flex;
  flex-direction: column;
  gap: 8px;
  background: var(--white);
}

@media (max-width: 1100px) {
  .family-grid {
    grid-template-columns: minmax(0, 1fr) 310px;
    gap: 18px;
  }

  .invite-card {
    padding: 22px;
  }

  .invite-code__value {
    font-size: 26px;
  }
}

@media (max-width: 900px) {
  .family-grid {
    grid-template-columns: 1fr;
  }

  .invite-card {
    position: static;
  }

  .family-page {
    padding-bottom: 48px;
  }

  .qr-frame {
    margin-left: 0;
  }
}

@media (max-width: 600px) {
  .family-grid {
    gap: 14px;
  }

  .invite-card {
    padding: 20px;
  }

  .invite-code__value {
    font-size: 26px;
    letter-spacing: 0.14em;
  }

  .family-table thead th:first-child,
  .family-table tbody td:first-child {
    padding-left: 20px;
  }

  .family-table thead th:last-child,
  .family-table tbody td:last-child {
    padding-right: 20px;
  }

  .members-invite-note {
    margin: 8px 16px 20px;
  }
}

/* Compact invite card (design W04) */
.invite-card {
  display: flex;
  flex-direction: column;
  gap: 14px;
}

.invite-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
}

.invite-label {
  font-size: 10.5px;
  font-weight: 600;
  letter-spacing: 0.1em;
  text-transform: uppercase;
  color: rgba(15, 95, 92, 0.72);
}

.invite-badge {
  display: inline-flex;
  align-items: center;
  height: 20px;
  padding: 0 8px;
  border-radius: 6px;
  background: rgba(255, 255, 255, 0.72);
  color: var(--teal-ink);
  font-size: 10px;
  font-weight: 600;
}

.invite-badge.is-expired {
  background: var(--red);
  color: var(--red-ink);
}

.invite-code-row {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
}

.invite-code__value {
  font-size: 30px;
  line-height: 1.1;
  font-weight: 600;
  letter-spacing: 0.02em;
  color: var(--teal-ink);
  font-variant-numeric: tabular-nums;
}

.invite-copy {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  height: 28px;
  padding: 0 12px;
  border-radius: 7px;
  background: var(--white);
  color: var(--teal-ink);
  font-size: 11px;
  font-weight: 600;
  flex: 0 0 auto;
}

.invite-copy:hover:not(:disabled) {
  background: rgba(255, 255, 255, 0.8);
}

.qr-row {
  display: flex;
  align-items: center;
  gap: 14px;
}

.invite-card .qr-frame {
  width: 88px;
  height: 88px;
  margin: 0;
  padding: 6px;
  border-radius: 10px;
  background: var(--white);
  flex: 0 0 auto;
}

.qr-copy {
  display: flex;
  flex-direction: column;
  gap: 4px;
  min-width: 0;
}

.qr-copy__title {
  color: var(--teal-ink);
  font-size: 11px;
  font-weight: 600;
}

.qr-copy__meta {
  color: rgba(15, 95, 92, 0.7);
  font-size: 10px;
}

.qr-copy__link {
  align-self: flex-start;
  margin-top: 2px;
  color: var(--teal-ink);
  font-size: 10px;
  font-weight: 600;
  text-decoration: underline;
  text-underline-offset: 2px;
}
</style>
