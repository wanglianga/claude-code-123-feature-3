<template>
  <div>
    <el-card shadow="never" class="mb">
      <template #header>
        <div class="head">
          <b>调解员绩效（改期责任口径）</b>
          <el-tag size="small" type="info">
            当事人/双方临时改期不计入工作人员延误，仅工作人员原因改期计入
          </el-tag>
        </div>
      </template>
      <el-alert type="warning" :closable="false" show-icon class="mb"
                title="口径说明：当事人或双方临时变更导致的改期，平台保留取消原因与双方确认，但不直接算作调解员等工作人员的延误；调解员/窗口等工作人员原因导致的改期才计入工作人员排班延误。" />
      <el-table :data="rows" stripe>
        <el-table-column prop="mediatorName" label="调解员" width="110" />
        <el-table-column prop="mediationRoom" label="调解室" width="130" />
        <el-table-column label="承办/在办" width="110">
          <template #default="{ row }">{{ row.totalCases }} / {{ row.activeCases }}</template>
        </el-table-column>
        <el-table-column label="调解成功率" width="110">
          <template #default="{ row }">
            <el-tag size="small" type="success">{{ row.successRate }}%</el-tag>
          </template>
        </el-table-column>
        <el-table-column label="改期总次数" width="100">
          <template #default="{ row }">{{ row.rescheduledTotal }}</template>
        </el-table-column>
        <el-table-column label="当事人临时变更（免责）" width="170">
          <template #default="{ row }">
            <el-tag size="small" type="info">{{ row.partyCausedCount }} 次</el-tag>
          </template>
        </el-table-column>
        <el-table-column label="平台原因" width="90">
          <template #default="{ row }">{{ row.platformCausedCount }} 次</template>
        </el-table-column>
        <el-table-column label="工作人员延误（计入）" width="170">
          <template #default="{ row }">
            <el-tag size="small" :type="row.staffDelayCount > 0 ? 'danger' : 'success'">
              {{ row.staffDelayCount }} 次
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column label="操作" width="110">
          <template #default="{ row }">
            <el-button size="small" link type="primary" @click="openDetail(row)">改期责任明细</el-button>
          </template>
        </el-table-column>
      </el-table>
    </el-card>

    <el-dialog v-model="detailVisible" :title="`${detail?.mediatorName} · 改期责任明细`" width="820px">
      <el-descriptions :column="4" border size="small" v-if="detail">
        <el-descriptions-item label="承办案件">{{ detail.totalCases }}</el-descriptions-item>
        <el-descriptions-item label="在办">{{ detail.activeCases }}</el-descriptions-item>
        <el-descriptions-item label="成功率">{{ detail.successRate }}%</el-descriptions-item>
        <el-descriptions-item label="工作人员延误">
          <el-tag size="small" :type="detail.staffDelayCount > 0 ? 'danger' : 'success'">{{ detail.staffDelayCount }} 次</el-tag>
        </el-descriptions-item>
      </el-descriptions>
      <el-table :data="detail?.changes || []" stripe size="small" class="mt">
        <el-table-column prop="caseNo" label="调解编号" width="150" />
        <el-table-column label="次数" width="64">
          <template #default="{ row }">第{{ row.roundNo }}次</template>
        </el-table-column>
        <el-table-column prop="cancelReason" label="取消原因" min-width="180" />
        <el-table-column label="发起方" width="170">
          <template #default="{ row }">
            <el-tag size="small" :type="row.changeSource === 'STAFF' ? 'danger' : 'warning'">
              {{ row.changeSourceLabel }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column label="绩效口径" width="120">
          <template #default="{ row }">
            <el-tag size="small" :type="row.countsAsDelay ? 'danger' : 'info'">
              {{ row.countsAsDelay ? '计入工作人员延误' : '当事人变更·免责' }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column label="窗口确认" width="100">
          <template #default="{ row }">
            <el-tag size="small" :type="row.windowConfirmed ? 'success' : 'info'">
              {{ row.windowConfirmed ? '已确认分流' : '待确认' }}
            </el-tag>
          </template>
        </el-table-column>
        <template #empty>该调解员暂无改期记录</template>
      </el-table>
    </el-dialog>
  </div>
</template>

<script setup>
import { onMounted, ref } from 'vue'
import api from '../api.js'

const rows = ref([])
const detailVisible = ref(false)
const detail = ref(null)

onMounted(async () => { rows.value = await api.get('/mediator-performance') })

const openDetail = async row => {
  detail.value = await api.get(`/mediator-performance/${row.mediatorId}`)
  detailVisible.value = true
}
</script>

<style scoped>
.mb { margin-bottom: 14px; }
.mt { margin-top: 12px; }
.head { display: flex; justify-content: space-between; align-items: center; }
</style>
