<template>
  <el-card shadow="never" class="mb">
    <template #header>
      <div class="head">
        <b>双方可调解时间（平台重新匹配依据）</b>
        <el-button link type="primary" @click="load"><el-icon><Refresh /></el-icon>刷新</el-button>
      </div>
    </template>
    <el-alert type="info" :closable="false" show-icon class="mb"
              title="登记申请人/被申请人各自可参加调解的时间段；改期冲突时平台取双方时间交集，并叠加调解员排班、调解室/线上会议占用自动给出可行方案。" />
    <el-row :gutter="12">
      <el-col :span="12" v-for="p in parties" :key="p.key">
        <el-divider content-position="left">{{ p.label }}可调解时间</el-divider>
        <div v-for="(w, i) in forms[p.key]" :key="i" class="win-row">
          <el-date-picker v-model="w.range" type="datetimerange" range-separator="至"
                          start-placeholder="开始" end-placeholder="结束"
                          value-format="YYYY-MM-DDTHH:mm:ss" style="flex:1" />
          <el-button link type="danger" @click="forms[p.key].splice(i, 1)">删除</el-button>
        </div>
        <el-button size="small" @click="forms[p.key].push({ range: [] })">+ 添加时间段</el-button>
        <div class="saved">
          已登记：
          <template v-if="saved[p.key].length">
            <el-tag v-for="(w, i) in saved[p.key]" :key="i" size="small" type="success" class="saved-tag">
              {{ fmt(w.startTime) }} ~ {{ fmt(w.endTime).substring(11) }}
            </el-tag>
          </template>
          <span v-else class="tip">暂无</span>
        </div>
        <el-button type="primary" size="small" class="mt" @click="save(p.key)">保存{{ p.label }}时间</el-button>
      </el-col>
    </el-row>
  </el-card>
</template>

<script setup>
import { reactive, ref, onMounted } from 'vue'
import { ElMessage } from 'element-plus'
import api from '../../api.js'

const props = defineProps({ caseId: [Number, String] })
const emit = defineEmits(['saved'])

const parties = [
  { key: 'APPLICANT', label: '申请人' },
  { key: 'RESPONDENT', label: '被申请人' }
]
const forms = reactive({ APPLICANT: [], RESPONDENT: [] })
const saved = ref({ APPLICANT: [], RESPONDENT: [] })

const fmt = t => t ? String(t).replace('T', ' ').substring(0, 16) : ''

const load = async () => {
  const data = await api.get(`/cases/${props.caseId}/availability`)
  saved.value = { APPLICANT: data.APPLICANT || [], RESPONDENT: data.RESPONDENT || [] }
  for (const key of ['APPLICANT', 'RESPONDENT']) {
    forms[key] = (saved.value[key] || []).map(w => ({ range: [fmtIso(w.startTime), fmtIso(w.endTime)] }))
  }
}
const fmtIso = t => t ? String(t).replace('T', 'T').substring(0, 19) : ''

const save = async party => {
  const windows = forms[party].filter(w => w.range?.length === 2)
    .map(w => ({ startTime: w.range[0], endTime: w.range[1] }))
  if (!windows.length) return ElMessage.warning('请至少添加一个完整的时间段')
  await api.post(`/cases/${props.caseId}/availability`, { party, windows })
  ElMessage.success('可调解时间已保存，将用于平台重新匹配')
  await load()
  emit('saved')
}

onMounted(load)
// 供父组件在需要时刷新
defineExpose({ load })
</script>

<style scoped>
.mb { margin-bottom: 14px; }
.mt { margin-top: 8px; }
.head { display: flex; justify-content: space-between; align-items: center; }
.win-row { display: flex; align-items: center; gap: 8px; margin-bottom: 8px; }
.saved { margin-top: 8px; font-size: 12px; color: #606266; }
.saved-tag { margin: 2px 4px 2px 0; }
.tip { color: #909399; }
</style>
