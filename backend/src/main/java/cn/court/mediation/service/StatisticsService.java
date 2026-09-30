package cn.court.mediation.service;

import cn.court.mediation.entity.MediationCase;
import cn.court.mediation.entity.MediationSchedule;
import cn.court.mediation.entity.PerformancePlan;
import cn.court.mediation.enums.CaseStatus;
import cn.court.mediation.enums.CaseType;
import cn.court.mediation.enums.RescheduleInitiator;
import cn.court.mediation.enums.ScheduleStatus;
import cn.court.mediation.repository.CaseRepository;
import cn.court.mediation.repository.PerformancePlanRepository;
import cn.court.mediation.repository.ScheduleRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.*;

/** 统计看板：调解效率、协议履行、转立案原因 */
@Service
public class StatisticsService {

    private final CaseRepository caseRepository;
    private final PerformancePlanRepository planRepository;
    private final ScheduleRepository scheduleRepository;

    public StatisticsService(CaseRepository caseRepository, PerformancePlanRepository planRepository,
                             ScheduleRepository scheduleRepository) {
        this.caseRepository = caseRepository;
        this.planRepository = planRepository;
        this.scheduleRepository = scheduleRepository;
    }

    private static final EnumSet<CaseStatus> ACTIVE_DIVERT = EnumSet.of(
            CaseStatus.TRIAGED, CaseStatus.SCHEDULED, CaseStatus.MEDIATING,
            CaseStatus.OVERDUE, CaseStatus.PENDING_TRIAGE);

    @Transactional(readOnly = true)
    public Map<String, Object> dashboard() {
        List<MediationCase> all = caseRepository.findAll();
        Map<String, Object> m = new LinkedHashMap<>();

        long total = all.size();
        long pendingTriage = all.stream().filter(c -> c.getStatus() == CaseStatus.PENDING_TRIAGE).count();
        long mediating = all.stream().filter(c -> EnumSet.of(
                CaseStatus.TRIAGED, CaseStatus.SCHEDULED, CaseStatus.MEDIATING).contains(c.getStatus())).count();
        long success = all.stream().filter(c -> EnumSet.of(
                CaseStatus.MEDIATION_SUCCESS, CaseStatus.JUDICIAL_CONFIRMING,
                CaseStatus.PERFORMING, CaseStatus.PERFORMED_DONE, CaseStatus.OVERDUE).contains(c.getStatus())).count();
        long failed = all.stream().filter(c -> c.getStatus() == CaseStatus.MEDIATION_FAILED).count();
        long transferred = all.stream().filter(c -> c.getStatus() == CaseStatus.TRANSFERRED).count();
        long overdue = all.stream().filter(c -> c.getStatus() == CaseStatus.OVERDUE).count();
        long missing = all.stream().filter(c -> Boolean.TRUE.equals(c.getRespondentMissing())
                && c.getStatus() != CaseStatus.TRANSFERRED && c.getStatus() != CaseStatus.CLOSED).count();

        Map<String, Object> kpi = new LinkedHashMap<>();
        kpi.put("total", total);
        kpi.put("pendingTriage", pendingTriage);
        kpi.put("mediating", mediating);
        kpi.put("success", success);
        kpi.put("failed", failed);
        kpi.put("transferred", transferred);
        kpi.put("overdue", overdue);
        kpi.put("missing", missing);
        kpi.put("successRate", total == 0 ? 0 : Math.round(success * 1000.0 / total) / 10.0);
        kpi.put("transferRate", total == 0 ? 0 : Math.round(transferred * 1000.0 / total) / 10.0);
        m.put("kpi", kpi);

        // 按案由分布
        List<Map<String, Object>> byType = new ArrayList<>();
        for (CaseType t : CaseType.values()) {
            List<MediationCase> sub = all.stream().filter(c -> c.getCaseType() == t).toList();
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("type", t.name());
            row.put("label", t.getLabel());
            row.put("count", sub.size());
            row.put("success", sub.stream().filter(c -> EnumSet.of(
                    CaseStatus.MEDIATION_SUCCESS, CaseStatus.JUDICIAL_CONFIRMING,
                    CaseStatus.PERFORMING, CaseStatus.PERFORMED_DONE, CaseStatus.OVERDUE).contains(c.getStatus())).count());
            row.put("transferred", sub.stream().filter(c -> c.getStatus() == CaseStatus.TRANSFERRED).count());
            byType.add(row);
        }
        m.put("byType", byType);

        // 按状态分布
        List<Map<String, Object>> byStatus = new ArrayList<>();
        for (CaseStatus s : CaseStatus.values()) {
            long n = all.stream().filter(c -> c.getStatus() == s).count();
            if (n > 0) {
                byStatus.add(Map.of("status", s.name(), "label", s.getLabel(), "count", n));
            }
        }
        m.put("byStatus", byStatus);

        // 履行情况
        List<PerformancePlan> plans = planRepository.findAll();
        Map<String, Object> perf = new LinkedHashMap<>();
        long paid = plans.stream().filter(p -> "PAID".equals(p.getStatus())).count();
        long partial = plans.stream().filter(p -> "PARTIAL".equals(p.getStatus())).count();
        long pod = plans.stream().filter(p -> "OVERDUE".equals(p.getStatus())).count();
        long pending = plans.stream().filter(p -> "PENDING".equals(p.getStatus())).count();
        BigDecimal dueSum = plans.stream().map(PerformancePlan::getDueAmount).filter(Objects::nonNull).reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal paidSum = plans.stream().map(PerformancePlan::getPaidAmount).filter(Objects::nonNull).reduce(BigDecimal.ZERO, BigDecimal::add);
        perf.put("totalNodes", plans.size());
        perf.put("paid", paid);
        perf.put("partial", partial);
        perf.put("overdue", pod);
        perf.put("pending", pending);
        perf.put("dueSum", dueSum);
        perf.put("paidSum", paidSum);
        perf.put("fulfillRate", dueSum.compareTo(BigDecimal.ZERO) == 0 ? 0
                : Math.round(paidSum.doubleValue() * 1000.0 / dueSum.doubleValue()) / 10.0);
        m.put("performance", perf);

        // 转立案原因分布
        Map<String, Long> reasonDist = new LinkedHashMap<>();
        all.stream().filter(c -> c.getStatus() == CaseStatus.TRANSFERRED)
                .forEach(c -> {
                    String r = c.getTransferReason() == null ? "其他" : c.getTransferReason();
                    reasonDist.merge(r, 1L, Long::sum);
                });
        m.put("transferReasons", reasonDist);

        // 改期与失联风险
        Map<String, Object> risks = new LinkedHashMap<>();
        List<MediationCase> repeatCases = all.stream()
                .filter(c -> c.getRescheduleCount() != null && c.getRescheduleCount() >= 2).toList();
        risks.put("repeatReschedule", repeatCases.size());
        risks.put("diversionPending", repeatCases.stream()
                .filter(c -> !Boolean.TRUE.equals(c.getContinuationReviewed())
                        && ACTIVE_DIVERT.contains(c.getStatus())).count());
        risks.put("diversionDecided", repeatCases.stream()
                .filter(c -> Boolean.TRUE.equals(c.getContinuationReviewed())).count());
        risks.put("missing", missing);
        risks.put("evidenceGap", all.stream().filter(c -> Boolean.FALSE.equals(c.getEvidenceComplete())).count());

        // 改期归责：当事人临时变更 vs 工作人员原因（工作人员原因才计入延误）
        long partySide = 0, staffSide = 0;
        List<MediationSchedule> allRescheduled = scheduleRepository.findAll().stream()
                .filter(s -> s.getStatus() == ScheduleStatus.RESCHEDULED).toList();
        for (MediationSchedule s : allRescheduled) {
            RescheduleInitiator init = s.getInitiator() == null
                    ? RescheduleInitiator.BOTH_PARTIES : s.getInitiator();
            if (init.isPartySide()) partySide++; else staffSide++;
        }
        risks.put("partySideReschedules", partySide);
        risks.put("staffSideReschedules", staffSide);
        m.put("risks", risks);

        return m;
    }

    /** 各角色工作台待办 */
    @Transactional(readOnly = true)
    public Map<String, Object> todos(cn.court.mediation.entity.User user) {
        List<MediationCase> all = caseRepository.findAll();
        List<Map<String, Object>> todos = new ArrayList<>();
        LocalDate today = LocalDate.now();

        for (MediationCase c : all) {
            String action = null;
            String tag = null;
            switch (user.getRole()) {
                case WINDOW, ADMIN -> {
                    if (c.getStatus() == CaseStatus.PENDING_TRIAGE) {
                        action = "待分流"; tag = "danger";
                    } else if (Boolean.TRUE.equals(c.getTransferRequested())
                            && c.getStatus() != CaseStatus.TRANSFERRED) {
                        action = "转立案材料待接收"; tag = "warning";
                    } else if (c.getRescheduleCount() != null && c.getRescheduleCount() >= 2
                            && !Boolean.TRUE.equals(c.getContinuationReviewed())
                            && EnumSet.of(CaseStatus.TRIAGED, CaseStatus.SCHEDULED,
                                    CaseStatus.MEDIATING, CaseStatus.OVERDUE).contains(c.getStatus())) {
                        action = "多次改期，待决定继续/分流"; tag = "warning";
                    } else if (Boolean.FALSE.equals(c.getEvidenceComplete())) {
                        action = "证据材料缺件跟进"; tag = "warning";
                    }
                }
                case MEDIATOR -> {
                    if (c.getMediatorId() != null && c.getMediatorId().equals(user.getId())) {
                        if (c.getStatus() == CaseStatus.TRIAGED) { action = "待排期"; tag = "danger"; }
                        if (c.getStatus() == CaseStatus.SCHEDULED) { action = "已排期待调解"; tag = "primary"; }
                        if (c.getStatus() == CaseStatus.MEDIATING) { action = "调解进行中"; tag = "primary"; }
                        if (Boolean.TRUE.equals(c.getRespondentMissing())) { action = "被申请人失联，需联系"; tag = "danger"; }
                    }
                }
                case PARTY -> {
                    if (c.getApplicantUserId() != null && c.getApplicantUserId().equals(user.getId())) {
                        if (c.getStatus() == CaseStatus.SCHEDULED) { action = "请确认出席并参加调解"; tag = "primary"; }
                        if (c.getStatus() == CaseStatus.OVERDUE) { action = "对方付款逾期，可申请转立案"; tag = "danger"; }
                        if (c.getStatus() == CaseStatus.PERFORMING) { action = "协议履行中，请关注到账"; tag = "info"; }
                    }
                }
                case ASSISTANT -> {
                    if (c.getStatus() == CaseStatus.JUDICIAL_CONFIRMING) { action = "司法确认申请待审查"; tag = "warning"; }
                    if (Boolean.TRUE.equals(c.getTransferRequested()) && c.getStatus() != CaseStatus.TRANSFERRED) {
                        action = "转立案材料待审核"; tag = "warning";
                    }
                }
                case EXECUTION -> {
                    if (c.getStatus() == CaseStatus.PERFORMING || c.getStatus() == CaseStatus.OVERDUE) {
                        action = c.getStatus() == CaseStatus.OVERDUE ? "逾期履行需跟进/回访" : "分期履行跟踪回访";
                        tag = c.getStatus() == CaseStatus.OVERDUE ? "danger" : "info";
                    }
                }
            }
            if (action != null) {
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("caseId", c.getId());
                row.put("caseNo", c.getCaseNo());
                row.put("typeLabel", c.getCaseType().getLabel());
                row.put("applicantName", c.getApplicantName());
                row.put("respondentName", c.getRespondentName());
                row.put("statusLabel", c.getStatus().getLabel());
                row.put("action", action);
                row.put("tag", tag);
                todos.add(row);
            }
        }
        return Map.of("todos", todos, "today", today.toString());
    }
}
