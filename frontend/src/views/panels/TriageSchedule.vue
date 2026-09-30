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

    <!-- 多次改期 · 调解效率风险与分流建议 -->
    <el-card v-if="eff && eff.risk" shadow="never" class="mb risk-card">
      <template #header>
        <div class="head">
          <b><el-icon><Warning /></el-icon> 调解效率风险 · 案件分流建议</b>
          <el-tag :type="decisionTag(eff.decision)" size="small">{{ decisionText(eff.decision) }}</el-tag>
        </div>
      </template>
      <el-alert type="error" :closable="false" show-icon class="mb"
                :title="`案件已累计改期 ${eff.rescheduleCount} 次（当事人/双方临时变更 ${eff.partyCausedCount} 次，工作人员原因 ${eff.staffCausedCount} 次），存在调解效率风险`" />
      <ul class="reasons">
        <li v-for="(r, i) in eff.reasons" :key="i">{{ r }}</li>
      </ul>
      <el-descriptions :column="1" border size="small" v-if="eff.decision">
        <el-descriptions-item label="窗口决定">
          {{ eff.decision === 'CONTINUE' ? '继续调解' : '确认分流（进入转立案）' }}
        </el-descriptions-item>
        <el-descriptions-item label="复核意见">{{ eff.efficiencyNote || '—' }}</el-descriptions-item>
        <el-descriptions-item label="决定时间">{{ fmt(eff.decidedAt) }}</el-descriptions-item>
      </el-descriptions>
      <div v-if="!eff.decision && auth.is('WINDOW','ADMIN')" class="risk-actions">
        <span class="tip">窗口人员复核后决定是否继续调解：</span>
        <el-button type="primary" @click="openDecision('CONTINUE')">决定继续调解</el-button>
        <el-button type="danger" @click="openDecision('TRIAGE')">确认分流（自动汇编转立案材料）</el-button>
      </div>
      <div v-else-if="!eff.decision" class="tip">已生成分流建议，等待立案窗口人员复核决定。</div>
    </el-card>

    <!-- 平台重匹配建议（待窗口确认分流） -->
    <el-card v-if="proposals.length" shadow="never" class="mb">
      <template #header>
        <div class="head">
          <b><el-icon><Connection /></el-icon> 改期冲突重匹配建议</b>
          <el-tag size="small" type="warning">平台在双方可用时间交集内重新匹配调解员与调解室/线上会议</el-tag>
        </div>
      </template>
      <el-table :data="proposals" stripe size="small">
        <el-table-column label="提交时间" width="150">
          <template #default="{ row }">{{ fmt(row.proposedAt) }}</template>
        </el-table-column>
        <el-table-column label="取消原因" min-width="150" prop="cancelReason" />
        <el-table-column label="发起方" width="160">
          <template #default="{ row }">
            <el-tag size="small" :type="row.changeSource === 'STAFF' ? 'danger' : 'warning'">
              {{ CHANGE_SOURCE[row.changeSource] || CHANGE_SOURCE.PARTY_TEMP }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column label="新安排" min-width="260">
          <template #default="{ row }">
            {{ fmt(row.startTime)}} ~ {{ fmt(row.endTime).substring(11) }}<br />
            <span class="sub">{{ mediatorName(row.mediatorId) }} · {{ row.location }}</span>
          </template>
        </el-table-column>
        <el-table-column label="状态" width="110">
          <template #default="{ row }">
            <el-tag size="small" :type="proposalTag(row.status)">{{ proposalLabel(row.status) }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column label="操作" width="230">
          <template #default="{ row }">
            <template v-if="row.status === 'PROPOSED' && auth.is('WINDOW','ADMIN')">
              <el-button size="small" type="primary" link @click="confirmProposal(row)">确认分流</el-button>
              <el-button size="small" type="danger" link @click="rejectProposal(row)">驳回</el-button>
            </template>
            <span v-else-if="row.status === 'REJECTED'" class="sub">驳回：{{ row.rejectReason }}</span>
            <span v-else-if="row.status === 'EXPIRED'" class="sub">已失效：{{ row.rejectReason }}</span>
            <span v-else class="sub">已由窗口确认分流</span>
          </template>
        </el-table-column>
      </el-table>
    </el-card>

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
        <el-table-column label="次数" width="64"><template #default="{ row }">第{{ row.roundNo }}次</template></el-table-column>
        <el-table-column label="时间" min-width="215">
          <template #default="{ row }">{{ fmt(row.startTime) }} ~ {{ fmt(row.endTime).substring(11) }}</template>
        </el-table-column>
        <el-table-column label="方式" width="84">
          <template #default="{ row }">{{ CHANNEL[row.channel] }}</template>
        </el-table-column>
        <el-table-column prop="location" label="调解室/会议" min-width="170" />
        <el-table-column label="出席确认" width="130">
          <template #default="{ row }">
            <el-tag size="small" :type="row.applicantConfirmed ? 'success' : 'info'" class="ck">申{{ row.applicantConfirmed ? '✓' : '…' }}</el-tag>
            <el-tag size="small" :type="row.respondentConfirmed ? 'success' : 'info'" class="ck">被{{ row.respondentConfirmed ? '✓' : '…' }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column label="状态" width="92">
          <template #default="{ row }">
            <el-tag size="small" :type="scheduleTag(row.status)">{{ scheduleLabel(row.status) }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column label="操作" width="230">
          <template #default="{ row }">
            <template v-if="row.status === 'PLANNED'">
              <el-button v-if="auth.is('MEDIATOR','WINDOW','ADMIN')" size="small" link type="warning" @click="openReschedule(row)">改期</el-button>
              <el-button v-if="auth.is('PARTY')" size="small" link type="primary" @click="confirmAttend(row,'APPLICANT')">确认出席</el-button>
              <template v-if="auth.is('MEDIATOR','WINDOW','ADMIN')">
                <el-button v-if="!row.applicantConfirmed" size="small" link @click="confirmAttend(row,'APPLICANT')">代申确认</el-button>
                <el-button v-if="!row.respondentConfirmed" size="small" link @click="confirmAttend(row,'RESPONDENT')">代被确认</el-button>
              </template>
            </template>
          </template>
        </el-table-column>
        <el-table-column label="取消原因/发起方" min-width="200">
          <template #default="{ row }">
            <template v-if="row.status === 'RESCHEDULED'">
              <div>{{ row.cancelReason || '—' }}</div>
              <el-tag size="small" :type="row.changeSource === 'STAFF' ? 'danger' : 'warning'" class="ck">
                {{ CHANGE_SOURCE[row.changeSource] || '双方时间冲突' }}
              </el-tag>
              <el-tag v-if="row.partyCaused" size="small" type="info" class="ck">绩效免责</el-tag>
              <el-tag size="small" :type="row.windowConfirmed ? 'success' : 'info'" class="ck">
                {{ row.windowConfirmed ? '窗口已确认分流' : '待窗口确认' }}
              </el-tag>
            </template>
            <span v-else class="sub">{{ row.note || '' }}</span>
          </template>
        </el-table-column>
        <template #empty>暂无排期记录</template>
      </el-table>
    </el-card>

    <!-- 排期 / 改期弹窗 -->
    <el-dialog v-model="schedVisible" :title="dialogTitle" width="660px">
      <!-- 普通排期 -->
      <el-form v-if="!editing" label-width="100px">
        <el-form-item label="调解员">
          <el-select v-model="sched.mediatorId" style="width:100%" @change="loadSuggest">
            <el-option v-for="m in mediators" :key="m.id" :label="m.realName" :value="m.id" />
          </el-select>
        </el-form-item>
        <el-form-item label="调解方式">
          <el-radio-group v-model="sched.channel">
            <el-radio-button value="ONLINE">线上</el-radio-button>
            <el-radio-button value="OFFLINE">线下</el-radio-button>
        </el-radio-group>
        </el-form-item>
        <el-form-item label="地点/会议">
          <el-input v-model="sched.location" :placeholder="sched.channel === 'ONLINE' ? '留空将自动分配空闲线上会议室' : '留空将自动分配空闲线下调解室'" />
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
            <div class="tip">按专长匹配、在办量、时间冲突综合推荐，点击标签可直接选用</div>
          </div>
          <span v-else class="tip">选择时间后显示调解员推荐；调解室/线上会议由平台按空闲自动分配</span>
        </el-form-item>
        <el-form-item label="备注"><el-input v-model="sched.note" type="textarea" :rows="2" /></el-form-item>
      </el-form>

      <!-- 改期 -->
      <el-form v-else label-width="110px">
        <el-form-item label="改期发起方">
          <el-radio-group v-model="resched.changeSource">
            <el-radio-button value="PARTY_TEMP">当事人/双方临时变更</el-radio-button>
            <el-radio-button value="STAFF">工作人员调整</el-radio-button>
            <el-radio-button value="PLATFORM">平台原因</el-radio-button>
          </el-radio-group>
        </el-form-item>
        <el-form-item label="取消原因">
          <el-select v-model="resched.reason" allow-create filterable style="width:100%"
                     placeholder="选择或填写取消原因（将保留在原排期记录中）">
            <el-option v-for="r in REASON_OPTIONS[resched.changeSource] || REASON_OPTIONS.PARTY_TEMP"
                       :key="r" :value="r" :label="r" />
          </el-select>
        </el-form-item>
        <el-form-item label="新时间">
          <el-date-picker v-model="reschedRange" type="datetimerange" range-separator="至"
                          start-placeholder="新开始" end-placeholder="新结束"
                          value-format="YYYY-MM-DDTHH:mm:ss" style="width:100%" @change="checkConflict" />
        </el-form-item>

        <el-alert v-if="conflict && conflict.mediatorConflict" type="error" show-icon :closable="false" class="mb"
                  title="调解员在新时段存在排班冲突，不能直接改期">
          <template #default>
            <div>平台可在双方可用时间交集内重新匹配可用调解员、调解室/线上会议。</div>
            <el-button type="primary" size="small" class="mt" @click="openRematch">发起平台重匹配</el-button>
          </template>
        </el-alert>
        <el-alert v-else-if="conflict && !conflict.mediatorConflict" type="success" show-icon :closable="false"
                  :title="`调解员时段空闲；该方式下另有 ${conflict.freeRooms.length} 个空闲调解室/线上会议可自动分配`" />
        <el-form-item label="备注"><el-input v-model="resched.note" type="textarea" :rows="2" /></el-form-item>
      </el-form>

      <template #footer>
        <el-button @click="schedVisible = false">取消</el-button>
        <el-button v-if="editing" type="primary" :disabled="!!(conflict && conflict.mediatorConflict)" @click="submitSchedule">
          确认改期（保留取消原因与双方确认）
        </el-button>
        <el-button v-else type="primary" @click="submitSchedule">确认排期</el-button>
      </template>
    </el-dialog>

    <!-- 平台重匹配对话框 -->
    <el-dialog v-model="rematchVisible" title="改期冲突 · 平台重新匹配" width="820px" top="6vh">
      <el-alert type="info" :closable="false" show-icon class="mb"
                title="双方临时改期导致调解员排班冲突时，请填写双方可用时间段，平台在时间交集内重新匹配可用调解员、调解室/线上会议并生成建议，提交后由窗口人员确认分流。" />
      <el-form label-width="120px">
        <el-form-item label="申请人可用时间">
          <div v-for="(s, i) in applicantSlots" :key="'a'+i" class="slot-row">
            <el-date-picker v-model="s.range" type="datetimerange" range-separator="至"
                            start-placeholder="开始" end-placeholder="结束"
                            value-format="YYYY-MM-DDTHH:mm:ss" />
            <el-button link type="danger" @click="applicantSlots.splice(i,1)">删除</el-button>
          </div>
          <el-button size="small" @click="applicantSlots.push({ range: [] })">+ 添加申请人时段</el-button>
        </el-form-item>
        <el-form-item label="被申请人可用时间">
          <div v-for="(s, i) in respondentSlots" :key="'r'+i" class="slot-row">
            <el-date-picker v-model="s.range" type="datetimerange" range-separator="至"
                            start-placeholder="开始" end-placeholder="结束"
                            value-format="YYYY-MM-DDTHH:mm:ss" />
            <el-button link type="danger" @click="respondentSlots.splice(i,1)">删除</el-button>
          </div>
          <el-button size="small" @click="respondentSlots.push({ range: [] })">+ 添加被申请人时段</el-button>
        </el-form-item>
        <el-row :gutter="12">
          <el-col :span="12">
            <el-form-item label="调解时长">
              <el-select v-model="durationMinutes" style="width:100%">
                <el-option :value="30" label="30 分钟" />
                <el-option :value="60" label="60 分钟" />
                <el-option :value="90" label="90 分钟" />
              </el-select>
            </el-form-item>
          </el-col>
          <el-col :span="12">
            <el-form-item label="调解方式">
              <el-radio-group v-model="rematchChannel">
                <el-radio-button value="ONLINE">线上会议</el-radio-button>
                <el-radio-button value="OFFLINE">线下调解室</el-radio-button>
              </el-radio-group>
            </el-form-item>
          </el-col>
        </el-row>
        <el-form-item>
          <el-button type="primary" :loading="matching" @click="doRematch">
            <el-icon><MagicStick /></el-icon>匹配可用方案
          </el-button>
          <span v-if="rematchMsg" class="tip">{{ rematchMsg }}</span>
        </el-form-item>
      </el-form>

      <el-divider content-position="left">候选方案（按专长匹配、在办量、沿用原调解员、资源空闲综合排序）</el-divider>
      <el-table :data="options" stripe size="small" highlight-current-row max-height="260"
                @current-change="onSelectOption">
        <el-table-column width="55">
          <template #default="{ row }">
            <el-radio v-model="selectedOptionId" :value="row._k">&nbsp;</el-radio>
          </template>
        </el-table-column>
        <el-table-column label="时间" min-width="210">
          <template #default="{ row }">{{ fmt(row.startTime) }} ~ {{ fmt(row.endTime).substring(11) }}</template>
        </el-table-column>
        <el-table-column prop="mediatorName" label="调解员" width="100" />
        <el-table-column prop="roomName" label="调解室/会议" min-width="170" />
        <el-table-column label="得分" width="70">
          <template #default="{ row }"><el-tag size="small" type="success">{{ row.score }}</el-tag></template>
        </el-table-column>
        <el-table-column prop="matchNote" label="匹配说明" min-width="160" />
        <template #empty>填写双方可用时间后点击「匹配可用方案」</template>
      </el-table>

      <template #footer>
        <el-button @click="rematchVisible = false">取消</el-button>
        <el-button type="primary" :disabled="!selectedOption" @click="submitProposal">
          提交重匹配建议（转窗口确认分流）
        </el-button>
      </template>
    </el-dialog>

    <!-- 窗口决定弹窗 -->
    <el-dialog v-model="decisionVisible" :title="decisionForm.decision === 'CONTINUE' ? '窗口决定：继续调解' : '窗口确认分流'" width="520px">
      <el-alert :type="decisionForm.decision === 'CONTINUE' ? 'primary' : 'error'" :closable="false" show-icon class="mb"
                :title="decisionForm.decision === 'CONTINUE'
                  ? '复核后决定继续调解，改期过程留痕但不影响调解员绩效（当事人临时变更免责）。'
                  : '确认分流后系统将自动汇编申请、历次改期、笔录等全过程材料为转立案材料包，待立案窗口接收。'" />
      <el-input v-model="decisionForm.note" type="textarea" :rows="3" placeholder="复核意见（可选）" />
      <template #footer>
        <el-button @click="decisionVisible = false">取消</el-button>
        <el-button :type="decisionForm.decision === 'CONTINUE' ? 'primary' : 'danger'" @click="submitDecision">确认决定</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup>
import { reactive, ref, computed, onMounted } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import api from '../../api.js'
import { auth } from '../../store.js'
import { CHANNEL, CHANGE_SOURCE } from '../../constants.js'

const props = defineProps({ data: Object })
const emit = defineEmits(['changed'])
const c = computed(() => props.data.case)
const proposals = computed(() => props.data.proposals || [])

const mediators = ref([])
const loadMediators = async () => { mediators.value = await api.get('/mediators') }
onMounted(async () => {
  await loadMediators()
  loadEfficiency()
})
const mediatorName = id => mediators.value.find(m => m.id === id)?.realName || '调解员'

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

// ---- 排期 / 改期 ----
const schedVisible = ref(false)
const editing = ref(null)
const dialogTitle = computed(() => {
  if (!editing.value) return '安排调解'
  return rematchOpened.value ? '改期冲突重匹配' : '改期（原记录保留留痕）'
})
const sched = reactive({ mediatorId: null, channel: 'ONLINE', location: '', startTime: '', endTime: '', note: '' })
const timeRange = ref([])
const REASON_OPTIONS = {
  PARTY_TEMP: ['双方时间冲突', '被申请人务工外出', '申请人身体不适', '被申请人临时出差', '家中有急事需处理', '双方协商另约时间'],
  STAFF: ['调解员临时参加紧急会议', '调解员出差', '调解室临时被征用', '工作人员排期失误'],
  PLATFORM: ['线上视频平台维护', '系统升级停服']
}
const resched = reactive({ reason: '双方时间冲突', changeSource: 'PARTY_TEMP', note: '' })
const reschedRange = ref([])
const suggests = ref([])
const conflict = ref(null)

const openSchedule = () => {
  editing.value = null
  sched.mediatorId = c.value.mediatorId
  sched.channel = c.value.channel || 'ONLINE'
  sched.location = ''
  sched.note = ''
  timeRange.value = []
  suggests.value = []
  schedVisible.value = true
}
const openReschedule = row => {
  editing.value = row
  resched.reason = '双方时间冲突'
  resched.changeSource = 'PARTY_TEMP'
  resched.note = ''
  reschedRange.value = []
  conflict.value = null
  rematchOpened.value = false
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

let conflictTimer = null
const checkConflict = () => {
  clearTimeout(conflictTimer)
  conflict.value = null
  if (!reschedRange.value?.length) return
  conflictTimer = setTimeout(async () => {
    conflict.value = await api.get(`/schedules/${editing.value.id}/conflict-check`, {
      params: { start: reschedRange.value[0], end: reschedRange.value[1], channel: editing.value.channel }
    })
  }, 300)
}

const submitSchedule = async () => {
  if (editing.value) {
    if (!reschedRange.value?.length) return ElMessage.warning('请选择新的时间')
    await api.post(`/schedules/${editing.value.id}/reschedule`, {
      reason: resched.reason, changeSource: resched.changeSource,
      startTime: reschedRange.value[0], endTime: reschedRange.value[1], note: resched.note
    })
    ElMessage.success('改期完成：取消原因与双方确认已保留，当事人临时变更不计工作人员延误')
  } else {
    if (!sched.mediatorId || !timeRange.value?.length) return ElMessage.warning('请选择调解员和时间')
    await api.post(`/cases/${c.value.id}/schedules`, {
      mediatorId: sched.mediatorId, channel: sched.channel, location: sched.location,
      startTime: timeRange.value[0], endTime: timeRange.value[1], note: sched.note
    })
    ElMessage.success('排期成功，平台已自动分配空闲调解室/线上会议')
  }
  schedVisible.value = false
  emit('changed')
}

const confirmAttend = async (row, party) => {
  await api.post(`/schedules/${row.id}/confirm`, { party })
  ElMessage.success('出席确认已记录')
  emit('changed')
}

// ---- 平台重匹配 ----
const rematchVisible = ref(false)
const rematchOpened = ref(false)
const applicantSlots = ref([{ range: [] }])
const respondentSlots = ref([{ range: [] }])
const durationMinutes = ref(60)
const rematchChannel = ref('ONLINE')
const options = ref([])
const selectedOptionId = ref(null)
const selectedOption = ref(null)
const matching = ref(false)
const rematchMsg = ref('')

const openRematch = () => {
  schedVisible.value = false
  rematchOpened.value = true
  applicantSlots.value = [{ range: [] }]
  respondentSlots.value = [{ range: [] }]
  rematchChannel.value = editing.value.channel
  durationMinutes.value = 60
  options.value = []
  selectedOptionId.value = null
  selectedOption.value = null
  rematchMsg.value = ''
  rematchVisible.value = true
}

const toSlots = arr => arr.filter(s => s.range?.length === 2)
  .map(s => ({ start: s.range[0], end: s.range[1] }))

const doRematch = async () => {
  const a = toSlots(applicantSlots.value)
  const r = toSlots(respondentSlots.value)
  if (!a.length || !r.length) return ElMessage.warning('请分别至少填写一条双方可用时间段')
  matching.value = true
  rematchMsg.value = ''
  try {
    const res = await api.post(`/cases/${c.value.id}/rematch/options`, {
      scheduleId: editing.value.id,
      applicantSlots: a, respondentSlots: r,
      durationMinutes: durationMinutes.value, channel: rematchChannel.value
    })
    options.value = (res.options || []).map((o, i) => ({ ...o, _k: i }))
    rematchMsg.value = res.message || (options.value.length ? `匹配到 ${options.value.length} 个候选方案` : '')
    selectedOptionId.value = null
    selectedOption.value = null
  } finally {
    matching.value = false
  }
}

const onSelectOption = row => {
  if (!row) return
  selectedOptionId.value = row._k
  selectedOption.value = row
}

const submitProposal = async () => {
  const o = selectedOption.value
  if (!o) return ElMessage.warning('请选择一个候选方案')
  await api.post(`/cases/${c.value.id}/rematch/propose`, {
    scheduleId: editing.value.id,
    cancelReason: resched.reason, changeSource: resched.changeSource,
    mediatorId: o.mediatorId, mediatorName: o.mediatorName,
    roomId: o.roomId, roomName: o.roomName,
    channel: o.channel, location: o.location,
    startTime: o.startTime, endTime: o.endTime,
    score: o.score, matchNote: o.matchNote
  })
  rematchVisible.value = false
  ElMessage.success('重匹配建议已提交，等待窗口人员确认分流')
  emit('changed')
}

// ---- 窗口确认/驳回建议 ----
const confirmProposal = async row => {
  await ElMessageBox.confirm(
    `确认按平台方案分流？${row.location}，${fmt(row.startTime)}。原排期取消原因与双方确认将保留，当事人临时变更不计调解员延误。`,
    '窗口确认分流', { confirmButtonText: '确认分流', cancelButtonText: '取消', type: 'warning' })
  await api.post(`/proposals/${row.id}/confirm`)
  ElMessage.success('已确认分流，新排期生效，改期记录已留痕')
  emit('changed')
}
const rejectProposal = async row => {
  const { value } = await ElMessageBox.prompt('请填写驳回原因', '驳回重匹配建议', {
    confirmButtonText: '提交驳回', cancelButtonText: '取消', inputPlaceholder: '如：双方时间需重新收集'
  })
  await api.post(`/proposals/${row.id}/reject`, { reason: value })
  ElMessage.success('建议已驳回')
  emit('changed')
}

// ---- 效率风险分流建议 ----
const eff = ref(null)
const loadEfficiency = async () => {
  try { eff.value = await api.get(`/cases/${c.value.id}/efficiency`) } catch { eff.value = null }
}
const decisionVisible = ref(false)
const decisionForm = reactive({ decision: 'CONTINUE', note: '' })
const openDecision = d => {
  decisionForm.decision = d
  decisionForm.note = ''
  decisionVisible.value = true
}
const submitDecision = async () => {
  await api.post(`/cases/${c.value.id}/efficiency/decision`, {
    decision: decisionForm.decision, note: decisionForm.note
  })
  decisionVisible.value = false
  ElMessage.success(decisionForm.decision === 'CONTINUE' ? '已登记：继续调解' : '已确认分流，转立案材料包自动汇编完成')
  emit('changed')
  loadEfficiency()
}

const fmt = t => t ? t.replace('T', ' ').substring(0, 16) : ''
const scheduleLabel = s => ({ PLANNED: '已排期', RESCHEDULED: '已改期', FINISHED: '已完成', CANCELLED: '已取消' }[s])
const scheduleTag = s => ({ PLANNED: 'primary', RESCHEDULED: 'warning', FINISHED: 'success', CANCELLED: 'info' }[s])
const proposalLabel = s => ({ PROPOSED: '待窗口确认', CONFIRMED: '已确认分流', REJECTED: '已驳回', EXPIRED: '已失效' }[s] || s)
const proposalTag = s => ({ PROPOSED: 'warning', CONFIRMED: 'success', REJECTED: 'danger', EXPIRED: 'info' }[s] || 'info')
const decisionText = d => d === 'CONTINUE' ? '窗口已决定：继续调解' : d === 'TRIAGE' ? '窗口已确认分流' : '待窗口决定'
const decisionTag = d => d === 'CONTINUE' ? 'primary' : d === 'TRIAGE' ? 'danger' : 'warning'
</script>

<style scoped>
.mb { margin-bottom: 14px; }
.mt { margin-top: 8px; }
.head { display: flex; justify-content: space-between; align-items: center; }
.ck { margin-right: 4px; }
.sub { color: #909399; font-size: 12px; }
.sugg { display: flex; flex-wrap: wrap; gap: 8px; align-items: center; }
.sugg-tag { cursor: pointer; }
.tip { color: #909399; font-size: 12px; margin-left: 4px; }
.risk-card { border: 1px solid #f56c6c55; }
.reasons { margin: 6px 0 10px 18px; color: #606266; font-size: 13px; line-height: 1.9; }
.risk-actions { display: flex; align-items: center; gap: 10px; flex-wrap: wrap; }
.slot-row { display: flex; gap: 10px; align-items: center; margin-bottom: 8px; }
</style>
