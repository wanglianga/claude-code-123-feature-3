// 枚举标签（与后端 enums 保持一致）
export const CASE_TYPES = {
  LOAN: '民间借贷纠纷',
  PROPERTY_FEE: '物业费纠纷',
  TRAFFIC: '交通事故纠纷',
  NEIGHBOR: '邻里纠纷',
  SALES: '小额买卖合同纠纷'
}

export const CASE_STATUS = {
  PENDING_TRIAGE: '待分流',
  TRIAGED: '已分流待排期',
  SCHEDULED: '已排期待调解',
  MEDIATING: '调解中',
  MEDIATION_SUCCESS: '调解成功',
  MEDIATION_FAILED: '调解失败',
  JUDICIAL_CONFIRMING: '司法确认审查中',
  PERFORMING: '履行中',
  PERFORMED_DONE: '履行完毕',
  OVERDUE: '履行逾期',
  TRANSFERRED: '已转立案',
  CLOSED: '已归档'
}

export const STATUS_TAG = {
  PENDING_TRIAGE: 'danger',
  TRIAGED: 'warning',
  SCHEDULED: 'primary',
  MEDIATING: 'primary',
  MEDIATION_SUCCESS: 'success',
  MEDIATION_FAILED: 'info',
  JUDICIAL_CONFIRMING: 'warning',
  PERFORMING: '',
  PERFORMED_DONE: 'success',
  OVERDUE: 'danger',
  TRANSFERRED: 'info',
  CLOSED: 'info'
}

export const URGENCY = { NORMAL: '普通', URGENT: '加急', EMERGENCY: '特急' }
export const URGENCY_TAG = { NORMAL: 'info', URGENT: 'warning', EMERGENCY: 'danger' }
export const CHANNEL = { ONLINE: '线上调解', OFFLINE: '线下调解' }

export const RISK_LABELS = {
  RESPONDENT_MISSING: '被申请人失联',
  EVIDENCE_MISSING: '证据材料缺失',
  REPEAT_RESCHEDULE: '多次改期',
  DIVERSION_PENDING: '多次改期·待窗口决定是否继续',
  DIVERTED: '窗口已确认分流',
  INSTALLMENT: '分期履行',
  OVERDUE: '付款逾期/部分履行',
  TRANSFER_REQUESTED: '已申请转立案'
}

export const RISK_TAG = {
  RESPONDENT_MISSING: 'danger',
  EVIDENCE_MISSING: 'danger',
  REPEAT_RESCHEDULE: 'warning',
  DIVERSION_PENDING: 'danger',
  DIVERTED: 'info',
  INSTALLMENT: 'info',
  OVERDUE: 'danger',
  TRANSFER_REQUESTED: 'warning'
}

export const RESCHEDULE_INITIATORS = {
  APPLICANT: '申请人临时改期',
  RESPONDENT: '被申请人临时改期',
  BOTH_PARTIES: '双方时间冲突改期',
  MEDIATOR: '调解员排班冲突',
  COURT: '院方/平台原因'
}

// 是否属于当事人临时变更（不计工作人员延误）
export const INITIATOR_PARTY_SIDE = {
  APPLICANT: true,
  RESPONDENT: true,
  BOTH_PARTIES: true,
  MEDIATOR: false,
  COURT: false
}

export const RESOURCE_TYPES = {
  OFFLINE_ROOM: '线下调解室',
  ONLINE_MEETING: '线上视频会议'
}

export const PLAN_STATUS = {
  PENDING: { label: '待履行', tag: 'info' },
  PAID: { label: '已履行', tag: 'success' },
  PARTIAL: { label: '部分履行', tag: 'warning' },
  OVERDUE: { label: '已逾期', tag: 'danger' }
}

export const TRANSFER_REASONS = {
  MISSING: '被申请人失联，调解无法进行',
  EVIDENCE: '证据材料争议较大，需诉讼查明',
  REJECT: '一方明确拒绝调解',
  OVERDUE: '协议达成后付款逾期/拒不履行',
  OTHER: '其他不适宜继续调解情形'
}

export const FOLLOW_RESULTS = {
  NORMAL: '履约正常', PARTIAL: '部分履行', OVERDUE: '仍处逾期', DONE: '已履行完毕', OBJECTION: '有异议'
}

export const hasRole = (user, ...roles) => roles.includes(user?.role)
