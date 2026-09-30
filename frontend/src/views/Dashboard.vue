<template>
  <div v-if="d">
    <el-row :gutter="12">
      <el-col :span="4" v-for="k in kpis" :key="k.l">
        <el-card shadow="hover" class="kpi">
          <div class="n" :style="{ color: k.c }">{{ k.v }}</div>
          <div class="l">{{ k.l }}</div>
        </el-card>
      </el-col>
    </el-row>

    <el-row :gutter="12" class="mt">
      <el-col :span="12">
        <el-card shadow="never">
          <template #header><b>案由分布（调解成功/转立案对比）</b></template>
          <div ref="typeChart" style="height:320px"></div>
        </el-card>
      </el-col>
      <el-col :span="12">
        <el-card shadow="never">
          <template #header><b>案件状态分布</b></template>
          <div ref="statusChart" style="height:320px"></div>
        </el-card>
      </el-col>
    </el-row>

    <el-row :gutter="12" class="mt">
      <el-col :span="12">
        <el-card shadow="never">
          <template #header><b>协议履行情况</b></template>
          <el-descriptions :column="2" border size="small">
            <el-descriptions-item label="履行节点总数">{{ d.performance.totalNodes }}</el-descriptions-item>
            <el-descriptions-item label="履行到位率">{{ d.performance.fulfillRate }}%</el-descriptions-item>
            <el-descriptions-item label="应履行总额">¥{{ fmtMoney(d.performance.dueSum) }}</el-descriptions-item>
            <el-descriptions-item label="已到账总额">¥{{ fmtMoney(d.performance.paidSum) }}</el-descriptions-item>
            <el-descriptions-item label="已足额履行">{{ d.performance.paid }} 个节点</el-descriptions-item>
            <el-descriptions-item label="部分履行">{{ d.performance.partial }} 个节点</el-descriptions-item>
            <el-descriptions-item label="逾期未付">
              <span style="color:#f56c6c;font-weight:700">{{ d.performance.overdue }} 个节点</span>
            </el-descriptions-item>
            <el-descriptions-item label="待到期">{{ d.performance.pending }} 个节点</el-descriptions-item>
          </el-descriptions>
          <div ref="perfChart" style="height:220px" class="mt"></div>
        </el-card>
      </el-col>
      <el-col :span="12">
        <el-card shadow="never">
          <template #header><b>转立案原因分布</b></template>
          <div ref="reasonChart" style="height:260px"></div>
          <el-divider content-position="left">过程风险统计</el-divider>
          <el-descriptions :column="1" border size="small">
            <el-descriptions-item label="多次改期案件（≥2次）">
              <el-tag type="warning">{{ d.risks.repeatReschedule }} 件</el-tag>
            </el-descriptions-item>
            <el-descriptions-item label="多次改期·待窗口决定">
              <el-tag type="danger">{{ d.risks.diversionPending ?? 0 }} 件</el-tag>
            </el-descriptions-item>
            <el-descriptions-item label="窗口已研判（继续/分流）">
              <el-tag type="info">{{ d.risks.diversionDecided ?? 0 }} 件</el-tag>
            </el-descriptions-item>
            <el-descriptions-item label="被申请人失联在办">
              <el-tag type="danger">{{ d.risks.missing }} 件</el-tag>
            </el-descriptions-item>
            <el-descriptions-item label="证据材料缺件">
              <el-tag type="danger">{{ d.risks.evidenceGap }} 件</el-tag>
            </el-descriptions-item>
            <el-descriptions-item label="改期·当事人临时变更（不计工作人员延误）">
              <el-tag type="warning">{{ d.risks.partySideReschedules ?? 0 }} 次</el-tag>
            </el-descriptions-item>
            <el-descriptions-item label="改期·工作人员原因（计入排班延误）">
              <el-tag :type="(d.risks.staffSideReschedules ?? 0) > 0 ? 'danger' : 'info'">{{ d.risks.staffSideReschedules ?? 0 }} 次</el-tag>
            </el-descriptions-item>
          </el-descriptions>
        </el-card>
      </el-col>
    </el-row>
  </div>
</template>

<script setup>
import { onMounted, ref, computed, nextTick } from 'vue'
import * as echarts from 'echarts'
import api from '../api.js'

const d = ref(null)
const typeChart = ref(), statusChart = ref(), perfChart = ref(), reasonChart = ref()

const kpis = computed(() => {
  const k = d.value.kpi
  return [
    { l: '案件总数', v: k.total, c: '#1d3c6e' },
    { l: '待分流', v: k.pendingTriage, c: '#f56c6c' },
    { l: '调解中', v: k.mediating, c: '#409eff' },
    { l: '调解成功率', v: k.successRate + '%', c: '#67c23a' },
    { l: '转立案率', v: k.transferRate + '%', c: '#e6a23c' },
    { l: '履行逾期', v: k.overdue, c: '#f56c6c' }
  ]
})
const fmtMoney = v => Number(v || 0).toLocaleString('zh-CN', { minimumFractionDigits: 2 })

onMounted(async () => {
  d.value = await api.get('/dashboard')
  await nextTick()
  render()
})

const render = () => {
  // 案由对比
  echarts.init(typeChart.value).setOption({
    tooltip: { trigger: 'axis' },
    legend: { data: ['案件数', '调解成功', '转立案'], bottom: 0 },
    grid: { left: 40, right: 20, top: 20, bottom: 50 },
    xAxis: { type: 'category', data: d.value.byType.map(x => x.label.replace('纠纷', '')) },
    yAxis: { type: 'value', minInterval: 1 },
    series: [
      { name: '案件数', type: 'bar', data: d.value.byType.map(x => x.count), itemStyle: { color: '#409eff' } },
      { name: '调解成功', type: 'bar', data: d.value.byType.map(x => x.success), itemStyle: { color: '#67c23a' } },
      { name: '转立案', type: 'bar', data: d.value.byType.map(x => x.transferred), itemStyle: { color: '#e6a23c' } }
    ]
  })
  // 状态饼图
  echarts.init(statusChart.value).setOption({
    tooltip: { trigger: 'item' },
    legend: { type: 'scroll', bottom: 0 },
    series: [{
      type: 'pie', radius: ['40%', '65%'], center: ['50%', '45%'],
      data: d.value.byStatus.map(x => ({ name: x.label, value: x.count })),
      label: { formatter: '{b}:{c}' }
    }]
  })
  // 履行
  echarts.init(perfChart.value).setOption({
    tooltip: { trigger: 'item' },
    legend: { bottom: 0 },
    series: [{
      type: 'pie', radius: '60%', center: ['50%', '45%'],
      data: [
        { name: '已履行', value: d.value.performance.paid, itemStyle: { color: '#67c23a' } },
        { name: '部分履行', value: d.value.performance.partial, itemStyle: { color: '#e6a23c' } },
        { name: '逾期', value: d.value.performance.overdue, itemStyle: { color: '#f56c6c' } },
        { name: '待履行', value: d.value.performance.pending, itemStyle: { color: '#909399' } }
      ]
    }]
  })
  // 转立案原因
  const reasons = d.value.transferReasons
  echarts.init(reasonChart.value).setOption({
    tooltip: { trigger: 'axis' },
    grid: { left: 10, right: 30, top: 10, bottom: 20, containLabel: true },
    xAxis: { type: 'value', minInterval: 1 },
    yAxis: { type: 'category', data: Object.keys(reasons).map(x => x.length > 14 ? x.substring(0, 14) + '…' : x) },
    series: [{ type: 'bar', data: Object.values(reasons), itemStyle: { color: '#e6a23c' }, label: { show: true, position: 'right' } }]
  })
}
</script>

<style scoped>
.kpi { text-align: center; }
.kpi .n { font-size: 26px; font-weight: 700; }
.kpi .l { color: #909399; font-size: 12px; margin-top: 4px; }
.mt { margin-top: 12px; }
</style>
