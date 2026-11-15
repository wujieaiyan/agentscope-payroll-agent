import { computed } from 'vue'
import { auth } from '../../stores/auth'

/** 以薪酬管理台的会话用户模拟 console 的 useAuthStore 形态。 */
export function useAuthStore() {
  const user = computed(() => {
    const current = auth.user
    if (!current) return null
    return {
      ...current,
      name: current.display_name || current.username,
      deptId: current.department_id ?? null,
      platformAdmin:
        current.role_code === 'ADMIN' ||
        (current.permissions || []).includes('kb:manage'),
      permissions: current.permissions || [],
    }
  })

  return {
    get user() {
      return user.value
    },
    get loggedIn() {
      return Boolean(auth.user)
    },
    get displayName() {
      return user.value ? user.value.name : '未登录'
    },
    get permissions() {
      return user.value ? user.value.permissions : []
    },
    hasPermission(code) {
      const current = user.value
      if (!current) return false
      return Boolean(current.platformAdmin) || current.permissions.includes(code)
    },
  }
}
