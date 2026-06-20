/**
 * Vue Router 路由配置
 *
 * 路由结构：
 *   /           → 重定向到 /chat
 *   /chat       → 对话页
 *   /kb         → 知识库页
 *   /org        → 组织管理页
 *   /monitor    → 监控页（仅 Admin 可见）
 *   /profile    → 用户资料页
 *
 * 路由守卫：
 *   - 未登录 → 跳转 /login.html
 *   - 非 Admin 访问 /monitor → 重定向到 /chat
 */
import { createRouter, createWebHashHistory } from 'vue-router';
import { getToken, getUser } from '../services/api.js';

// 懒加载各视图组件
const ChatView         = () => import('../views/ChatView.vue');
const KnowledgeBaseView = () => import('../views/KnowledgeBaseView.vue');
const OrgView          = () => import('../views/OrgView.vue');
const MonitorView      = () => import('../views/MonitorView.vue');
// /invite/:token 复用 OrgView，组件内读取 token 调用接受邀请 API
const AdminView        = () => import('../views/AdminView.vue');
const ProfileView      = () => import('../views/ProfileView.vue');
const MainLayout       = () => import('../components/layout/MainLayout.vue');
const ShareView        = () => import('../views/ShareView.vue');
const CreationView     = () => import('../views/CreationView.vue');

const routes = [
  { path: '/share/:shareId', component: ShareView, meta: { public: true, title: '会话分享' } },
  {
    path: '/',
    component: MainLayout,
    children: [
      { path: '',        redirect: '/chat' },
      { path: 'chat',    component: ChatView,          meta: { title: '对话' } },
      { path: 'creation', component: CreationView, meta: { title: '创作', creationMode: 'home' } },
      { path: 'creation/projects', component: CreationView, meta: { title: '作品库', creationMode: 'projects' } },
      { path: 'creation/projects/:id/editor', component: CreationView, meta: { title: '作品编辑器', creationMode: 'editor' } },
      { path: 'creation/rewrite/:taskId', component: CreationView, meta: { title: '改写对照', creationMode: 'rewrite' } },
      { path: 'creation/scripts/:draftId', component: CreationView, meta: { title: '短剧改编', creationMode: 'script' } },
      { path: 'creation/scripts/:draftId/export', component: CreationView, meta: { title: '导出预览', creationMode: 'export' } },
      { path: 'kb',      component: KnowledgeBaseView, meta: { title: '知识库' } },
      { path: 'org',           component: OrgView,           meta: { title: '组织管理' } },
      { path: 'invite/:token', component: OrgView,           meta: { title: '组织邀请' } },
      { path: 'monitor',       component: MonitorView,       meta: { title: '监控',     requiresAdmin: true } },
      { path: 'admin',   component: AdminView,         meta: { title: '用户管理', requiresAdmin: true } },
      { path: 'profile', component: ProfileView,       meta: { title: '个人资料' } },
    ],
  },
];

const router = createRouter({
  history: createWebHashHistory(),
  routes,
});

// 全局前置守卫
router.beforeEach((to) => {
  if (to.meta.public) return true;

  // 未登录 → 跳转登录页
  if (!getToken()) {
    const redirect = `/index.html#${to.fullPath}`;
    location.replace(`/login.html?redirect=${encodeURIComponent(redirect)}`);
    return false;
  }

  // 非 Admin 访问 /monitor → 重定向到 /chat
  if (to.meta.requiresAdmin) {
    const user  = getUser();
    const roles = user?.roles || [];
    if (!roles.includes('ROLE_ADMIN')) {
      return { path: '/chat' };
    }
  }

  return true;
});

export default router;
