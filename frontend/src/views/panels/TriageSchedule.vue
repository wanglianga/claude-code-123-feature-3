<template>
  <div>
    <!-- 窗口分流 -->
    <el-alert v-if="c.status === 'PENDING_TRIAGE'" type="error" show-icon :closable="false" class="mb"
              title="案件待立案窗口分流：核验材料 → 确定线上/线下 → 指派调解员" />
    <el-card v-if="canTriage" shadow="never" class="mb">
      <template #header><b>立案窗口分流</b></template>
      <el-form :model="triage" label-width="110px">
        <el-row :gutter="12">
          <el-col :span="12">
            <el-form-item label="指派调解员">
              <el-select v-model="triage.mediatorId" placeholder="选择调解员" style="width:100%">
                <el-option v-for="m in mediators" :key="m.id"
                           :label="`${m.realName}（${m.mediationRoom || ''}）专长：${expertiseText(m.expertise)}`"
                           :value="m.id" />
              </el-select>
            </el-form-item>
          </el-col>
          <el-col :span="12">
            <el-form-item label="调解方式">
              <el-radio-group v-model="triage.channel">
                <el-radio-button value="ONLINE">线上调解</el-radio-button>
                <el-radio-button value="OFFLINE">线下调解</el-radio-button>
              </el-radio-group>
            </el-form-item>
          </el-col>
          <el-col :span="12">
            <el-form-item label="材料核验">
              <el-switch v-model="evidenceOk" active-text="材料齐备" inactive-text="材料缺失（先行调解并登记补正）" />
            </el-form-item>
          </el-col>
          <el-col v-if="!evidenceOk" :span="12">
            <el-form-item label="缺失材料"><el-input v-model="triage.evidenceGapNote" placeholder="如：缺少收费标准备案表" /></el-form-item>
          </el-col>
          <el-col :span="24"><el-form-item label="分流意见"><el-input v-model="triage.triageOpinion" type="textarea" :rows="2" /></el-form-item></el-col>
        </el-row>
        <el-button type="primary" @click="doTriage">完成分流</el-button>
      </el-form>
    </el-card>

    <!-- 多次改期：窗口研判是否继续调解 -->
    <DiversionDecision v-if="(c.rescheduleCount || 0) >= 2" :data="data" @changed="emit('changed')" @go-transfer="emit('go-transfer')" />

    <!-- 双方可调解时间 -->
    <AvailabilityEditor v-if="canSchedule || auth.is('PARTY','ADMIN')" :case-id="c.id" @saved="emit('changed')" />

    <!-- 排期 -->
    <el-card shadow="never">
      <template #header>
        <div class="head">
          <b>调解排期记录</b>
          <el-button v-if="canSchedule" type="primary" size="small" @click="openSchedule()">
            <el-icon><Calendar /></el-icon>{{ c.status === 'SCHEDULED' || c.status === 'MEDIATING' ? '追加排期' : '安排调解' }}
          </el-button>
        </div>
      </template>
      <el-table :data="data.schedules" stripe>
        <el-table-column label="次数" width="70"><template #default="{ row }">第{{ row.roundNo }}次</template></el-table-column>
        <el-table-column label="时间" min-width="220">
          <template #default="{ row }">{{ fmt(row.startTime) }} ~ {{ fmt(row.endTime).substring(11) }}</template>
        </el-table-column>
        <el-table-column label="方式" width="85">
          <template #default="{ row }">{{ CHANNEL[row.channel] }}</template>
        </el-table-column>
        <el-table-column prop="location" label="调解室/线上会议" min-width="180" />
        <el-table-column label="出席确认" width="150">
          <template #default="{ row }">
            <el-tag size="small" :type="row.applicantConfirmed ? 'success' : 'info'" class="ck">申{{ row.applicantConfirmed ? '✓' : '…' }}</el-tag>
            <el-tag size="small" :type="row.respondentConfirmed ? 'success' : 'info'" class="ck">被{{ row.respondentConfirmed ? '✓' : '…' }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column label="状态" width="95">
          <template #default="{ row }">
            <el-tag size="small" :type="scheduleTag(row.status)">{{ scheduleLabel(row.status) }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column label="操作" width="230">
          <template #default="{ row }">
            <template v-if="row.status === 'PLANNED'">
              <el-button v-if="auth.is('MEDIATOR','WINDOW','ADMIN')" size="small" link type="warning" @click="openReschedule(row)">改期冲突/重匹配</el-button>
              <el-button v-if="auth.is('PARTY')" size="small" link type="primary" @click="confirmAttend(row, 'APPLICANT')">我确认出席</el-button>
              <template v-if="auth.is('MEDIATOR','WINDOW','ADMIN')">
                <el-button v-if="!row.applicantConfirmed" size="small" link type="success" @click="confirmAttend(row, 'APPLICANT')">申请人确认</el-button>
                <el-button v-if="!row.respondentConfirmed" size="small" link type="success" @click="confirmAttend(row, 'RESPONDENT')">被申请人确认</el-button>
              </template>
            </template>
          </template>
        </el-table-column>
        <el-table-column label="改期取消原因/发起方" min-width="200">
          <template #default="{ row }">
            <div v-if="row.status === 'RESCHEDULED'">
              <el-tag size="small" :type="partySide(row.initiator) ? 'warning' : 'danger'" class="ck">
                {{ initiatorText(row.initiator) }}
              </el-tag>
              <div class="reason">{{ row.cancelReason || '—' }}</div>
              <div class="reason tip">{{ partySide(row.initiator) ? '当事人临时变更·不计工作人员延误' : '工作人员原因·计入排班延误' }}</div>
            </div>
            <span v-else class="tip">{{ row.note }}</span>
          </template>
        </el-table-column>
        <template #empty>暂无排期记录</template>
      </el-table>
    </el-card>

    <!-- 安排排期弹窗 -->
    <el-dialog v-model="schedVisible" :title="editing ? '调解改期 · 平台重新匹配' : '安排调解'" width="720px" top="6vh">
      <el-form label-width="110px">
        <template v-if="!editing">
          <el-form-item label="调解员">
            <el-select v-model="sched.mediatorId" style="width:100%" @change="loadSuggest">
              <el-option v-for="m in mediators" :key="m.id" :label="m.realName" :value="m.id" />
            </el-select>
          </el-form-item>
          <el-form-item label="调解方式">
            <el-radio-group v-model="sched.channel" @change="onChannelChange">
              <el-radio-button value="ONLINE">线上</el-radio-button>
              <el-radio-button value="OFFLINE">线下</el-radio-button>
            </el-radio-group>
          </el-form-item>
          <el-form-item label="调解室/会议">
            <el-select v-model="sched.resourceId" style="width:100%">
              <el-option value="AUTO" label="平台自动分配空闲资源" />
              <el-option v-for="r in resources" :key="r.id" :label="`${r.name}（${r.address || ''}）`" :value="r.id" />
            </el-select>
          </el-form-item>
          <el-form-item label="时间">
            <el-date-picker v-model="timeRange" type="datetimerange" range-separator="至"
                            start-placeholder="开始" end-placeholder="结束" value-format="YYYY-MM-DDTHH:mm:ss"
                            style="width:100%" @change="loadSuggest" />
          </el-form-item>
          <el-form-item label="智能推荐">
            <div v-if="suggests.length" class="sugg">
              <el-tag v-for="s in suggests" :key="s.id" class="sugg-tag"
                      :type="s.score >= 30 ? 'success' : s.score >= 10 ? 'warning' : 'info'"
                      @click="sched.mediatorId = s.id">
                {{ s.realName }} 在办{{ s.activeCount }}/{{ s.capacity }}
                <span v-if="s.expertiseMatch">·专长✓</span>
                <span :style="{ color: s.timeFree ? '#67c23a' : '#f56c6c' }">{{ s.timeFree ? '·时间空闲✓' : '·时间冲突' }}</span>
              </el-tag>
              <div class="tip">按专长匹配、在办量、调解员排班冲突综合推荐，点击标签可直接选用</div>
            </div>
            <span v-else class="tip">选择时间后显示调解员推荐；提交时平台同步校验调解室/线上会议占用</span>
          </el-form-item>
        </template>

        <!-- 改期 -->
        <template v-else>
          <el-alert type="warning" :closable="false" show-icon class="mb"
                    title="双方临时改期导致调解员排班冲突时，平台将在双方共同可用时间内，重新匹配可用调解员、调解室/线上视频会议；改期后需双方重新确认出席。" />
          <el-form-item label="改期发起方">
            <el-radio-group v-model="resched.initiator">
              <el-radio-button v-for="(label, key) in INITIATORS" :key="key" :value="key">{{ label }}</el-radio-button>
            </el-radio-group>
          </el-form-item>
          <el-form-item label="归责提示">
            <el-tag size="small" :type="partySide(resched.initiator) ? 'warning' : 'danger'">
              {{ partySide(resched.initiator) ? '当事人临时变更，不计入调解员（工作人员）延误' : '工作人员原因，计入调解员排班延误' }}
            </el-tag>
          </el-form-item>
          <el-form-item label="取消原因">
            <el-select v-model="resched.reason" allow-create filterable style="width:100%">
              <el-option value="双方时间冲突" label="双方时间冲突" />
              <el-option value="被申请人务工外出" label="被申请人务工外出" />
              <el-option value="申请人身体不适" label="申请人身体不适" />
              <el-option value="被申请人失联待联系" label="被申请人失联待联系" />
              <el-option value="补充证据需要时间" label="补充证据需要时间" />
              <el-option value="调解员临时承办紧急案件" label="调解员临时承办紧急案件" />
            </el-select>
          </el-form-item>
          <el-form-item label="调解方式">
            <el-radio-group v-model="resched.channel">
              <el-radio-button value="ONLINE">线上</el-radio-button>
              <el-radio-button value="OFFLINE">线下</el-radio-button>
            </el-radio-group>
          </el-form-item>
          <el-form-item label="期望新时间">
            <el-date-picker v-model="reschedRange" type="datetimerange" range-separator="至"
                            start-placeholder="期望开始（可空）" end-placeholder="期望结束（可空）"
                            value-format="YYYY-MM-DDTHH:mm:ss" style="width:100%" />
          </el-form-item>
          <el-form-item>
            <el-button type="primary" plain @click="runRematch"><el-icon><MagicStick /></el-icon>检测冲突 / 平台智能重匹配</el-button>
            <el-checkbox v-model="resched.autoRematch" class="ml">提交时若期望时间冲突，平台自动在双方时间内改约</el-checkbox>
          </el-form-item>

          <!-- 双方时间窗 -->
          <el-form-item v-if="rematchData" label="双方时间">
            <div class="avail">
              <el-tag size="small" type="info" class="ck">共同可约时段：{{ rematchData.intersection.length }} 段</el-tag>
              <el-tag v-for="(w, i) in rematchData.intersection" :key="i" size="small" type="success" class="ck">
                {{ fmt(w.startTime) }} ~ {{ fmt(w.endTime).substring(11) }}
              </el-tag>
            </div>
          </el-form-item>

          <!-- 期望时段候选（调解员×资源） -->
          <template v-if="rematchData && reschedRange.length === 2">
            <el-form-item label="期望时段检测">
              <el-tag :type="rematchData.requestedFeasible ? 'success' : 'danger'" size="small">
                {{ rematchData.requestedFeasible ? '该时段存在可行的调解员+资源组合（见下，点击选用）' : '该时段调解员排班或资源全部冲突' }}
              </el-tag>
            </el-form-item>
            <el-form-item label="候选组合">
              <el-table :data="rematchData.candidates" size="small" border max-height="220">
                <el-table-column prop="mediatorName" label="调解员" width="100" />
                <el-table-column label="专长" width="60">
                  <template #default="{ row }">{{ row.expertiseMatch ? '✓' : '—' }}</template>
                </el-table-column>
                <el-table-column label="排班">
                  <template #default="{ row }">
                    <el-tag size="small" :type="row.mediatorBusy ? 'danger' : 'success'">{{ row.mediatorBusy ? '冲突' : '空闲' }}</el-tag>
                  </template>
                </el-table-column>
                <el-table-column label="调解室/线上会议">
                  <template #default="{ row }">
                    <el-button v-for="o in row.options" :key="o.resourceId" size="small" class="opt"
                               :type="o.feasible ? 'success' : 'info'" :disabled="!o.feasible"
                               @click="pickCandidate(row, o)">
                      {{ o.resourceName }}{{ o.resourceBusy ? '（占用）' : '（空闲·选用）' }}
                    </el-button>
                  </template>
                </el-table-column>
              </el-table>
            </el-form-item>
          </template>

          <!-- 双方时间内的替代时段 -->
          <el-form-item v-if="rematchData && !rematchData.requestedFeasible && rematchData.alternativeSlots.length" label="智能改约方案">
            <div class="slots">
              <el-card v-for="(s, i) in rematchData.alternativeSlots" :key="i" shadow="hover" class="slot-card"
                       :class="{ active: isPicked(s) }" @click="pickSlot(s)">
                <div class="slot-time">{{ fmt(s.startTime) }} ~ {{ fmt(s.endTime).substring(11) }}</div>
                <div class="slot-meta">{{ s.mediatorName }} · {{ s.resourceName }}<span v-if="s.expertiseMatch"> · 专长✓</span></div>
              </el-card>
            </div>
          </el-form-item>
          <el-form-item v-if="rematchData && !rematchData.requestedFeasible && !rematchData.alternativeSlots.length" label=" ">
            <span class="tip">双方共同时间内暂无可同时空闲的调解员与资源，请先在上方「双方可调解时间」补充更宽时段。</span>
          </el-form-item>
        </template>
        <el-form-item label="备注"><el-input v-model="sched.note" type="textarea" :rows="2" /></el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="schedVisible = false">取消</el-button>
        <el-button type="primary" @click="submitSchedule">{{ editing ? '确认改期并重新匹配' : '确认排期' }}</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup>
import { reactive, ref, computed, onMounted } from 'vue'
import { ElMessage } from 'element-plus'
import api from '../../api.js'
import { auth } from '../../store.js'
import { CHANNEL, RESCHEDULE_INITIATORS as INITIATORS, INITIATOR_PARTY_SIDE } from '../../constants.js'
import AvailabilityEditor from './AvailabilityEditor.vue'
import DiversionDecision from './DiversionDecision.vue'

const props = defineProps({ data: Object })
const emit = defineEmits(['changed', 'go-transfer'])
const c = computed(() => props.data.case)

const mediators = ref([])
const resources = ref([])
const loadMediators = async () => { mediators.value = await api.get('/mediators') }
const loadResources = async channel => { resources.value = await api.get('/meeting-resources', { params: { type: channel === 'ONLINE' ? 'ONLINE_MEETING' : 'OFFLINE_ROOM' } }) }
onMounted(async () => { await loadMediators(); await loadResources(c.value.channel || 'ONLINE') })

const expertiseText = e => (e || '').split(',').map(x => ({
  LOAN: '借贷', PROPERTY_FEE: '物业', TRAFFIC: '交通', NEIGHBOR: '邻里', SALES: '买卖'
}[x] || x)).join('、')

const canTriage = computed(() => auth.is('WINDOW', 'ADMIN') && (c.value.status === 'PENDING_TRIAGE' || c.value.status === 'TRIAGED'))
const canSchedule = computed(() => auth.is('WINDOW', 'MEDIATOR', 'ADMIN')
  && ['TRIAGED', 'SCHEDULED', 'MEDIATING', 'PENDING_TRIAGE'].includes(c.value.status))

const evidenceOk = ref(true)
const triage = reactive({ mediatorId: null, channel: 'ONLINE', triageOpinion: '', evidenceComplete: true, evidenceGapNote: '' })

const doTriage = async () => {
  if (!triage.mediatorId) return ElMessage.warning('请选择调解员')
  triage.evidenceComplete = evidenceOk.value
  await api.post(`/cases/${c.value.id}/triage`, triage)
  ElMessage.success('分流完成，案件进入排期环节')
  emit('changed')
}

// ---- 排期 ----
const schedVisible = ref(false)
const editing = ref(null)
const sched = reactive({ mediatorId: null, channel: 'ONLINE', resourceId: 'AUTO', location: '', startTime: '', endTime: '', note: '' })
const timeRange = ref([])
const suggests = ref([])

const openSchedule = () => {
  editing.value = null
  sched.mediatorId = c.value.mediatorId
  sched.channel = c.value.channel || 'ONLINE'
  sched.resourceId = 'AUTO'
  sched.location = ''
  sched.note = ''
  timeRange.value = []
  suggests.value = []
  loadResources(sched.channel)
  schedVisible.value = true
}

const onChannelChange = () => { sched.resourceId = 'AUTO'; loadResources(sched.channel); loadSuggest() }

const resched = reactive({
  reason: '双方时间冲突', initiator: 'BOTH_PARTIES', channel: 'ONLINE',
  mediatorId: null, resourceId: 'AUTO', autoRematch: true
})
const reschedRange = ref([])
const rematchData = ref(null)

const openReschedule = row => {
  editing.value = row
  resched.reason = '双方时间冲突'
  resched.initiator = 'BOTH_PARTIES'
  resched.channel = row.channel
  resched.mediatorId = null
  resched.resourceId = 'AUTO'
  resched.autoRematch = true
  reschedRange.value = []
  rematchData.value = null
  sched.note = ''
  schedVisible.value = true
}

const loadSuggest = async () => {
  sched.mediatorId = sched.mediatorId || c.value.mediatorId
  if (timeRange.value?.length === 2) {
    suggests.value = await api.get(`/cases/${c.value.id}/mediator-suggest`, {
      params: { start: timeRange.value[0], end: timeRange.value[1] }
    })
  }
}

const runRematch = async () => {
  const body = { channel: resched.channel }
  if (reschedRange.value?.length === 2) {
    body.startTime = reschedRange.value[0]
    body.endTime = reschedRange.value[1]
  }
  rematchData.value = await api.post(`/schedules/${editing.value.id}/rematch-options`, body)
  if (rematchData.value.requestedFeasible) ElMessage.success('期望时段可行，请在候选组合中点选「空闲」资源')
  else if (rematchData.value.alternativeSlots.length) ElMessage.warning('期望时段冲突，已给出双方时间内的智能改约方案')
  else ElMessage.error('暂无可行方案，请补充双方可调解时间')
}

const pickCandidate = (mv, o) => {
  resched.mediatorId = mv.mediatorId
  resched.resourceId = o.resourceId
  ElMessage.success(`已选用：${mv.mediatorName} / ${o.resourceName}`)
}
const pickSlot = s => {
  resched.mediatorId = s.mediatorId
  resched.resourceId = s.resourceId
  reschedRange.value = [fmtIso(s.startTime), fmtIso(s.endTime)]
}
const isPicked = s => resched.mediatorId === s.mediatorId
    && reschedRange.value?.[0] === fmtIso(s.startTime)

const submitSchedule = async () => {
  if (editing.value) {
    const payload = {
      reason: resched.reason,
      initiator: resched.initiator,
      channel: resched.channel,
      mediatorId: resched.mediatorId,
      resourceId: resched.resourceId,
      autoRematch: resched.autoRematch
    }
    if (reschedRange.value?.length === 2) {
      payload.startTime = reschedRange.value[0]
      payload.endTime = reschedRange.value[1]
    }
    const fresh = await api.post(`/schedules/${editing.value.id}/reschedule`, payload)
    ElMessage.success('改期完成：原排期已保留取消原因与发起方，平台已重新匹配，双方需重新确认出席')
    schedVisible.value = false
    emit('changed')
    return fresh
  }
  if (!sched.mediatorId || !timeRange.value?.length) return ElMessage.warning('请选择调解员和时间')
  await api.post(`/cases/${c.value.id}/schedules`, {
    mediatorId: sched.mediatorId, channel: sched.channel, resourceId: sched.resourceId,
    startTime: timeRange.value[0], endTime: timeRange.value[1], note: sched.note
  })
  ElMessage.success('排期成功，已校验调解员排班与调解室/线上会议占用')
  schedVisible.value = false
  emit('changed')
}

const confirmAttend = async (row, party) => {
  await api.post(`/schedules/${row.id}/confirm`, { party })
  ElMessage.success('出席已确认')
  emit('changed')
}

const fmt = t => t ? String(t).replace('T', ' ').substring(0, 16) : ''
const fmtIso = t => t ? String(t).substring(0, 19) : ''
const partySide = initiator => initiator ? INITIATOR_PARTY_SIDE[initiator] !== false : true
const initiatorText = i => i ? INITIATORS[i] : '双方时间冲突改期'
const scheduleLabel = s => ({ PLANNED: '已排期', RESCHEDULED: '已改期', FINISHED: '已完成', CANCELLED: '已取消' }[s])
const scheduleTag = s => ({ PLANNED: 'primary', RESCHEDULED: 'warning', FINISHED: 'success', CANCELLED: 'info' }[s])
</script>

<style scoped>
.mb { margin-bottom: 14px; }
.ml { margin-left: 10px; }
.head { display: flex; justify-content: space-between; align-items: center; }
.ck { margin-right: 4px; }
.reason { font-size: 12px; color: #606266; margin-top: 2px; }
.tip { color: #909399; font-size: 12px; margin-left: 4px; }
.sugg { display: flex; flex-wrap: wrap; gap: 8px; align-items: center; }
.sugg-tag { cursor: pointer; }
.avail { display: flex; flex-wrap: wrap; gap: 6px; }
.opt { margin: 2px 4px 2px 0; }
.slots { display: flex; flex-wrap: wrap; gap: 10px; }
.slot-card { width: 230px; cursor: pointer; }
.slot-card.active { border-color: #67c23a; box-shadow: 0 0 0 2px rgba(103,194,58,.2); }
.slot-time { font-weight: 700; color: #1d3c6e; }
.slot-meta { font-size: 12px; color: #606266; margin-top: 4px; }
</style>
