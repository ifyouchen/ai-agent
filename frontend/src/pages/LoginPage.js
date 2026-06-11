import { defineComponent, h, onMounted, reactive, ref } from 'vue';
import { getToken, login, register } from '../services/api.js';

const LogoMark = defineComponent({
  setup() {
    return () => h('svg', { viewBox: '0 0 24 24', xmlns: 'http://www.w3.org/2000/svg' }, [
      h('path', { d: 'M12 2C6.48 2 2 6.48 2 12s4.48 10 10 10 10-4.48 10-10S17.52 2 12 2zm-1 14H9V8h2v8zm4 0h-2V8h2v8z' })
    ]);
  }
});

export default defineComponent({
  name: 'LoginPage',
  components: { LogoMark },
  setup() {
    const features = [
      '混合 RAG 知识库，精准引用溯源',
      'ReAct 多步推理，复杂任务自动拆解',
      'DeepSeek / Claude 多模型热切换',
      'Token 成本追踪与智能告警'
    ];

    const activeTab = ref('login');
    const loading = ref(false);
    const globalError = ref('');
    const fieldErrors = reactive({});
    const loginForm = reactive({ username: '', password: '' });
    const registerForm = reactive({ username: '', password: '', confirm: '' });

    onMounted(() => {
      if (getToken()) location.replace('/index.html');
    });

    function switchTab(tab) {
      activeTab.value = tab;
      clearErrors();
    }

    async function submitLogin() {
      clearErrors();
      if (!validateLogin()) return;
      loading.value = true;
      try {
        await login({ username: loginForm.username, password: loginForm.password });
        location.replace('/index.html');
      } catch (error) {
        globalError.value = error.message || '用户名或密码错误';
      } finally {
        loading.value = false;
      }
    }

    async function submitRegister() {
      clearErrors();
      if (!validateRegister()) return;
      loading.value = true;
      try {
        await register({ username: registerForm.username, password: registerForm.password });
        location.replace('/index.html');
      } catch (error) {
        globalError.value = error.message || '注册失败，用户名可能已存在';
      } finally {
        loading.value = false;
      }
    }

    function validateLogin() {
      if (!loginForm.username) fieldErrors.loginUsername = '请输入用户名';
      if (!loginForm.password) fieldErrors.loginPassword = '请输入密码';
      return !Object.keys(fieldErrors).length;
    }

    function validateRegister() {
      if (!registerForm.username || !/^[a-zA-Z0-9]{4,32}$/.test(registerForm.username)) {
        fieldErrors.regUsername = '用户名需为 4-32 位字母或数字';
      }
      if (!registerForm.password || registerForm.password.length < 6) {
        fieldErrors.regPassword = '密码不能少于 6 位';
      }
      if (registerForm.password !== registerForm.confirm) {
        fieldErrors.regConfirm = '两次密码不一致';
      }
      return !Object.keys(fieldErrors).length;
    }

    function clearErrors() {
      globalError.value = '';
      Object.keys(fieldErrors).forEach((key) => delete fieldErrors[key]);
    }

    return {
      activeTab,
      features,
      fieldErrors,
      globalError,
      loading,
      loginForm,
      registerForm,
      submitLogin,
      submitRegister,
      switchTab
    };
  }
});
