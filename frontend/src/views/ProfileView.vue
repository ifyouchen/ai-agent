<template>
  <div class="profile-view">
    <div class="profile-card">
      <!-- 头像区 -->
      <div class="profile-avatar-wrap">
        <Avatar :name="auth.displayName" :size="72" style="width:72px;height:72px;font-size:28px;border-radius:50%;" />
        <div class="profile-names">
          <h2 class="profile-username">{{ auth.user?.username }}</h2>
          <p class="profile-userid">ID：{{ auth.user?.userId }}</p>
          <p class="profile-roles">{{ rolesLabel }}</p>
        </div>
      </div>

      <!-- 资料编辑表单 -->
      <div class="profile-section">
        <h3 class="profile-section-title">基本信息</h3>
        <div class="profile-form">
          <label class="profile-field">
            <span class="profile-label">用户名</span>
            <input type="text" class="profile-input" :value="auth.user?.username" disabled />
          </label>
          <label class="profile-field">
            <span class="profile-label">昵称</span>
            <input v-model.trim="form.nickname" type="text" class="profile-input" placeholder="设置一个昵称（可选）" maxlength="50" />
          </label>
          <label class="profile-field">
            <span class="profile-label">邮箱</span>
            <input v-model.trim="form.email" type="email" class="profile-input" placeholder="your@email.com（可选）" maxlength="100" />
          </label>
        </div>
        <button class="profile-save-btn" type="button" :disabled="saving" @click="saveProfile">
          <svg v-if="saving" class="inline-spinner" viewBox="0 0 24 24" fill="none" width="14" height="14">
            <circle cx="12" cy="12" r="9" stroke="currentColor" stroke-width="2.5" stroke-dasharray="14 50" stroke-linecap="round"/>
          </svg>
          {{ saving ? '保存中…' : '保存资料' }}
        </button>
      </div>

      <!-- 修改密码 -->
      <div class="profile-section">
        <h3 class="profile-section-title">
          修改密码
          <button class="profile-toggle-btn" type="button" @click="pwdVisible = !pwdVisible">
            {{ pwdVisible ? '收起' : '展开' }}
          </button>
        </h3>
        <div v-if="pwdVisible" class="profile-form">
          <label class="profile-field">
            <span class="profile-label">当前密码</span>
            <input v-model="pwd.old" type="password" class="profile-input" placeholder="输入当前密码" />
          </label>
          <label class="profile-field">
            <span class="profile-label">新密码</span>
            <input v-model="pwd.new" type="password" class="profile-input" placeholder="至少 6 位" />
          </label>
          <label class="profile-field">
            <span class="profile-label">确认新密码</span>
            <input v-model="pwd.confirm" type="password" class="profile-input" placeholder="再次输入新密码" />
          </label>
          <button class="profile-save-btn" type="button" :disabled="pwdSaving" @click="changePassword">
            {{ pwdSaving ? '保存中…' : '修改密码' }}
          </button>
        </div>
      </div>
    </div>
  </div>
</template>

<script setup>
import { computed, onMounted, reactive, ref } from 'vue';
import Avatar from '../components/ui/Avatar.vue';
import { useAuthStore } from '../stores/auth.js';
import { useUiStore } from '../stores/ui.js';
import * as api from '../services/api.js';

const auth = useAuthStore();
const ui   = useUiStore();

const form    = reactive({ nickname: '', email: '' });
const saving  = ref(false);
const pwdVisible = ref(false);
const pwdSaving  = ref(false);
const pwd = reactive({ old: '', new: '', confirm: '' });

const rolesLabel = computed(() => {
  const roles = auth.user?.roles || [];
  if (roles.includes('ROLE_ADMIN')) return '管理员';
  return '普通用户';
});

onMounted(() => {
  form.nickname = auth.user?.nickname || '';
  form.email    = auth.user?.email    || '';
});

async function saveProfile() {
  saving.value = true;
  try {
    await auth.updateProfile(form.nickname, form.email);
    ui.showToast('success', '资料已保存');
  } catch (err) {
    ui.showToast('error', err.message || '保存失败');
  } finally {
    saving.value = false;
  }
}

async function changePassword() {
  if (!pwd.old || !pwd.new) { ui.showToast('warning', '请填写当前密码和新密码'); return; }
  if (pwd.new !== pwd.confirm) { ui.showToast('warning', '两次输入的新密码不一致'); return; }
  if (pwd.new.length < 6) { ui.showToast('warning', '新密码长度不能少于 6 位'); return; }
  pwdSaving.value = true;
  try {
    await api.changePassword(pwd.old, pwd.new);
    ui.showToast('success', '密码修改成功，请重新登录');
    setTimeout(() => auth.logout(), 1500);
  } catch (err) {
    ui.showToast('error', err.message || '修改失败');
  } finally {
    pwdSaving.value = false;
  }
}
</script>

<style scoped>
.profile-view {
  padding: 32px;
  max-width: 560px;
}

.profile-card {
  background: #fff;
  border-radius: 16px;
  padding: 28px;
  box-shadow: 0 2px 12px rgba(0,0,0,.06);
  display: flex;
  flex-direction: column;
  gap: 24px;
}

.profile-avatar-wrap {
  display: flex;
  align-items: center;
  gap: 20px;
}

.profile-username {
  font-size: 20px;
  font-weight: 700;
  color: #1A1A1A;
  margin: 0 0 4px;
}

.profile-userid, .profile-roles {
  font-size: 12px;
  color: #888;
  margin: 0;
}

.profile-section-title {
  font-size: 14px;
  font-weight: 600;
  color: #1A1A1A;
  margin: 0 0 14px;
  display: flex;
  align-items: center;
  justify-content: space-between;
}

.profile-form {
  display: flex;
  flex-direction: column;
  gap: 12px;
}

.profile-field {
  display: flex;
  flex-direction: column;
  gap: 4px;
}

.profile-label {
  font-size: 12px;
  font-weight: 500;
  color: #444;
}

.profile-input {
  padding: 8px 12px;
  border: 1.5px solid #EBEBEB;
  border-radius: 8px;
  font-size: 14px;
  color: #1A1A1A;
  outline: none;
  transition: border-color .2s;
}

.profile-input:focus { border-color: var(--primary, #4D6BFE); }
.profile-input:disabled { background: #F7F7F8; color: #888; cursor: not-allowed; }

.profile-save-btn {
  margin-top: 8px;
  padding: 10px 20px;
  background: var(--primary, #4D6BFE);
  color: #fff;
  border: none;
  border-radius: 8px;
  font-size: 14px;
  font-weight: 500;
  cursor: pointer;
  display: flex;
  align-items: center;
  gap: 6px;
  align-self: flex-start;
  transition: opacity .2s;
}

.profile-save-btn:disabled { opacity: .6; cursor: not-allowed; }
.profile-save-btn:hover:not(:disabled) { opacity: .9; }

.profile-toggle-btn {
  font-size: 12px;
  color: var(--primary, #4D6BFE);
  background: none;
  border: none;
  cursor: pointer;
  padding: 0;
}
</style>
