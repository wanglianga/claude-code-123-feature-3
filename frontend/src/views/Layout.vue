<template>
  <el-container class="layout">
    <el-aside width="220px" class="aside">
      <div class="logo">
        <el-icon :size="22"><Scale /></el-icon>
        <span>诉前调解平台</span>
      </div>
      <el-menu :default-active="$route.path" router background-color="#1d3c6e" text-color="#c5d6ef"
               active-text-color="#fff">
        <el-menu-item index="/todo"><el-icon><Bell /></el-icon><span>工作台</span></el-menu-item>
        <el-menu-item index="/cases"><el-icon><Files /></el-icon><span>案件管理</span></el-menu-item>
        <el-menu-item index="/cases/new"><el-icon><EditPen /></el-icon><span>新建调解申请</span></el-menu-item>
        <el-menu-item v-if="auth.is('WINDOW','ADMIN','ASSISTANT')" index="/import">
          <el-icon><Upload /></el-icon><span>批量导入</span>
        </el-menu-item>
        <el-menu-item v-if="auth.is('WINDOW','ADMIN')" index="/mediators">
          <el-icon><UserFilled /></el-icon><span>调解员名册</span>
        </el-menu-item>
        <el-menu-item v-if="auth.is('WINDOW','ADMIN','MEDIATOR')" index="/mediator-performance">
          <el-icon><Trophy /></el-icon><span>调解员绩效</span>
        </el-menu-item>
        <el-menu-item index="/dashboard"><el-icon><DataAnalysis /></el-icon><span>统计看板</span></el-menu-item>
      </el-menu>
    </el-aside>
    <el-container>
      <el-header class="header">
        <div class="crumb">{{ $route.meta.title }}</div>
        <div class="user-area">
          <el-tag :type="roleType" effect="dark" size="small">{{ auth.user?.roleLabel }}</el-tag>
          <span class="uname">{{ auth.user?.realName }}</span>
          <el-button link type="primary" @click="logout">退出登录</el-button>
        </div>
      </el-header>
      <el-main class="main">
        <router-view />
      </el-main>
    </el-container>
  </el-container>
</template>

<script setup>
import { computed } from 'vue'
import { useRouter } from 'vue-router'
import { auth } from '../store.js'
import api from '../api.js'

const router = useRouter()
const roleType = computed(() => ({
  WINDOW: 'danger', MEDIATOR: 'success', PARTY: 'primary',
  ASSISTANT: 'warning', EXECUTION: 'info', ADMIN: ''
}[auth.role] || ''))

const logout = async () => {
  try { await api.post('/auth/logout') } catch {}
  auth.clear()
  router.push('/login')
}
</script>

<style scoped>
.layout { height: 100vh; }
.aside { background: #1d3c6e; }
.logo {
  height: 60px; display: flex; align-items: center; justify-content: center; gap: 8px;
  color: #fff; font-size: 17px; font-weight: 700;
  border-bottom: 1px solid rgba(255,255,255,.12);
}
.aside :deep(.el-menu) { border-right: none; }
.header {
  background: #fff; display: flex; align-items: center; justify-content: space-between;
  border-bottom: 1px solid #eee;
}
.crumb { font-size: 16px; font-weight: 600; color: #303133; }
.user-area { display: flex; align-items: center; gap: 10px; }
.uname { color: #606266; font-size: 14px; }
.main { background: #f2f4f8; padding: 16px; }
</style>
