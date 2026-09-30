package cn.court.mediation.service;

import cn.court.mediation.common.BizException;
import cn.court.mediation.entity.*;
import cn.court.mediation.enums.CaseStatus;
import cn.court.mediation.repository.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;
import java.util.StringJoiner;

/**
 * 转立案 & 司法确认：
 * 系统自动把申请、分流、排期/改期、笔录、协议、签署、履行、回访、送达、失联/缺件等时间线
 * 汇编成连续材料，直接供法官审查，避免当事人在调解与立案窗口之间反复补交。
 */
@Service
public class TransferService {

    private final TransferMaterialRepository transferRepository;
    private final CaseRepository caseRepository;
    private final TimelineRepository timelineRepository;
    private final MediationRecordRepository recordRepository;
    private final AgreementRepository agreementRepository;
    private final PerformancePlanRepository planRepository;
    private final FollowUpRepository followUpRepository;
    private final DeliveryRepository deliveryRepository;
    private final FaceVerificationRepository faceRepository;
    private final TimelineService timelineService;

    public TransferService(TransferMaterialRepository transferRepository, CaseRepository caseRepository,
                           TimelineRepository timelineRepository, MediationRecordRepository recordRepository,
                           AgreementRepository agreementRepository, PerformancePlanRepository planRepository,
                           FollowUpRepository followUpRepository, DeliveryRepository deliveryRepository,
                           FaceVerificationRepository faceRepository, TimelineService timelineService) {
        this.transferRepository = transferRepository;
        this.caseRepository = caseRepository;
        this.timelineRepository = timelineRepository;
        this.recordRepository = recordRepository;
        this.agreementRepository = agreementRepository;
        this.planRepository = planRepository;
        this.followUpRepository = followUpRepository;
        this.deliveryRepository = deliveryRepository;
        this.faceRepository = faceRepository;
        this.timelineService = timelineService;
    }

    /** 生成转立案材料包 */
    @Transactional
    public TransferMaterial prepareTransfer(Long caseId, Map<String, Object> body, User operator) {
        MediationCase c = caseRepository.findById(caseId).orElseThrow(() -> new BizException("案件不存在"));
        String reasonType = (String) body.getOrDefault("reasonType", "OTHER");
        String reasonDetail = (String) body.getOrDefault("reasonDetail", "");

        TransferMaterial tm = transferRepository.findByCaseId(caseId).orElseGet(TransferMaterial::new);
        tm.setCaseId(caseId);
        tm.setReasonType(reasonType);
        tm.setReasonDetail(reasonDetail);
        tm.setStatus("PREPARED");
        tm.setPreparedBy(operator.getId());
        tm.setPreparedAt(LocalDateTime.now());

        List<TimelineEvent> events = timelineRepository.findByCaseIdOrderByCreatedAtAsc(caseId);
        StringJoiner materials = new StringJoiner("\n");
        materials.add("1. 诉前调解申请书（含诉求金额、被申请人联系方式、证据清单）");
        materials.add("2. 立案窗口分流意见");
        if (events.stream().anyMatch(e -> "RESCHEDULE".equals(e.getType()))) {
            materials.add("3. 历次排期/改期记录（含改期原因与次数：" + c.getRescheduleCount() + "次）");
        } else {
            materials.add("3. 排期记录");
        }
        if (!recordRepository.findByCaseIdOrderByRoundNoDescCreatedAtDesc(caseId).isEmpty()) {
            materials.add("4. 调解笔录（争议焦点、双方让步、协商过程）");
        }
        if (agreementRepository.findByCaseId(caseId).isPresent()) {
            materials.add("5. 调解协议及电子签署/人脸核验记录");
        }
        if (!planRepository.findByCaseIdOrderByInstallmentNo(caseId).isEmpty()) {
            materials.add("6. 履行计划与到账/部分履行/逾期记录");
        }
        if (!followUpRepository.findByCaseIdOrderByFollowUpDateDesc(caseId).isEmpty()) {
            materials.add("7. 履行回访记录");
        }
        materials.add("8. 电子送达记录");
        if (Boolean.TRUE.equals(c.getRespondentMissing())) {
            materials.add("9. 被申请人失联情况说明及联系过程记录");
        }
        materials.add("10. 转立案审批表");
        tm.setMaterialList(materials.toString());
        tm.setCompiledContent(buildCompiledDossier(c, reasonType, reasonDetail, events));
        transferRepository.save(tm);

        c.setTransferRequested(true);
        c.setTransferReason(reasonDetail.isBlank() ? reasonTypeLabel(reasonType) : reasonDetail);
        caseRepository.save(c);

        timelineService.record(caseId, "TRANSFER", "生成转立案材料包",
                "转立案原因：" + reasonTypeLabel(reasonType) + "。系统已自动汇编调解全过程连续材料共"
                        + events.size() + "条过程记录，立案窗口无需当事人重复提交。",
                operator, "转立案材料_" + c.getCaseNo() + ".txt");
        return tm;
    }

    /** 法官助理/窗口接收材料并正式转立案 */
    @Transactional
    public MediationCase acceptTransfer(Long caseId, String litigationCaseNo, User operator) {
        MediationCase c = caseRepository.findById(caseId).orElseThrow(() -> new BizException("案件不存在"));
        TransferMaterial tm = transferRepository.findByCaseId(caseId)
                .orElseThrow(() -> new BizException("尚未生成转立案材料包"));
        tm.setStatus("ACCEPTED");
        tm.setAcceptedAt(LocalDateTime.now());
        transferRepository.save(tm);

        c.setStatus(CaseStatus.TRANSFERRED);
        c.setTransferredAt(LocalDateTime.now());
        c.setLitigationCaseNo(litigationCaseNo);
        caseRepository.save(c);

        timelineService.record(caseId, "TRANSFER", "案件正式转立案",
                "立案窗口已接收全部调解卷宗材料，诉讼案号：" + litigationCaseNo
                        + "。调解-立案材料一次移交完成。", operator);
        return c;
    }

    /** 生成司法确认申请书文本 */
    @Transactional
    public String buildJudicialConfirmDoc(Long caseId, User operator) {
        MediationCase c = caseRepository.findById(caseId).orElseThrow(() -> new BizException("案件不存在"));
        MediationAgreement a = agreementRepository.findByCaseId(caseId)
                .orElseThrow(() -> new BizException("尚无调解协议，无法生成司法确认材料"));
        StringBuilder sb = new StringBuilder();
        sb.append("司法确认申请书\n\n");
        sb.append("申请人（调解协议双方）：\n");
        sb.append("  申请人：").append(c.getApplicantName())
                .append("，联系电话：").append(c.getApplicantPhone()).append("\n");
        sb.append("  被申请人：").append(c.getRespondentName())
                .append("，联系电话：").append(c.getRespondentPhone()).append("\n\n");
        sb.append("请求事项：\n");
        sb.append("  请求人民法院依法确认双方于").append(a.getEffectiveAt() == null ? "—" : a.getEffectiveAt().toLocalDate())
                .append("达成的诉前调解协议效力。\n\n");
        sb.append("事实与理由：\n");
        sb.append("  双方因").append(c.getCaseType().getLabel()).append("，经本院诉前调解组织主持调解，自愿达成协议：\n");
        sb.append("  协议金额人民币").append(a.getTotalAmount()).append("元；")
                .append(Boolean.TRUE.equals(a.getInstallment()) ? "分期履行" : "一次性履行")
                .append("；履行期限至").append(a.getDeadline()).append("。\n");
        if (a.getGuarantorName() != null && !a.getGuarantorName().isBlank()) {
            sb.append("  担保人：").append(a.getGuarantorName()).append("。\n");
        }
        sb.append("  协议经双方电子签名并完成人脸核验，内容真实合法，特申请司法确认。\n\n");
        sb.append("  此致\n  基层人民法院\n\n");
        sb.append("                              申请人（签名）：\n");
        sb.append("                              被申请人（签名）：\n");
        sb.append("                              日期：").append(LocalDateTime.now().toLocalDate()).append("\n");

        timelineService.record(caseId, "TRANSFER", "生成司法确认申请书",
                "依据已生效调解协议自动生成司法确认申请书，供法官助理审查后报法官确认。",
                operator, "司法确认申请书_" + c.getCaseNo() + ".txt");
        return sb.toString();
    }

    /** 法官助理/法官审查通过司法确认，案件转入履行跟踪 */
    @Transactional
    public MediationCase passJudicialConfirm(Long caseId, User operator) {
        MediationCase c = caseRepository.findById(caseId).orElseThrow(() -> new BizException("案件不存在"));
        if (c.getStatus() != CaseStatus.JUDICIAL_CONFIRMING) {
            throw new BizException("仅司法确认审查中的案件可以确认");
        }
        c.setStatus(CaseStatus.PERFORMING);
        caseRepository.save(c);
        timelineService.record(caseId, "TRANSFER", "司法确认审查通过",
                "法官助理审查、法官确认调解协议效力，确认裁定电子送达双方，案件进入履行跟踪。", operator);
        return c;
    }

    /** 不经过转立案流程，直接汇编当前连续过程材料供法官查阅（失联、多次改期、部分履行等情形） */
    @Transactional
    public String dossierForCase(Long caseId, User operator) {
        MediationCase c = caseRepository.findById(caseId).orElseThrow(() -> new BizException("案件不存在"));
        List<TimelineEvent> events = timelineRepository.findByCaseIdOrderByCreatedAtAsc(caseId);
        String text = buildCompiledDossier(c, "OTHER", "（过程材料随案提交法官查阅）", events);
        timelineService.record(caseId, "TRANSFER", "导出连续过程材料卷",
                "已汇编申请、分流、排期/改期、笔录、协议、履行、回访、送达等共" + events.size()
                        + "条连续记录提交法官。", operator, "过程材料卷_" + c.getCaseNo() + ".txt");
        return text;
    }

    /** 汇编连续过程材料卷宗（同时供转立案与“提交法官”使用） */
    public String buildCompiledDossier(MediationCase c, String reasonType, String reasonDetail, List<TimelineEvent> events) {
        DateTimeFormatter fmt = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");
        StringBuilder sb = new StringBuilder();
        sb.append("════════════════════════════════════════\n");
        sb.append("      诉前调解连续过程材料卷（随案移交法官）\n");
        sb.append("════════════════════════════════════════\n\n");
        sb.append("调解编号：").append(c.getCaseNo()).append("\n");
        sb.append("案    由：").append(c.getCaseType().getLabel()).append("\n");
        sb.append("申 请 人：").append(c.getApplicantName()).append(" 电话：").append(c.getApplicantPhone()).append("\n");
        sb.append("被申请人：").append(c.getRespondentName()).append(" 电话：").append(c.getRespondentPhone()).append("\n");
        sb.append("诉求金额：").append(c.getClaimAmount()).append("元\n");
        sb.append("证据清单：\n").append(c.getEvidenceList() == null ? "（无）" : c.getEvidenceList()).append("\n\n");

        sb.append("【分流情况】\n").append(c.getTriageOpinion() == null ? "（无）" : c.getTriageOpinion()).append("\n");
        sb.append("失联情况：").append(Boolean.TRUE.equals(c.getRespondentMissing()) ? "曾登记失联" : "无")
                .append("；材料缺件：").append(Boolean.FALSE.equals(c.getEvidenceComplete()) ? "未补齐：" + c.getEvidenceGapNote() : "已齐备")
                .append("；改期次数：").append(c.getRescheduleCount()).append("\n\n");

        List<MediationRecord> records = recordRepository.findByCaseIdOrderByRoundNoDescCreatedAtDesc(c.getId());
        sb.append("【调解笔录】（共").append(records.size()).append("次）\n");
        for (int i = records.size() - 1; i >= 0; i--) {
            MediationRecord r = records.get(i);
            sb.append("— 第").append(r.getRoundNo()).append("次 —\n");
            sb.append("争议焦点：").append(nz(r.getFocusPoints())).append("\n");
            sb.append("申请人让步：").append(nz(r.getApplicantConcession())).append("\n");
            sb.append("被申请人让步：").append(nz(r.getRespondentConcession())).append("\n");
            sb.append("过程：").append(nz(r.getProcessNote())).append("\n");
        }
        sb.append("\n");

        agreementRepository.findByCaseId(c.getId()).ifPresent(a -> {
            sb.append("【调解协议】\n").append(a.getContent()).append("\n\n");
            sb.append("签署情况：申请人").append(Boolean.TRUE.equals(a.getApplicantSigned()) ? "已签(" + a.getApplicantSignedAt() + ")" : "未签")
                    .append("，被申请人").append(Boolean.TRUE.equals(a.getRespondentSigned()) ? "已签(" + a.getRespondentSignedAt() + ")" : "未签")
                    .append("，人脸核验：").append(Boolean.TRUE.equals(a.getFaceVerified()) ? "通过" : "未核验").append("\n\n");
        });

        List<PerformancePlan> plans = planRepository.findByCaseIdOrderByInstallmentNo(c.getId());
        if (!plans.isEmpty()) {
            sb.append("【履行情况】\n");
            for (PerformancePlan p : plans) {
                sb.append("第").append(p.getInstallmentNo()).append("期：应付").append(p.getDueAmount())
                        .append("元，期限").append(p.getDueDate())
                        .append("，实付").append(p.getPaidAmount()).append("元，状态：")
                        .append(switch (p.getStatus()) {
                            case "PAID" -> "已履行";
                            case "PARTIAL" -> "部分履行";
                            case "OVERDUE" -> "逾期";
                            default -> "待履行";
                        }).append("\n");
            }
            sb.append("\n");
        }

        List<FollowUp> fus = followUpRepository.findByCaseIdOrderByFollowUpDateDesc(c.getId());
        if (!fus.isEmpty()) {
            sb.append("【回访记录】\n");
            for (FollowUp f : fus) {
                sb.append(f.getFollowUpDate()).append(" ").append(f.getOperatorName()).append("：")
                        .append(f.getResult()).append(" ").append(nz(f.getNote())).append("\n");
            }
            sb.append("\n");
        }

        sb.append("【全流程时间线】（共").append(events.size()).append("条）\n");
        for (TimelineEvent e : events) {
            sb.append(e.getCreatedAt().format(fmt)).append(" [")
                    .append(e.getOperatorName()).append("] ")
                    .append(e.getTitle());
            if (e.getDetail() != null && !e.getDetail().isBlank()) {
                sb.append("：").append(e.getDetail());
            }
            sb.append("\n");
        }

        sb.append("\n【转立案说明】\n原因分类：").append(reasonTypeLabel(reasonType))
                .append("\n具体说明：").append(nz(reasonDetail)).append("\n");
        return sb.toString();
    }

    private String nz(String s) {
        return s == null || s.isBlank() ? "（无）" : s;
    }

    private String reasonTypeLabel(String t) {
        return switch (t) {
            case "MISSING" -> "被申请人失联，调解无法进行";
            case "EVIDENCE" -> "证据材料争议较大，需诉讼查明";
            case "REJECT" -> "一方明确拒绝调解";
            case "OVERDUE" -> "协议达成后付款逾期/拒不履行";
            case "RESCHEDULE" -> "多次临时改期，调解效率风险，窗口确认分流";
            default -> "其他不适宜继续调解情形";
        };
    }
}
