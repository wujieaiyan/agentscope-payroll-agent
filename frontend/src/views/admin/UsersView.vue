<script setup>
import { onMounted, reactive, ref } from 'vue';
import { api } from '../../api/http';
import { toast } from '../../stores/toast';

const tab = ref('users');
const users = ref([]);
const roles = ref([]);
const departments = ref([]);
const permissions = ref([]);
const loading = ref(false);

const userModal = ref(false);
const editingUser = ref(false);
const userForm = reactive({
  id: null,
  username: '',
  display_name: '',
  password: '',
  role_code: '',
  department_id: '',
  active: true,
});

const roleModal = ref(false);
const editingRole = ref(false);
const roleForm = reactive({ code: '', name: '', description: '', permissions: [] });

const deptModal = ref(false);
const editingDept = ref(false);
const deptForm = reactive({ id: null, name: '', description: '', active: true });

function formatTime(value) {
  return value ? String(value).replace('T', ' ').slice(0, 16) : '—';
}

async function load() {
  loading.value = true;
  try {
    const [userList, roleList, deptList, permissionList] = await Promise.all([
      api('/api/admin/users'),
      api('/api/admin/roles'),
      api('/api/admin/departments'),
      api('/api/admin/permissions'),
    ]);
    users.value = userList || [];
    roles.value = roleList || [];
    departments.value = deptList || [];
    permissions.value = permissionList || [];
  } catch (error) {
    toast(error.message, 'error');
  } finally {
    loading.value = false;
  }
}

function createUser() {
  editingUser.value = false;
  Object.assign(userForm, {
    id: null,
    username: '',
    display_name: '',
    password: '',
    role_code: roles.value[0]?.code || '',
    department_id: departments.value[0]?.id ?? '',
    active: true,
  });
  userModal.value = true;
}

function editUser(row) {
  editingUser.value = true;
  Object.assign(userForm, {
    id: row.id,
    username: row.username,
    display_name: row.display_name,
    password: '',
    role_code: row.role_code,
    department_id: row.department_id ?? '',
    active: row.active,
  });
  userModal.value = true;
}

async function saveUser() {
  try {
    if (editingUser.value) {
      await api(`/api/admin/users/${userForm.id}`, {
        method: 'PUT',
        body: {
          display_name: userForm.display_name,
          role_code: userForm.role_code,
          department_id: userForm.department_id === '' ? null : Number(userForm.department_id),
          active: userForm.active,
        },
      });
    } else {
      await api('/api/admin/users', {
        method: 'POST',
        body: {
          username: userForm.username.trim(),
          display_name: userForm.display_name.trim(),
          password: userForm.password,
          role_code: userForm.role_code,
          department_id: userForm.department_id === '' ? null : Number(userForm.department_id),
        },
      });
    }
    toast('用户已保存', 'success');
    userModal.value = false;
    await load();
  } catch (error) {
    toast(error.message, 'error');
  }
}

async function resetPassword(row) {
  const password = window.prompt(`为「${row.display_name}」设置新密码（至少 8 位）`);
  if (!password) return;
  try {
    await api(`/api/admin/users/${row.id}/password`, {
      method: 'POST',
      body: { password },
    });
    toast('密码已重置', 'success');
  } catch (error) {
    toast(error.message, 'error');
  }
}

function createRole() {
  editingRole.value = false;
  Object.assign(roleForm, { code: '', name: '', description: '', permissions: [] });
  roleModal.value = true;
}

function editRole(role) {
  editingRole.value = true;
  Object.assign(roleForm, {
    code: role.code,
    name: role.name,
    description: role.description || '',
    permissions: [...(role.permissions || [])],
  });
  roleModal.value = true;
}

function togglePermission(code) {
  const index = roleForm.permissions.indexOf(code);
  if (index >= 0) roleForm.permissions.splice(index, 1);
  else roleForm.permissions.push(code);
}

async function saveRole() {
  try {
    const body = {
      code: roleForm.code.trim(),
      name: roleForm.name.trim(),
      description: roleForm.description.trim(),
      permissions: roleForm.permissions,
    };
    if (editingRole.value) {
      await api(`/api/admin/roles/${encodeURIComponent(body.code)}`, { method: 'PUT', body });
    } else {
      await api('/api/admin/roles', { method: 'POST', body });
    }
    toast('角色已保存', 'success');
    roleModal.value = false;
    await load();
  } catch (error) {
    toast(error.message, 'error');
  }
}

async function removeRole(role) {
  if (!window.confirm(`删除角色「${role.name}」？`)) return;
  try {
    await api(`/api/admin/roles/${encodeURIComponent(role.code)}`, { method: 'DELETE' });
    toast('角色已删除', 'success');
    await load();
  } catch (error) {
    toast(error.message, 'error');
  }
}

function createDept() {
  editingDept.value = false;
  Object.assign(deptForm, { id: null, name: '', description: '', active: true });
  deptModal.value = true;
}

function editDept(row) {
  editingDept.value = true;
  Object.assign(deptForm, {
    id: row.id,
    name: row.name,
    description: row.description || '',
    active: row.active,
  });
  deptModal.value = true;
}

async function saveDept() {
  try {
    const body = {
      name: deptForm.name.trim(),
      description: deptForm.description.trim(),
      active: deptForm.active,
    };
    if (editingDept.value) {
      await api(`/api/admin/departments/${deptForm.id}`, { method: 'PUT', body });
    } else {
      await api('/api/admin/departments', { method: 'POST', body });
    }
    toast('部门已保存', 'success');
    deptModal.value = false;
    await load();
  } catch (error) {
    toast(error.message, 'error');
  }
}

async function removeDept(row) {
  if (!window.confirm(`删除部门「${row.name}」？有员工或用户引用时会被拒绝。`)) return;
  try {
    await api(`/api/admin/departments/${row.id}`, { method: 'DELETE' });
    toast('部门已删除', 'success');
    await load();
  } catch (error) {
    toast(error.message, 'error');
  }
}

onMounted(load);
</script>

<template>
  <div class="page">
    <div class="page-head">
      <div>
        <h2>用户与角色</h2>
        <p>账号按角色授予权限；员工档案与登录账号分离，部门同时用于核算范围与知识库可见性。</p>
      </div>
      <div class="toolbar">
        <button class="ghost" :class="{ primary: tab === 'users' }" @click="tab = 'users'">用户</button>
        <button class="ghost" :class="{ primary: tab === 'roles' }" @click="tab = 'roles'">角色</button>
        <button class="ghost" :class="{ primary: tab === 'departments' }" @click="tab = 'departments'">部门</button>
      </div>
    </div>

    <template v-if="tab === 'users'">
      <div class="toolbar">
        <span class="hint">{{ users.length }} 个账号</span>
        <span class="spacer"></span>
        <button class="primary" @click="createUser">新增用户</button>
      </div>
      <div class="table-wrap">
        <table class="table">
          <thead>
            <tr>
              <th>ID</th>
              <th>用户名</th>
              <th>显示名</th>
              <th>角色</th>
              <th>部门</th>
              <th>状态</th>
              <th>最近登录</th>
              <th>操作</th>
            </tr>
          </thead>
          <tbody>
            <tr v-for="row in users" :key="row.id">
              <td class="mono">{{ row.id }}</td>
              <td>{{ row.username }}</td>
              <td>{{ row.display_name }}</td>
              <td>{{ row.role_name || row.role_code }}</td>
              <td>{{ row.department_name || '—' }}</td>
              <td>
                <span class="badge" :class="row.active ? 'green' : 'red'">
                  {{ row.active ? '启用' : '停用' }}
                </span>
              </td>
              <td>{{ formatTime(row.last_login_at) }}</td>
              <td>
                <div class="cell-actions">
                  <button class="subtle sm" @click="editUser(row)">编辑</button>
                  <button class="subtle sm" @click="resetPassword(row)">重置密码</button>
                </div>
              </td>
            </tr>
            <tr v-if="!users.length">
              <td colspan="8" class="empty">{{ loading ? '加载中…' : '暂无用户' }}</td>
            </tr>
          </tbody>
        </table>
      </div>
    </template>

    <template v-else-if="tab === 'roles'">
      <div class="toolbar">
        <span class="hint">{{ roles.length }} 个角色，内置角色不可删除</span>
        <span class="spacer"></span>
        <button class="primary" @click="createRole">新增角色</button>
      </div>
      <div class="table-wrap">
        <table class="table">
          <thead>
            <tr>
              <th>编码</th>
              <th>名称</th>
              <th>说明</th>
              <th>权限</th>
              <th>用户数</th>
              <th>类型</th>
              <th>操作</th>
            </tr>
          </thead>
          <tbody>
            <tr v-for="role in roles" :key="role.code">
              <td class="mono">{{ role.code }}</td>
              <td>{{ role.name }}</td>
              <td class="muted">{{ role.description }}</td>
              <td class="muted">
                {{ (role.permissions || []).length }} 项
              </td>
              <td>{{ role.user_count }}</td>
              <td>
                <span class="badge" :class="role.builtin ? 'blue' : 'green'">
                  {{ role.builtin ? '内置' : '自定义' }}
                </span>
              </td>
              <td>
                <div class="cell-actions">
                  <button class="subtle sm" @click="editRole(role)">查看 / 编辑</button>
                  <button v-if="!role.builtin" class="subtle sm" @click="removeRole(role)">删除</button>
                </div>
              </td>
            </tr>
          </tbody>
        </table>
      </div>
    </template>

    <template v-else>
      <div class="toolbar">
        <span class="hint">{{ departments.length }} 个部门</span>
        <span class="spacer"></span>
        <button class="primary" @click="createDept">新增部门</button>
      </div>
      <div class="table-wrap">
        <table class="table">
          <thead>
            <tr>
              <th>ID</th>
              <th>名称</th>
              <th>说明</th>
              <th>员工数</th>
              <th>用户数</th>
              <th>状态</th>
              <th>操作</th>
            </tr>
          </thead>
          <tbody>
            <tr v-for="row in departments" :key="row.id">
              <td class="mono">{{ row.id }}</td>
              <td>{{ row.name }}</td>
              <td class="muted">{{ row.description }}</td>
              <td>{{ row.employee_count }}</td>
              <td>{{ row.user_count }}</td>
              <td>
                <span class="badge" :class="row.active ? 'green' : 'red'">
                  {{ row.active ? '启用' : '停用' }}
                </span>
              </td>
              <td>
                <div class="cell-actions">
                  <button class="subtle sm" @click="editDept(row)">编辑</button>
                  <button class="subtle sm" @click="removeDept(row)">删除</button>
                </div>
              </td>
            </tr>
          </tbody>
        </table>
      </div>
    </template>

    <div v-if="userModal" class="modal-mask" @click.self="userModal = false">
      <div class="modal" style="width: min(520px, 96vw)">
        <div class="modal-head">
          <h3>{{ editingUser ? `编辑用户 ${userForm.username}` : '新增用户' }}</h3>
          <button class="subtle sm" @click="userModal = false">关闭</button>
        </div>
        <div class="modal-body">
          <div class="form-grid">
            <label class="field">
              用户名
              <input v-model="userForm.username" :disabled="editingUser" placeholder="zhangsan" />
            </label>
            <label class="field">
              显示名
              <input v-model="userForm.display_name" placeholder="张三" />
            </label>
            <label v-if="!editingUser" class="field">
              初始密码（至少 8 位）
              <input v-model="userForm.password" type="password" />
            </label>
            <label class="field">
              角色
              <select v-model="userForm.role_code">
                <option v-for="role in roles" :key="role.code" :value="role.code">{{ role.name }}</option>
              </select>
            </label>
            <label class="field">
              部门
              <select v-model="userForm.department_id">
                <option value="">不指定</option>
                <option v-for="dept in departments" :key="dept.id" :value="dept.id">{{ dept.name }}</option>
              </select>
            </label>
            <label v-if="editingUser" class="field">
              状态
              <select v-model="userForm.active">
                <option :value="true">启用</option>
                <option :value="false">停用</option>
              </select>
            </label>
          </div>
        </div>
        <div class="modal-foot">
          <button class="ghost" @click="userModal = false">取消</button>
          <button class="primary" @click="saveUser">保存</button>
        </div>
      </div>
    </div>

    <div v-if="roleModal" class="modal-mask" @click.self="roleModal = false">
      <div class="modal" style="width: min(720px, 96vw)">
        <div class="modal-head">
          <h3>{{ editingRole ? `编辑角色 ${roleForm.code}` : '新增角色' }}</h3>
          <button class="subtle sm" @click="roleModal = false">关闭</button>
        </div>
        <div class="modal-body">
          <div class="form-grid">
            <label class="field">
              编码
              <input v-model="roleForm.code" :disabled="editingRole" placeholder="hr_viewer" />
            </label>
            <label class="field">
              名称
              <input v-model="roleForm.name" placeholder="HR 只读" />
            </label>
            <label class="field span-2">
              说明
              <input v-model="roleForm.description" placeholder="可查看员工与工资批次" />
            </label>
          </div>
          <div>
            <div class="muted" style="margin-bottom: 6px">权限</div>
            <div class="toolbar">
              <button
                v-for="permission in permissions"
                :key="permission.code"
                class="chip"
                :class="{ 'badge blue': roleForm.permissions.includes(permission.code) }"
                @click="togglePermission(permission.code)"
              >
                {{ permission.label || permission.code }}
              </button>
            </div>
          </div>
        </div>
        <div class="modal-foot">
          <button class="ghost" @click="roleModal = false">取消</button>
          <button class="primary" @click="saveRole">保存</button>
        </div>
      </div>
    </div>

    <div v-if="deptModal" class="modal-mask" @click.self="deptModal = false">
      <div class="modal" style="width: min(460px, 96vw)">
        <div class="modal-head">
          <h3>{{ editingDept ? '编辑部门' : '新增部门' }}</h3>
          <button class="subtle sm" @click="deptModal = false">关闭</button>
        </div>
        <div class="modal-body">
          <label class="field">
            名称
            <input v-model="deptForm.name" placeholder="研发部" />
          </label>
          <label class="field">
            说明
            <input v-model="deptForm.description" placeholder="产品研发" />
          </label>
          <label class="field">
            状态
            <select v-model="deptForm.active">
              <option :value="true">启用</option>
              <option :value="false">停用</option>
            </select>
          </label>
        </div>
        <div class="modal-foot">
          <button class="ghost" @click="deptModal = false">取消</button>
          <button class="primary" @click="saveDept">保存</button>
        </div>
      </div>
    </div>
  </div>
</template>
