import { ApiError } from '@/api/errors'

const CODE_COPY: Record<string, string> = {
  UNAUTHORIZED: '登录已过期，请重新登录',
  FORBIDDEN: '你没有权限执行这个操作',
  NOT_FOUND: '内容不存在，或你无权查看',
  VALIDATION: '提交的内容有误，请检查后重试',
  CONFLICT: '操作冲突，请刷新后再试',
  ALREADY_IN_FAMILY: '你已经在家庭中了',
  INVITE_INVALID: '邀请码无效，请向家人确认',
  INVITE_EXPIRED: '邀请码已过期，请让管理员重置',
  DEVICE_OFFLINE: '对方当前离线，无法立即定位',
  RATE_LIMITED: '操作太频繁，请稍后再试',
}

/** Friendly Chinese copy for any thrown error. */
export function messageForError(err: unknown, fallback = '操作失败，请稍后重试'): string {
  if (err instanceof ApiError) {
    if (err.code === 'VALIDATION' && err.message) return err.message
    return CODE_COPY[err.code] ?? err.message ?? fallback
  }
  if (err instanceof TypeError) {
    return '无法连接服务器，请检查网络或服务器地址'
  }
  if (err instanceof Error && err.message) return err.message
  return fallback
}

export function isUnauthorized(err: unknown): boolean {
  return err instanceof ApiError && err.status === 401
}
