<template>
  <div>
    <el-card shadow="never" class="mb">
      <el-row :gutter="16">
        <el-col :span="6"><div class="stat"><div class="n" style="color:#e6a23c">{{ d?.total ?? 0 }}</div><div class="l">进入分流建议（多次改期）</div></div></el-col>
        <el-col :span="6"><div class="stat"><div class="n" style="color:#f56c6c">{{ d?.pendingReview ?? 0 }}</div><div class="l">待窗口决定</div></div></el-col>
        <el-col :span="6"><div class="stat"><div class="n" style="color:#409eff">{{ d?.continued ?? 0 }}</div><div class="l">已决定继续调解</div></div></el-col>
        <el-col :span="6"><div class="stat"><div class="n" style="color:#909399">{{ d?.diverted ?? 0 }}</div><div class="l">已确认分流</div></div></el-col>
      </el-row>
    </el-card>

    <el-card shadow="never">
      <template #header>
        <div class="head">
          <b>案件分流建议（多次改期触发调解效率风险）</b>
          <el-button text type="primary" @click="load"><el-icon><Refresh /></el-icon>刷新</el-button>
        </div>
      </template>
      <el-alert class="mb" type="warning" :closable="false" show-icon
                title="多次改期（≥2次）案件自动进入本清单。窗口人员结合取消原因、发起方与双方确认情况，研判是否继续调解；确认分流的案件可衔接转立案材料一键汇编。" />
      <el-table :data="d?.suggestions || []" stripe>
        <el-table-column prop="caseNo" label="调解编号" width="160" />
        <el-table-column prop="typeLabel" label="案由" width="130" />
        <el-table-column label="双方" min-width="180">
          <template #default="{ row }">{{ row.applicantName }} / {{ row.respondentName }}</template>
        </el-table-column>
        <el-table-column label="改期" width="80">
          <template #default="{ row }"><el-tag size="small" type="warning">{{ row.rescheduleCount }}次</el-tag></template>
        </el-table-column>
        <el-table-column label="效率风险" width="180">
          <template #default="{ row }"><el-tag size="small" :type="row.riskLevel >= 3 ? 'danger' : 'warning'">{{ row.riskLabel }}</el-tag></template>
        </el-table-column>
        <el-table-column label="最近改期" min-width="220">
          <template #default="{ row }">
            <el-tag size="small" :type="row.lastInitiator && !partySide(row.lastInitiator) ? 'danger' : 'warning'" class="mb2">
              {{ row.lastInitiatorLabel }}
            </el-tag>
            <div class="reason">{{ row.lastCancelReason || '—' }}</div>
          </template>
        </el-table-column>
        <el-table-column label="双方确认" width="120">
          <template #default="{ row }">
            <el-tag size="small" :type="row.applicantConfirmed ? 'success' : 'info'" class="mb2">申{{ row.applicantConfirmed ? '✓' : '…' }}</el-tag>
            <el-tag size="small" :type="row.respondentConfirmed ? 'success' : 'info'">被{{ row.respondentConfirmed ? '✓' : '…' }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column label="窗口决定" width="120">
          <template #default="{ row }">
            <el-tag size="small" :type="row.decision === 'DIVERT' ? 'info' : row.decision === 'CONTINUE' ? 'success' : 'danger'">
              {{ row.decisionLabel }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column label="操作" width="240" fixed="right">
          <template #default="{ row }">
            <el-button size="small" type="primary" link @click="openReview(row)">窗口研判</el-button>
            <el-button size="small" link @click="$router.push(`/cases/${row.caseId}`)">案件详情</el-button>
          </template>
        </el-table-column>
        <template #empty>暂无多次改期待分流研判案件</template>
      </el-table>
    </el-card>

    <el-dialog v-model="visible" title="窗口研判：是否继续调解" width="520px">
      <el-descriptions :column="1" border size="small" class="mb">
        <el-descriptions-item label="案件">{{ current?.caseNo }}（{{ current?.typeLabel }}）</el-descriptions-item>
        <el-descriptions-item label="累计改期">{{ current?.rescheduleCount }} 次 — {{ current?.riskLabel }}</el-descriptions-item>
        <el-descriptions-item label="最近取消原因">{{ current?.lastCancelReason || '—' }}</el-descriptions-item>
        <el-descriptions-item label="最近发起方">{{ current?.lastInitiatorLabel }}</el-descriptions-item>
      </el-descriptions>
      <el-radio-group v-model="form.decision" class="mb">
        <el-radio-button value="CONTINUE">继续调解</el-radio-button>
        <el-radio-button value="DIVERT">确认分流（衔接转立案）</el-radio-button>
      </el-radio-group>
      <el-input v-model="form.note" type="textarea" :rows="3" placeholder="填写研判意见（必填建议）" />
      <template #footer>
        <el-button @click="visible = false">取消</el-button>
        <el-button :type="form.decision === 'DIVERT' ? 'danger' : 'primary'" @click="submit">提交决定</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup>
import { onMounted, ref, reactive } from 'vue'
import { ElMessage } from 'element-plus'
import api from '../api.js'
import { INITIATOR_PARTY_SIDE } from '../constants.js'

const d = ref(null)
const visible = ref(false)
const current = ref(null)
const form = reactive({ decision: 'CONTINUE', note: '' })

const load = async () => { d.value = await api.get('/divert-suggestions') }
onMounted(load)

const partySide = i => INITIATOR_PARTY_SIDE[i] !== false

const openReview = row => {
  current.value = row
  form.decision = row.decision || 'CONTINUE'
  form.note = row.continuationNote || ''
  visible.value = true
}
const submit = async () => {
  await api.post(`/cases/${current.value.caseId}/continuation-review`, form)
  visible.value = false
  ElMessage.success(form.decision === 'DIVERT' ? '已确认分流，可在案件详情汇编转立案材料' : '已决定继续调解')
  load()
}
</script>

<style scoped>
.mb { margin-bottom: 14px; }
.mb2 { margin-bottom: 4px; }
.head { display: flex; justify-content: space-between; align-items: center; }
.stat { text-align: center; }
.stat .n { font-size: 28px; font-weight: 700; }
.stat .l { color: #909399; font-size: 13px; margin-top: 4px; }
.reason { font-size: 12px; color: #606266; }
</style>
