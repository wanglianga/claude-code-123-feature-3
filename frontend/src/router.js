import { createRouter, createWebHashHistory } from 'vue-router'

const routes = [
  { path: '/login', component: () => import('./views/Login.vue') },
  {
    path: '/',
    component: () => import('./views/Layout.vue'),
    redirect: '/todo',
    children: [
      { path: 'todo', name: 'todo', component: () => import('./views/Workbench.vue'), meta: { title: '工作台' } },
      { path: 'cases', name: 'cases', component: () => import('./views/CaseList.vue'), meta: { title: '案件列表' } },
      { path: 'cases/new', name: 'caseNew', component: () => import('./views/CaseCreate.vue'), meta: { title: '新建调解申请' } },
      { path: 'cases/:id', name: 'caseDetail', component: () => import('./views/CaseDetail.vue'), meta: { title: '案件详情' } },
      { path: 'divert', name: 'divert', component: () => import('./views/DivertSuggestions.vue'), meta: { title: '案件分流建议' } },
      { path: 'mediator-performance', name: 'mediatorPerformance', component: () => import('./views/MediatorPerformance.vue'), meta: { title: '调解员绩效' } },
      { path: 'import', name: 'import', component: () => import('./views/BatchImport.vue'), meta: { title: '批量导入' } },
      { path: 'dashboard', name: 'dashboard', component: () => import('./views/Dashboard.vue'), meta: { title: '统计看板' } },
      { path: 'mediators', name: 'mediators', component: () => import('./views/Mediators.vue'), meta: { title: '调解员名册' } }
    ]
  }
]

const router = createRouter({
  history: createWebHashHistory(),
  routes
})

router.beforeEach((to) => {
  const token = localStorage.getItem('token')
  if (to.path !== '/login' && !token) return '/login'
  if (to.path === '/login' && token) return '/'
})

export default router
