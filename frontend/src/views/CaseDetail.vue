<template>
  <div v-if="data">
    <div class="ph">
      <el-button link @click="$router.push('/cases')"><el-icon><ArrowLeft /></el-icon> 返回列表</el-button>
      <span class="cno">{{ data.case.caseNo }}</span>
      <el-tag :type="STATUS_TAG[data.case.status]" class="ml">{{ CASE_STATUS[data.case.status] }}</el-tag>
      <el-tag size="small" :type="URGENCY_TAG[data.case.urgency]" class="ml">{{ URGENCY[data.case.urgency] }}</el-tag>
      <el-tag v-for="r in data.risks" :key="r" size="small" :type="RISK_TAG[r]" class="ml">{{ RISK_LABELS[r] }}</el-tag>
    </div>

    <el-card shadow="never" class="mt">
      <el-descriptions :column="4" border size="small">
        <el-descriptions-item label="案由">{{ CASE_TYPES[c.caseType] }}</el-descriptions-item>
        <el-descriptions-item label="诉求金额">¥{{ money(c.claimAmount) }}</el-descriptions-item>
        <el-descriptions-item label="调解方式">{{ c.channel ? CHANNEL[c.channel] : '待分流确定' }}</el-descriptions-item>
        <el-descriptions-item label="承办调解员">{{ data.mediator?.realName || '未指派' }}</el-descriptions-item>
        <el-descriptions-item label="申请人">{{ c.applicantName }} {{ c.applicantPhone }}</el-descriptions-item>
        <el-descriptions-item label="被申请人">{{ c.respondentName }} {{ c.respondentPhone }}</el-descriptions-item>
        <el-descriptions-item label="被申请人地址" :span="2">{{ c.respondentAddress || '—' }}</el-descriptions-item>
        <el-descriptions-item label="证据清单" :span="3">{{ c.evidenceList || '—' }}</el-descriptions-item>
        <el-descriptions-item label="材料状态">
          <el-tag size="small" :type="c.evidenceComplete ? 'success' : 'danger'">
            {{ c.evidenceComplete ? '已齐备' : '缺件：' + c.evidenceGapNote }}
          </el-tag>
        </el-descriptions-item>
        <el-descriptions-item label="诉求摘要" :span="4">{{ c.claimSummary || '—' }}</el-descriptions-item>
        <el-descriptions-item label="分流意见" :span="4">{{ c.triageOpinion || '—' }}</el-descriptions-item>
      </el-descriptions>
      <div class="quick">
        <el-button size="small" @click="deliverVisible = true"><el-icon><Message /></el-icon>电子送达</el-button>
        <el-button size="small" @click="downloadDossier"><el-icon><Document /></el-icon>导出连续过程材料卷</el-button>
        <el-button size="small" v-if="auth.is('MEDIATOR','WINDOW','ADMIN')" @click="missingVisible = true">
          <el-icon><Warning /></el-icon>{{ c.respondentMissing ? '恢复联系登记' : '失联登记' }}
        </el-button>
        <el-button size="small" v-if="!c.evidenceComplete" type="warning" @click="supplementVisible = true">
          <el-icon><CirclePlus /></el-icon>补正证据材料
        </el-button>
      </div>
    </el-card>

    <el-card shadow="never" class="mt">
      <el-tabs v-model="tab">
        <el-tab-pane label="分流与排期" name="schedule">
          <TriageSchedule :data="data" @changed="load" @go-transfer="tab = 'transfer'" />
        </el-tab-pane>
        <el-tab-pane label="调解与协议" name="mediation">
          <MediationPanel :data="data" @changed="load" />
        </el-tab-pane>
        <el-tab-pane label="履行与回访" name="performance">
          <PerformancePanel :data="data" @changed="load" />
        </el-tab-pane>
        <el-tab-pane label="司法确认 / 转立案" name="transfer">
          <TransferPanel :data="data" @changed="load" />
        </el-tab-pane>
        <el-tab-pane :label="`过程时间线（${data.timeline.length}）`" name="timeline">
          <el-timeline class="tl">
            <el-timeline-item v-for="e in data.timeline" :key="e.id" :timestamp="fmt(e.createdAt)"
                              :type="typeOf(e.type)" placement="top">
              <el-card shadow="hover" class="tl-card">
                <div class="tl-title">{{ e.title }}
                  <el-tag size="small" class="ml">{{ e.operatorName }}</el-tag>
                  <el-tag v-if="e.attachmentName" size="small" type="success" class="ml">
                    <el-icon><Paperclip /></el-icon>{{ e.attachmentName }}
                  </el-tag>
                </div>
                <div class="tl-detail">{{ e.detail }}</div>
              </el-card>
            </el-timeline-item>
          </el-timeline>
        </el-tab-pane>
      </el-tabs>
    </el-card>

    <!-- 电子送达 -->
    <el-dialog v-model="deliverVisible" title="电子送达" width="520px">
      <el-form :model="deliverForm" label-width="100px">
        <el-form-item label="送达对象">
          <el-radio-group v-model="deliverForm.target">
            <el-radio value="APPLICANT">申请人（{{ c.applicantPhone || '无电话' }}）</el-radio>
            <el-radio value="RESPONDENT">被申请人（{{ c.respondentPhone || '无电话' }}）</el-radio>
          </el-radio-group>
        </el-form-item>
        <el-form-item label="文书名称">
          <el-input v-model="deliverForm.documentName" placeholder="如：排期通知 / 调解协议" />
        </el-form-item>
        <el-form-item label="送达方式">
          <el-radio-group v-model="deliverForm.channel">
            <el-radio-button value="SMS">短信</el-radio-button>
            <el-radio-button value="EMAIL">邮件</el-radio-button>
            <el-radio-button value="ONLINE">站内</el-radio-button>
          </el-radio-group>
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="deliverVisible = false">取消</el-button>
        <el-button type="primary" @click="doDeliver">发送</el-button>
      </template>
    </el-dialog>

    <!-- 失联登记 -->
    <el-dialog v-model="missingVisible" :title="c.respondentMissing ? '恢复联系登记' : '被申请人失联登记'" width="480px">
      <el-input v-model="missingNote" type="textarea" :rows="3"
                :placeholder="c.respondentMissing ? '说明恢复联系的情况' : '说明已尝试的联系过程：电话、户籍地走访、社区了解等'" />
      <template #footer>
        <el-button @click="missingVisible = false">取消</el-button>
        <el-button :type="c.respondentMissing ? 'success' : 'danger'" @click="doMissing">
          {{ c.respondentMissing ? '登记恢复联系' : '登记失联' }}
        </el-button>
      </template>
    </el-dialog>

    <!-- 证据补正 -->
    <el-dialog v-model="supplementVisible" title="证据材料补正" width="480px">
      <el-alert type="warning" :closable="false" show-icon
                title="补正后系统将在卷宗时间线连续留痕，消除缺件预警" class="mb" />
      <el-input v-model="supplementNote" type="textarea" :rows="3" placeholder="说明本次补齐的材料，如：已补交收费标准备案表" />
      <template #footer>
        <el-button @click="supplementVisible = false">取消</el-button>
        <el-button type="primary" @click="doSupplement">确认补正</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup>
import { onMounted, ref, computed } from 'vue'
import { useRoute } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import api from '../api.js'
import { auth } from '../store.js'
import { CASE_TYPES, CASE_STATUS, STATUS_TAG, URGENCY, URGENCY_TAG, CHANNEL, RISK_LABELS, RISK_TAG } from '../constants.js'
import TriageSchedule from './panels/TriageSchedule.vue'
import MediationPanel from './panels/MediationPanel.vue'
import PerformancePanel from './panels/PerformancePanel.vue'
import TransferPanel from './panels/TransferPanel.vue'

const route = useRoute()
const data = ref(null)
const tab = ref('schedule')

const c = computed(() => data.value.case)
const money = v => v == null ? '—' : Number(v).toLocaleString('zh-CN', { minimumFractionDigits: 2 })
const fmt = t => t ? t.replace('T', ' ').substring(0, 16) : ''

const TYPE_COLOR = {
  SUBMIT: '', TRIAGE: 'primary', SCHEDULE: 'primary', RESCHEDULE: 'warning',
  RECORD: '', AGREEMENT: 'success', SIGN: 'success', FACE: 'success',
  PERFORMANCE: 'success', OVERDUE: 'danger', FOLLOWUP: 'info', TRANSFER: 'warning',
  DELIVERY: 'info', MISSING: 'danger', EVIDENCE: 'warning'
}
const typeOf = t => TYPE_COLOR[t] || ''

const load = async () => {
  data.value = await api.get(`/cases/${route.params.id}`)
}
onMounted(load)

// ---- 电子送达 ----
const deliverVisible = ref(false)
const deliverForm = ref({ target: 'RESPONDENT', documentName: '调解排期通知', channel: 'SMS' })
const doDeliver = async () => {
  const d = await api.post(`/cases/${c.value.id}/deliveries`, deliverForm.value)
  deliverVisible.value = false
  ElMessage[d.status === 'FAILED' ? 'warning' : 'success'](d.detail)
  load()
}

// ---- 失联 ----
const missingVisible = ref(false)
const missingNote = ref('')
const doMissing = async () => {
  await api.post(`/cases/${c.value.id}/missing`, { missing: !c.value.respondentMissing, note: missingNote.value })
  missingVisible.value = false
  missingNote.value = ''
  ElMessage.success('已登记并写入案件时间线')
  load()
}

// ---- 证据补正 ----
const supplementVisible = ref(false)
const supplementNote = ref('')
const doSupplement = async () => {
  await api.post(`/cases/${c.value.id}/evidence`, { note: supplementNote.value })
  supplementVisible.value = false
  supplementNote.value = ''
  ElMessage.success('材料已补正，缺件预警消除')
  load()
}

const downloadDossier = () => {
  const token = localStorage.getItem('token')
  // 通过 fetch 带鉴权头下载
  fetch(`/api/cases/${c.value.id}/dossier`, { headers: { Authorization: `Bearer ${token}` } })
    .then(r => r.blob()).then(blob => {
      const a = document.createElement('a')
      a.href = URL.createObjectURL(blob)
      a.download = `过程材料卷_${c.value.caseNo}.txt`
      a.click()
      ElMessage.success('连续过程材料卷已导出，可直接提交法官')
      load()
    })
}
</script>

<style scoped>
.ph { margin-top: 4px; display: flex; align-items: center; gap: 4px; flex-wrap: wrap; }
.cno { font-size: 17px; font-weight: 700; color: #1d3c6e; }
.ml { margin-left: 8px; }
.mt { margin-top: 12px; }
.quick { margin-top: 10px; display: flex; gap: 8px; flex-wrap: wrap; }
.mb { margin-bottom: 10px; }
.tl { padding: 12px 8px; }
.tl-title { font-weight: 600; }
.tl-detail { color: #606266; font-size: 13px; margin-top: 6px; white-space: pre-wrap; }
</style>
