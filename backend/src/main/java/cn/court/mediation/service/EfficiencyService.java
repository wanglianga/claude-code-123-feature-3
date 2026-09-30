package cn.court.mediation.service;

import cn.court.mediation.common.BizException;
import cn.court.mediation.entity.MediationCase;
import cn.court.mediation.entity.MediationSchedule;
import cn.court.mediation.entity.RescheduleProposal;
import cn.court.mediation.enums.CaseStatus;
import cn.court.mediation.enums.ScheduleStatus;
import cn.court.mediation.repository.CaseRepository;
import cn.court.mediation.repository.RescheduleProposalRepository;
import cn.court.mediation.repository.ScheduleRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.*;

/**
 * 多次改期调解效率风险：
 * 累计改期达到阈值后生成案件分流建议（继续调解 / 确认分流转立案），
 * 由窗口人员决定；确认分流时自动汇编转立案材料包。
 */
@Service
public class EfficiencyService {

    /** 达到此次数即提示调解效率风险 */
    public static final int RISK_THRESHOLD = 2;

    private final CaseRepository caseRepository;
    private final ScheduleRepository scheduleRepository;
    private final RescheduleProposalRepository proposalRepository;
    private final TransferService transferService;
    private final TimelineService timelineService;

    public EfficiencyService(CaseRepository caseRepository, ScheduleRepository scheduleRepository,
                             RescheduleProposalRepository proposalRepository,
                             TransferService transferService, TimelineService timelineService) {
        this.caseRepository = caseRepository;
        this.scheduleRepository = scheduleRepository;
        this.proposalRepository = proposalRepository;
        this.transferService = transferService;
        this.timelineService = timelineService;
    }

    /** 计算案件的效率风险与分流建议 */
    @Transactional(readOnly = true)
    public Map<String, Object> suggestion(Long caseId) {
        MediationCase c = caseRepository.findById(caseId).orElseThrow(() -> new BizException("案件不存在"));
        List<MediationSchedule> schedules = scheduleRepository.findByCaseIdOrderByStartTimeDesc(caseId);
        long partyCaused = schedules.stream()
                .filter(s -> s.getStatus() == ScheduleStatus.RESCHEDULED && Boolean.TRUE.equals(s.getPartyCaused()))
                .count();
        long staffCaused = schedules.stream()
                .filter(s -> s.getStatus() == ScheduleStatus.RESCHEDULED
                        && "STAFF".equals(s.getChangeSource()))
                .count();
        long pendingConfirm = schedules.stream()
                .filter(s -> s.getStatus() == ScheduleStatus.RESCHEDULED
                        && !Boolean.TRUE.equals(s.getWindowConfirmed())).count();
        boolean pendingProposal = proposalRepository
                .findFirstByCaseIdAndStatusOrderByProposedAtDesc(caseId, "PROPOSED").isPresent();

        int count = c.getRescheduleCount() == null ? 0 : c.getRescheduleCount();
        boolean risk = count >= RISK_THRESHOLD && isActive(c);

        List<String> reasons = new ArrayList<>();
        if (risk) {
            reasons.add("已累计改期" + count + "次（其中当事人/双方临时变更" + partyCaused + "次）");
            reasons.add("多次改期拉长调解周期，存在调解效率风险");
            if (Boolean.TRUE.equals(c.getRespondentMissing())) reasons.add("被申请人曾登记失联");
            if (Boolean.FALSE.equals(c.getEvidenceComplete())) reasons.add("证据材料尚未补齐");
        }

        // 分流建议：风险且当事人原因占主导，建议窗口复核；失联叠加时建议转立案
        String suggestion = "NONE";
        if (risk) {
            suggestion = Boolean.TRUE.equals(c.getRespondentMissing()) ? "TRIAGE" : "REVIEW";
        }

        Map<String, Object> m = new LinkedHashMap<>();
        m.put("caseId", caseId);
        m.put("rescheduleCount", count);
        m.put("threshold", RISK_THRESHOLD);
        m.put("risk", risk);
        m.put("partyCausedCount", partyCaused);
        m.put("staffCausedCount", staffCaused);
        m.put("windowUnconfirmedCount", pendingConfirm);
        m.put("pendingProposal", pendingProposal);
        m.put("suggestion", suggestion);
        m.put("reasons", reasons);
        m.put("decision", c.getEfficiencyDecision());
        m.put("efficiencyNote", c.getEfficiencyNote());
        m.put("decidedAt", c.getEfficiencyDecidedAt());
        m.put("transferRequested", c.getTransferRequested());
        m.put("status", c.getStatus().name());
        return m;
    }

    /**
     * 窗口人员对效率风险的决定：
     * CONTINUE 继续调解；TRIAGE 确认分流（自动按多次改期原因汇编转立案材料包，进入窗口接收）。
     */
    @Transactional
    public Map<String, Object> decide(Long caseId, Map<String, Object> body, cn.court.mediation.entity.User operator) {
        MediationCase c = caseRepository.findById(caseId).orElseThrow(() -> new BizException("案件不存在"));
        int count = c.getRescheduleCount() == null ? 0 : c.getRescheduleCount();
        if (count < RISK_THRESHOLD) {
            throw new BizException("改期未达" + RISK_THRESHOLD + "次，暂不触发效率风险分流");
        }
        String decision = (String) body.getOrDefault("decision", "CONTINUE");
        if (!"CONTINUE".equals(decision) && !"TRIAGE".equals(decision)) {
            throw new BizException("决定类型无效");
        }
        String note = (String) body.getOrDefault("note", "");

        c.setEfficiencyDecision(decision);
        c.setEfficiencyNote(note);
        c.setEfficiencyDecidedBy(operator.getId());
        c.setEfficiencyDecidedAt(LocalDateTime.now());
        caseRepository.save(c);

        Map<String, Object> result = new LinkedHashMap<>();
        if ("CONTINUE".equals(decision)) {
            timelineService.record(caseId, "RESCHEDULE", "窗口决定：继续调解",
                    String.format("案件已改期%d次触发调解效率风险，窗口人员%s复核后决定继续调解。%s",
                            count, operator.getRealName(), note.isBlank() ? "" : "复核意见：" + note),
                    operator);
            result.put("transferMaterial", null);
        } else {
            // 窗口确认分流：自动汇编转立案材料包（多次改期原因）
            Map<String, Object> req = new HashMap<>();
            req.put("reasonType", "RESCHEDULE");
            req.put("reasonDetail", "多次改期（累计" + count + "次）调解效率风险，窗口复核确认分流。"
                    + (note.isBlank() ? "" : "意见：" + note));
            Object tm = transferService.prepareTransfer(caseId, req, operator);
            timelineService.record(caseId, "RESCHEDULE", "窗口确认分流：进入转立案",
                    "窗口人员" + operator.getRealName() + "复核多次改期效率风险后确认分流，系统已自动汇编全过程材料包，待立案窗口接收登记诉讼案号。",
                    operator);
            result.put("transferMaterial", tm);
        }
        result.put("decision", decision);
        return result;
    }

    private boolean isActive(MediationCase c) {
        return c.getStatus() != CaseStatus.TRANSFERRED
                && c.getStatus() != CaseStatus.CLOSED
                && c.getStatus() != CaseStatus.MEDIATION_FAILED
                && c.getStatus() != CaseStatus.PERFORMED_DONE;
    }

    /** 工作台：待窗口决定的效率风险案件 */
    @Transactional(readOnly = true)
    public List<Map<String, Object>> pendingDecisions() {
        List<Map<String, Object>> out = new ArrayList<>();
        for (MediationCase c : caseRepository.findAll()) {
            int count = c.getRescheduleCount() == null ? 0 : c.getRescheduleCount();
            if (count >= RISK_THRESHOLD && c.getEfficiencyDecision() == null
                    && !Boolean.TRUE.equals(c.getTransferRequested()) && isActive(c)) {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("caseId", c.getId());
                m.put("caseNo", c.getCaseNo());
                m.put("rescheduleCount", count);
                m.put("applicantName", c.getApplicantName());
                m.put("respondentName", c.getRespondentName());
                out.add(m);
            }
        }
        return out;
    }

    /** 工作台：待窗口确认的重匹配建议 */
    @Transactional(readOnly = true)
    public List<RescheduleProposal> pendingProposals() {
        return proposalRepository.findByStatusOrderByProposedAtDesc("PROPOSED");
    }
}
