package cn.court.mediation.service;

import cn.court.mediation.common.BizException;
import cn.court.mediation.dto.CaseCreateRequest;
import cn.court.mediation.entity.*;
import cn.court.mediation.enums.CaseStatus;
import cn.court.mediation.enums.CaseType;
import cn.court.mediation.enums.Channel;
import cn.court.mediation.enums.Role;
import cn.court.mediation.repository.*;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.stream.Collectors;

@Service
public class CaseService {

    private final CaseRepository caseRepository;
    private final UserRepository userRepository;
    private final TimelineService timelineService;
    private final ScheduleRepository scheduleRepository;
    private final MediationRecordRepository recordRepository;
    private final AgreementRepository agreementRepository;
    private final PerformancePlanRepository planRepository;
    private final TimelineRepository timelineRepository;
    private final DeliveryRepository deliveryRepository;
    private final FollowUpRepository followUpRepository;
    private final FaceVerificationRepository faceRepository;
    private final TransferMaterialRepository transferRepository;
    private final RescheduleProposalRepository rescheduleProposalRepository;

    public CaseService(CaseRepository caseRepository, UserRepository userRepository,
                       TimelineService timelineService, ScheduleRepository scheduleRepository,
                       MediationRecordRepository recordRepository, AgreementRepository agreementRepository,
                       PerformancePlanRepository planRepository, TimelineRepository timelineRepository,
                       DeliveryRepository deliveryRepository, FollowUpRepository followUpRepository,
                       FaceVerificationRepository faceRepository, TransferMaterialRepository transferRepository,
                       RescheduleProposalRepository rescheduleProposalRepository) {
        this.caseRepository = caseRepository;
        this.userRepository = userRepository;
        this.timelineService = timelineService;
        this.scheduleRepository = scheduleRepository;
        this.recordRepository = recordRepository;
        this.agreementRepository = agreementRepository;
        this.planRepository = planRepository;
        this.timelineRepository = timelineRepository;
        this.deliveryRepository = deliveryRepository;
        this.followUpRepository = followUpRepository;
        this.faceRepository = faceRepository;
        this.transferRepository = transferRepository;
        this.rescheduleProposalRepository = rescheduleProposalRepository;
    }

    @Transactional
    public MediationCase create(CaseCreateRequest req, User operator) {
        MediationCase c = new MediationCase();
        c.setCaseNo(generateCaseNo());
        c.setCaseType(req.getCaseType());
        c.setStatus(CaseStatus.PENDING_TRIAGE);
        c.setUrgency(req.getUrgency());
        c.setApplicantName(req.getApplicantName());
        c.setApplicantPhone(req.getApplicantPhone());
        if (operator != null && operator.getRole() == Role.PARTY) {
            c.setApplicantUserId(operator.getId());
        } else if (req.getApplicantUserId() != null) {
            c.setApplicantUserId(req.getApplicantUserId());
        }
        c.setRespondentName(req.getRespondentName());
        c.setRespondentPhone(req.getRespondentPhone());
        c.setRespondentAddress(req.getRespondentAddress());
        c.setRespondentOutOfTown(Boolean.TRUE.equals(req.getRespondentOutOfTown()));
        c.setClaimAmount(req.getClaimAmount());
        c.setClaimSummary(req.getClaimSummary());
        c.setEvidenceList(req.getEvidenceList());
        c.setOnlineWilling(req.getOnlineWilling() == null || req.getOnlineWilling());
        c.setEvidenceComplete(true);
        caseRepository.save(c);

        String detail = String.format("申请人%s提交%s，诉求金额%s元，被申请人%s（电话%s），%s，紧急程度：%s",
                c.getApplicantName(), c.getCaseType().getLabel(),
                c.getClaimAmount() == null ? "未明确" : c.getClaimAmount().toPlainString(),
                c.getRespondentName(), c.getRespondentPhone(),
                Boolean.TRUE.equals(c.getOnlineWilling()) ? "愿意线上调解" : "要求线下调解",
                c.getUrgency().getLabel());
        timelineService.record(c.getId(), "SUBMIT", "当事人提交调解申请", detail, operator);
        return c;
    }

    private String generateCaseNo() {
        String prefix = "TZ" + LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd"));
        long count = caseRepository.count() + 1;
        return prefix + String.format("%03d", count);
    }

    /** 按角色过滤案件列表 */
    @Transactional(readOnly = true)
    public List<MediationCase> list(String status, String type, String keyword, User current) {
        List<MediationCase> all;
        if (current != null && current.getRole() == Role.MEDIATOR) {
            all = caseRepository.findByMediatorId(current.getId());
        } else if (current != null && current.getRole() == Role.PARTY) {
            all = caseRepository.findByApplicantUserId(current.getId());
        } else {
            all = caseRepository.findAll(Sort.by(Sort.Direction.DESC, "createdAt"));
        }
        return all.stream()
                .filter(c -> status == null || status.isBlank() || c.getStatus().name().equals(status))
                .filter(c -> type == null || type.isBlank() || c.getCaseType().name().equals(type))
                .filter(c -> {
                    if (keyword == null || keyword.isBlank()) return true;
                    String k = keyword.trim();
                    return c.getCaseNo().contains(k)
                            || (c.getApplicantName() != null && c.getApplicantName().contains(k))
                            || (c.getRespondentName() != null && c.getRespondentName().contains(k));
                })
                .collect(Collectors.toList());
    }

    public MediationCase mustGet(Long id) {
        return caseRepository.findById(id).orElseThrow(() -> new BizException("案件不存在"));
    }

    /** 案件详情聚合：一个接口返回全流程连续材料 */
    @Transactional(readOnly = true)
    public Map<String, Object> detail(Long id) {
        MediationCase c = mustGet(id);
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("case", c);
        m.put("mediator", c.getMediatorId() == null ? null : userRepository.findById(c.getMediatorId()).orElse(null));
        m.put("windowUser", c.getWindowUserId() == null ? null : userRepository.findById(c.getWindowUserId()).orElse(null));
        m.put("schedules", scheduleRepository.findByCaseIdOrderByStartTimeDesc(id));
        m.put("records", recordRepository.findByCaseIdOrderByRoundNoDescCreatedAtDesc(id));
        m.put("agreement", agreementRepository.findByCaseId(id).orElse(null));
        m.put("plans", planRepository.findByCaseIdOrderByInstallmentNo(id));
        m.put("timeline", timelineRepository.findByCaseIdOrderByCreatedAtAsc(id));
        m.put("deliveries", deliveryRepository.findByCaseIdOrderBySentAtDesc(id));
        m.put("followUps", followUpRepository.findByCaseIdOrderByFollowUpDateDesc(id));
        m.put("faceVerifications", faceRepository.findByCaseIdOrderByCreatedAtDesc(id));
        m.put("transferMaterial", transferRepository.findByCaseId(id).orElse(null));
        m.put("proposals", rescheduleProposalRepository.findByCaseIdOrderByProposedAtDesc(id));

        // 风险标记汇总（供前端红黄提示）
        List<String> risks = new ArrayList<>();
        if (Boolean.TRUE.equals(c.getRespondentMissing())) risks.add("RESPONDENT_MISSING");
        if (Boolean.FALSE.equals(c.getEvidenceComplete())) risks.add("EVIDENCE_MISSING");
        if (c.getRescheduleCount() != null && c.getRescheduleCount() >= 2) risks.add("REPEAT_RESCHEDULE");
        if (c.getRescheduleCount() != null && c.getRescheduleCount() >= 2
                && c.getEfficiencyDecision() == null
                && !Boolean.TRUE.equals(c.getTransferRequested())
                && c.getStatus() != CaseStatus.TRANSFERRED
                && c.getStatus() != CaseStatus.CLOSED) {
            risks.add("EFFICIENCY_RISK");
        }
        if (Boolean.TRUE.equals(c.getTransferRequested())) risks.add("TRANSFER_REQUESTED");
        agreementRepository.findByCaseId(id).ifPresent(a -> {
            if (Boolean.TRUE.equals(a.getInstallment())) risks.add("INSTALLMENT");
        });
        if (planRepository.findByCaseIdOrderByInstallmentNo(id).stream()
                .anyMatch(p -> "OVERDUE".equals(p.getStatus()) || "PARTIAL".equals(p.getStatus()))) {
            risks.add("OVERDUE");
        }
        m.put("risks", risks);
        return m;
    }

    /** 立案窗口分流：核验材料、确定线上/线下、指定调解员 */
    @Transactional
    public MediationCase triage(Long caseId, Map<String, Object> body, User operator) {
        MediationCase c = mustGet(caseId);
        if (c.getStatus() != CaseStatus.PENDING_TRIAGE && c.getStatus() != CaseStatus.TRIAGED) {
            throw new BizException("当前状态（" + c.getStatus().getLabel() + "）不能分流");
        }
        Long mediatorId = Long.valueOf(body.get("mediatorId").toString());
        User mediator = userRepository.findById(mediatorId)
                .filter(u -> u.getRole() == Role.MEDIATOR)
                .orElseThrow(() -> new BizException("请选择有效的调解员"));
        Channel channel = Channel.valueOf(body.get("channel").toString());

        c.setMediatorId(mediatorId);
        c.setChannel(channel);
        c.setWindowUserId(operator.getId());
        c.setTriageOpinion((String) body.getOrDefault("triageOpinion", ""));
        c.setEvidenceComplete(!Boolean.FALSE.equals(body.get("evidenceComplete")));
        c.setEvidenceGapNote((String) body.getOrDefault("evidenceGapNote", ""));
        c.setTriagedAt(LocalDateTime.now());
        c.setStatus(CaseStatus.TRIAGED);
        caseRepository.save(c);

        StringBuilder detail = new StringBuilder();
        detail.append(String.format("窗口分流：指派调解员%s，调解方式：%s。", mediator.getRealName(), channel.getLabel()));
        if (Boolean.FALSE.equals(c.getEvidenceComplete())) {
            detail.append("【材料不齐】").append(c.getEvidenceGapNote()).append("，已记录并允许先行调解，后续在卷宗中连续补正。");
            timelineService.record(caseId, "EVIDENCE", "证据材料缺失登记",
                    "缺失材料：" + c.getEvidenceGapNote(), operator);
        }
        if (Boolean.TRUE.equals(c.getRespondentOutOfTown()) && channel == Channel.ONLINE) {
            detail.append("被申请人在外地，优先采用线上调解。");
        }
        timelineService.record(caseId, "TRIAGE", "立案窗口完成分流", detail.toString(), operator);
        return c;
    }

    /** 登记/解除被申请人失联 */
    @Transactional
    public void markMissing(Long caseId, boolean missing, String note, User operator) {
        MediationCase c = mustGet(caseId);
        c.setRespondentMissing(missing);
        if (missing) {
            c.setMissingAt(LocalDateTime.now());
            timelineService.record(caseId, "MISSING", "被申请人失联登记",
                    "经多种方式联系未果。" + (note == null ? "" : note), operator);
        } else {
            timelineService.record(caseId, "MISSING", "恢复与被申请人联系",
                    note == null ? "被申请人已恢复联系" : note, operator);
        }
        caseRepository.save(c);
    }

    /** 补充证据材料（窗口/当事人），消除缺件标记 */
    @Transactional
    public void supplementEvidence(Long caseId, String note, User operator) {
        MediationCase c = mustGet(caseId);
        boolean wasGap = Boolean.FALSE.equals(c.getEvidenceComplete());
        c.setEvidenceComplete(true);
        c.setEvidenceGapNote(null);
        caseRepository.save(c);
        timelineService.record(caseId, "EVIDENCE", "证据材料补正",
                (wasGap ? "原缺件已补齐：" : "补充材料：") + (note == null ? "" : note), operator);
    }

    public List<CaseType> caseTypes() {
        return List.of(CaseType.values());
    }
}
