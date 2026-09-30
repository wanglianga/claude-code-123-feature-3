package cn.court.mediation.service;

import cn.court.mediation.common.BizException;
import cn.court.mediation.entity.MediationCase;
import cn.court.mediation.entity.MediationSchedule;
import cn.court.mediation.enums.CaseStatus;
import cn.court.mediation.enums.ScheduleStatus;
import cn.court.mediation.repository.CaseRepository;
import cn.court.mediation.repository.ScheduleRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.*;

/**
 * 案件分流建议：多次改期（≥2次）触发调解效率风险，案件自动进入分流建议清单；
 * 由窗口人员判断"继续调解"或"确认分流"。确认分流的案件衔接既有转立案材料汇编流程。
 */
@Service
public class DivertService {

    public static final int SUGGEST_THRESHOLD = 2;
    private static final Set<CaseStatus> ACTIVE_STATUSES = EnumSet.of(
            CaseStatus.TRIAGED, CaseStatus.SCHEDULED, CaseStatus.MEDIATING,
            CaseStatus.OVERDUE, CaseStatus.PENDING_TRIAGE);

    private final CaseRepository caseRepository;
    private final ScheduleRepository scheduleRepository;
    private final TimelineService timelineService;

    public DivertService(CaseRepository caseRepository, ScheduleRepository scheduleRepository,
                         TimelineService timelineService) {
        this.caseRepository = caseRepository;
        this.scheduleRepository = scheduleRepository;
        this.timelineService = timelineService;
    }

    /** 进入分流建议的案件：多次改期且仍在调解进程（已正式转立案/归档/失败的不再提示） */
    @Transactional(readOnly = true)
    public Map<String, Object> suggestions() {
        List<Map<String, Object>> rows = new ArrayList<>();
        for (MediationCase c : caseRepository.findAll()) {
            int count = c.getRescheduleCount() == null ? 0 : c.getRescheduleCount();
            if (count < SUGGEST_THRESHOLD || !ACTIVE_STATUSES.contains(c.getStatus())) continue;
            rows.add(buildRow(c, count));
        }
        rows.sort((a, b) -> ((Integer) b.get("rescheduleCount")) - ((Integer) a.get("rescheduleCount")));

        long pending = rows.stream().filter(r -> Boolean.FALSE.equals(r.get("reviewed"))).count();
        long diverted = rows.stream().filter(r -> "DIVERT".equals(r.get("decision"))).count();
        long continued = rows.stream().filter(r -> "CONTINUE".equals(r.get("decision"))).count();

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("suggestions", rows);
        result.put("total", rows.size());
        result.put("pendingReview", pending);
        result.put("diverted", diverted);
        result.put("continued", continued);
        return result;
    }

    private Map<String, Object> buildRow(MediationCase c, int count) {
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("caseId", c.getId());
        r.put("caseNo", c.getCaseNo());
        r.put("caseType", c.getCaseType().name());
        r.put("typeLabel", c.getCaseType().getLabel());
        r.put("applicantName", c.getApplicantName());
        r.put("respondentName", c.getRespondentName());
        r.put("status", c.getStatus().name());
        r.put("statusLabel", c.getStatus().getLabel());
        r.put("rescheduleCount", count);
        r.put("riskLevel", c.getRescheduleRiskLevel() == null ? 0 : c.getRescheduleRiskLevel());
        r.put("riskLabel", riskLabel(c.getRescheduleRiskLevel(), count));
        r.put("reviewed", Boolean.TRUE.equals(c.getContinuationReviewed()));
        r.put("decision", c.getContinuationDecision());
        r.put("decisionLabel", "CONTINUE".equals(c.getContinuationDecision()) ? "继续调解"
                : "DIVERT".equals(c.getContinuationDecision()) ? "已确认分流" : "待窗口决定");
        r.put("continuationNote", c.getContinuationNote());
        r.put("mediatorId", c.getMediatorId());

        // 最近一次改期的取消原因与发起方：按轮次 roundNo 最大取最近一次（startTime 为原排期时间，不能据此判断先后）
        List<MediationSchedule> history = scheduleRepository.findByCaseIdOrderByStartTimeDesc(c.getId());
        history.stream().filter(s -> s.getStatus() == ScheduleStatus.RESCHEDULED)
                .max(Comparator.comparingInt(s -> s.getRoundNo() == null ? 0 : s.getRoundNo()))
                .ifPresent(last -> {
            r.put("lastCancelReason", last.getCancelReason());
            r.put("lastInitiator", last.getInitiator() == null ? null : last.getInitiator().name());
            r.put("lastInitiatorLabel", last.getInitiator() == null ? "双方当事人" : last.getInitiator().getLabel());
        });
        // 当前有效排期（轮次最大的待开始排期）的双方确认情况
        history.stream().filter(s -> s.getStatus() == ScheduleStatus.PLANNED)
                .max(Comparator.comparingInt(s -> s.getRoundNo() == null ? 0 : s.getRoundNo()))
                .ifPresent(cur -> {
            r.put("applicantConfirmed", Boolean.TRUE.equals(cur.getApplicantConfirmed()));
            r.put("respondentConfirmed", Boolean.TRUE.equals(cur.getRespondentConfirmed()));
        });
        r.put("transferRequested", Boolean.TRUE.equals(c.getTransferRequested()));
        return r;
    }

    /** 窗口决定：继续调解 / 确认分流 */
    @Transactional
    public MediationCase review(Long caseId, Map<String, Object> body, cn.court.mediation.entity.User operator) {
        MediationCase c = caseRepository.findById(caseId).orElseThrow(() -> new BizException("案件不存在"));
        int count = c.getRescheduleCount() == null ? 0 : c.getRescheduleCount();
        if (count < SUGGEST_THRESHOLD) {
            throw new BizException("该案件改期未达" + SUGGEST_THRESHOLD + "次，暂不进入分流建议");
        }
        String decision = (String) body.getOrDefault("decision", "CONTINUE");
        if (!"CONTINUE".equals(decision) && !"DIVERT".equals(decision)) {
            throw new BizException("决定类型仅支持 CONTINUE（继续调解）/ DIVERT（确认分流）");
        }
        String note = (String) body.getOrDefault("note", "");

        c.setContinuationReviewed(true);
        c.setContinuationDecision(decision);
        c.setContinuationNote(note);
        c.setContinuationReviewedAt(LocalDateTime.now());
        c.setContinuationReviewedBy(operator.getId());
        caseRepository.save(c);

        if ("DIVERT".equals(decision)) {
            timelineService.record(caseId, "TRIAGE", "窗口确认案件分流",
                    String.format("该案件已累计改期%d次，调解效率风险%s，窗口研判后确认分流，不再继续排期调解。%s",
                            count, riskLabel(c.getRescheduleRiskLevel(), count),
                            note.isBlank() ? "" : "分流意见：" + note)
                            + " 下一步可一键汇编转立案材料包，由窗口接收登记诉讼案号。",
                    operator);
        } else {
            timelineService.record(caseId, "TRIAGE", "窗口决定继续调解",
                    String.format("该案件已累计改期%d次，经窗口研判仍具备调解基础，决定继续调解。%s",
                            count, note.isBlank() ? "" : "意见：" + note),
                    operator);
        }
        return c;
    }

    private String riskLabel(Integer level, int count) {
        int lvl = level == null ? (count >= 3 ? 3 : count >= 2 ? 2 : 0) : level;
        return switch (lvl) {
            case 3 -> "红色（多次改期，建议分流）";
            case 2 -> "黄色（多次改期，效率风险）";
            default -> "提示";
        };
    }
}
