<template>
  <div>
    <el-card shadow="never" class="mb">
      <el-alert type="success" :closable="false" show-icon
                title="改期归责规则：申请人/被申请人/双方临时改期属当事人原因，不计入调解员（工作人员）延误；仅调解员或院方原因的改期计入工作人员排班延误。" />
    </el-card>

    <el-card shadow="never">
      <template #header><b>调解员绩效与改期归责</b></template>
      <el-table :data="rows" stripe row-key="mediatorId"
                :expand-row-keys="expanded" @expand-change="onExpand">
        <el-table-column type="expand">
          <template #default="{ row }">
            <div class="detail" v-if="details[row.mediatorId]">
              <el-table :data="details[row.mediatorId].rescheduleRecords" size="small" border>
                <el-table-column label="案件" width="90">
                  <template #default="{ row: r }">#{{ r.caseId }}</template>
                </el-table-column>
                <el-table-column label="原排期" min-width="150">
                  <template #default="{ row: r }">{{ fmt(r.startTime) }}（第{{ r.roundNo }}次）</template>
                </el-table-column>
                <el-table-column prop="cancelReason" label="取消原因" min-width="200" />
                <el-table-column label="发起方" width="120">
                  <template #default="{ row: r }">
                    <el-tag size="small" :type="r.partySide ? 'warning' : 'danger'">{{ r.initiatorLabel }}</el-tag>
                  </template>
                </el-table-column>
                <el-table-column label="是否计入工作人员延误" width="180">
                  <template #default="{ row: r }">
                    <el-tag size="small" :type="r.countsAsStaffDelay ? 'danger' : 'info'">
                      {{ r.countsAsStaffDelay ? '计入排班延误' : '不计入（当事人变更）' }}
                    </el-tag>
                  </template>
                </el-table-column>
                <template #empty>该调解员暂无改期记录</template>
              </el-table>
            </div>
            <div v-else class="tip">加载中…</div>
          </template>
        </el-table-column>
        <el-table-column prop="mediatorName" label="调解员" width="100" />
        <el-table-column prop="mediationRoom" label="调解室" width="120" />
        <el-table-column label="承办/在办" width="110">
          <template #default="{ row }">{{ row.totalCases }} 件 / 在办 {{ row.activeCases }}</template>
        </el-table-column>
        <el-table-column label="调解成功率" width="110">
          <template #default="{ row }">{{ row.successRate }}%</template>
        </el-table-column>
        <el-table-column label="改期总数" width="90">
          <template #default="{ row }">
            <el-tag size="small">{{ row.totalReschedules }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column label="当事人原因改期（不计延误）" width="200">
          <template #default="{ row }">
            <el-tag size="small" type="warning">{{ row.partySideReschedules }} 次</el-tag>
          </template>
        </el-table-column>
        <el-table-column label="工作人员原因改期（计延误）" width="200">
          <template #default="{ row }">
            <el-tag size="small" :type="row.staffSideReschedules > 0 ? 'danger' : 'info'">{{ row.staffSideReschedules }} 次</el-tag>
          </template>
        </el-table-column>
        <el-table-column label="可归责延误率" width="120">
          <template #default="{ row }">
            <span :style="{ color: row.staffDelayRate > 0 ? '#f56c6c' : '#67c23a', fontWeight: 700 }">{{ row.staffDelayRate }}%</span>
          </template>
        </el-table-column>
        <el-table-column label="多次改期在办" width="120">
          <template #default="{ row }">
            <el-tag size="small" :type="row.repeatRescheduleActiveCases > 0 ? 'warning' : 'info'">{{ row.repeatRescheduleActiveCases }} 件</el-tag>
          </template>
        </el-table-column>
      </el-table>
    </el-card>
  </div>
</template>

<script setup>
import { onMounted, ref } from 'vue'
import api from '../api.js'

const rows = ref([])
const details = ref({})
const expanded = ref([])

const load = async () => { rows.value = await api.get('/mediator-performance') }
onMounted(load)

const onExpand = async (row, expandedRows) => {
  const isOpen = Array.isArray(expandedRows)
    ? expandedRows.some(r => r.mediatorId === row.mediatorId)
    : !!expandedRows
  if (isOpen && !details.value[row.mediatorId]) {
    details.value[row.mediatorId] = await api.get(`/mediator-performance/${row.mediatorId}`)
  }
}

const fmt = t => t ? String(t).replace('T', ' ').substring(0, 16) : ''
</script>

<style scoped>
.mb { margin-bottom: 14px; }
.detail { padding: 8px 16px; background: #fafafa; }
.tip { color: #909399; padding: 8px 16px; }
</style>
