<template>
  <el-card shadow="never" class="mb" :body-style="{ background: reviewed ? '#f4f4f5' : '#fff7e6' }">
    <template #header>
      <div class="head">
        <b><el-icon><Warning /></el-icon> 调解效率风险（多次改期）— 窗口研判</b>
        <el-tag :type="riskTag" size="small">{{ riskText }}</el-tag>
      </div>
    </template>
    <el-alert :type="reviewed ? 'info' : 'warning'" :closable="false" show-icon class="mb"
              :title="reviewed
                ? `窗口已作出决定：${decisionText}（${c.continuationNote || '无补充意见'}）`
                : `该案件已累计改期 ${c.rescheduleCount} 次，系统已列入案件分流建议，请窗口人员研判是否继续调解。`" />
    <el-descriptions :column="2" border size="small" class="mb">
      <el-descriptions-item label="累计改期">{{ c.rescheduleCount }} 次</el-descriptions-item>
      <el-descriptions-item label="最近改期发起方">{{ lastInitiatorLabel }}</el-descriptions-item>
      <el-descriptions-item label="最近取消原因" :span="2">{{ lastCancelReason || '—' }}</el-descriptions-item>
      <el-descriptions-item label="当前排期双方确认" :span="2">
        申请人 {{ current?.applicantConfirmed ? '已确认' : '未确认' }} ／ 被申请人 {{ current?.respondentConfirmed ? '已确认' : '未确认' }}
      </el-descriptions-item>
    </el-descriptions>
    <el-form v-if="canReview && !reviewed" :model="form" label-width="90px">
      <el-form-item label="窗口决定">
        <el-radio-group v-model="form.decision">
          <el-radio-button value="CONTINUE">继续调解</el-radio-button>
          <el-radio-button value="DIVERT">确认分流</el-radio-button>
        </el-radio-group>
      </el-form-item>
      <el-form-item label="研判意见">
        <el-input v-model="form.note" type="textarea" :rows="2"
                  :placeholder="form.decision === 'DIVERT' ? '说明分流理由，确认后衔接转立案材料汇编' : '说明继续调解的基础与安排'" />
      </el-form-item>
      <el-form-item>
        <el-button type="primary" @click="submit">提交窗口决定</el-button>
        <el-button v-if="form.decision === 'DIVERT'" type="danger" plain @click="goTransfer">确认分流后：去汇编转立案材料</el-button>
      </el-form-item>
    </el-form>
    <div v-else-if="reviewed && c.continuationDecision === 'DIVERT'" class="mb">
      <el-button type="danger" @click="goTransfer">前往汇编转立案材料包</el-button>
    </div>
  </el-card>
</template>

<script setup>
import { computed, reactive } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import api from '../../api.js'
import { auth } from '../../store.js'

const props = defineProps({ data: Object })
const emit = defineEmits(['changed'])
const router = useRouter()
const c = computed(() => props.data.case)
const canReview = computed(() => auth.is('WINDOW', 'ADMIN'))

const reviewed = computed(() => c.value.continuationReviewed === true)
const decisionText = computed(() => c.value.continuationDecision === 'DIVERT' ? '确认分流' : '继续调解')
const riskTag = computed(() => (c.value.rescheduleRiskLevel ?? c.value.rescheduleCount) >= 3 ? 'danger' : 'warning')
const riskText = computed(() => (c.value.rescheduleRiskLevel ?? c.value.rescheduleCount) >= 3
  ? '红色风险（建议分流）' : '黄色风险（效率预警）')

const rescheduledList = computed(() =>
  (props.data.schedules || []).filter(s => s.status === 'RESCHEDULED'))
// 按轮次 roundNo 取最近一次改期（startTime 是原排期时间，不能据此判断先后）
const last = computed(() =>
  rescheduledList.value.reduce((acc, s) =>
    (!acc || (s.roundNo || 0) > (acc.roundNo || 0)) ? s : acc, null) || null)
const lastCancelReason = computed(() => last.value?.cancelReason || '')
const lastInitiatorLabel = computed(() => {
  const map = { APPLICANT: '申请人', RESPONDENT: '被申请人', BOTH_PARTIES: '双方当事人', MEDIATOR: '调解员', COURT: '院方/平台' }
  return last.value?.initiator ? map[last.value.initiator] : '双方当事人'
})
// 当前有效排期：轮次最大的待开始排期
const current = computed(() =>
  (props.data.schedules || [])
    .filter(s => s.status === 'PLANNED')
    .reduce((acc, s) => (!acc || (s.roundNo || 0) > (acc.roundNo || 0)) ? s : acc, null))

const form = reactive({ decision: 'CONTINUE', note: '' })

const submit = async () => {
  await api.post(`/cases/${c.value.id}/continuation-review`, form)
  ElMessage.success(form.decision === 'DIVERT' ? '窗口已确认分流，案件不再排期，可衔接转立案' : '窗口已决定继续调解')
  emit('changed')
}
const goTransfer = () => emit('go-transfer')
</script>

<style scoped>
.mb { margin-bottom: 14px; }
.head { display: flex; justify-content: space-between; align-items: center; }
</style>
