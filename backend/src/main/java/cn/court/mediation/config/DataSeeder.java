package cn.court.mediation.config;

import cn.court.mediation.entity.*;
import cn.court.mediation.enums.*;
import cn.court.mediation.repository.*;
import cn.court.mediation.service.TimelineService;
import org.springframework.boot.CommandLineRunner;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 演示种子数据（幂等：仅在用户表为空时执行）。
 * 覆盖：待分流/已分流/已排期/调解中/司法确认/履行中(分期)/逾期/履行完毕/转立案，
 * 以及失联、缺材料、多次改期等风险情形。
 */
@Component
public class DataSeeder implements CommandLineRunner {

    private final UserRepository userRepository;
    private final CaseRepository caseRepository;
    private final ScheduleRepository scheduleRepository;
    private final MediationRecordRepository recordRepository;
    private final AgreementRepository agreementRepository;
    private final PerformancePlanRepository planRepository;
    private final FollowUpRepository followUpRepository;
    private final DeliveryRepository deliveryRepository;
    private final TransferMaterialRepository transferRepository;
    private final RoomRepository roomRepository;
    private final TimelineService timelineService;

    public DataSeeder(UserRepository userRepository, CaseRepository caseRepository,
                      ScheduleRepository scheduleRepository, MediationRecordRepository recordRepository,
                      AgreementRepository agreementRepository, PerformancePlanRepository planRepository,
                      FollowUpRepository followUpRepository, DeliveryRepository deliveryRepository,
                      TransferMaterialRepository transferRepository, RoomRepository roomRepository,
                      TimelineService timelineService) {
        this.userRepository = userRepository;
        this.caseRepository = caseRepository;
        this.scheduleRepository = scheduleRepository;
        this.recordRepository = recordRepository;
        this.agreementRepository = agreementRepository;
        this.planRepository = planRepository;
        this.followUpRepository = followUpRepository;
        this.deliveryRepository = deliveryRepository;
        this.transferRepository = transferRepository;
        this.roomRepository = roomRepository;
        this.timelineService = timelineService;
    }

    @Override
    @Transactional
    public void run(String... args) {
        if (userRepository.count() > 0) {
            return;
        }

        // ---------- 用户 ----------
        User admin = user("admin", "admin123", "系统管理员", "1350000000", Role.ADMIN, null, null);
        User window = user("window", "window123", "王窗口", "1350000001", Role.WINDOW, null, "立案大厅");
        User zhang = user("zhang", "mediator123", "张调解员", "1360000001", Role.MEDIATOR,
                "LOAN,NEIGHBOR,TRAFFIC", "第一调解室");
        User li = user("li", "mediator123", "李调解员", "1360000002", Role.MEDIATOR,
                "PROPERTY_FEE,SALES,LOAN", "第二调解室");
        User zhao = user("zhao", "mediator123", "赵调解员", "1360000003", Role.MEDIATOR,
                "TRAFFIC,NEIGHBOR", "线上调解室");
        User party = user("party", "party123", "陈小明", "1380000001", Role.PARTY, null, null);
        User assistant = user("assistant", "assistant123", "孙法官助理", "1390000001", Role.ASSISTANT, null, "速裁庭");
        User execution = user("execution", "exec123", "周执行联络", "1390000002", Role.EXECUTION, null, "执行局");
        userRepository.saveAll(java.util.List.of(admin, window, zhang, li, zhao, party, assistant, execution));

        // ---------- 调解资源：线下调解室 + 线上视频会议室 ----------
        MediationRoom room1 = room("院部第一调解室", Channel.OFFLINE, 12, null, "08:30-17:30");
        MediationRoom room2 = room("院部第二调解室", Channel.OFFLINE, 8, null, "08:30-17:30");
        MediationRoom room3 = room("院部第三调解室", Channel.OFFLINE, 6, null, "09:00-17:00");
        MediationRoom onlineA = room("线上视频会议室A", Channel.ONLINE, 20, "https://meeting.court.gov.cn/room-a", "08:30-20:00");
        MediationRoom onlineB = room("线上视频会议室B", Channel.ONLINE, 20, "https://meeting.court.gov.cn/room-b", "08:30-20:00");
        MediationRoom onlineC = room("线上视频会议室C（外地当事人专线）", Channel.ONLINE, 10, "https://meeting.court.gov.cn/room-c", "08:30-20:00");
        roomRepository.saveAll(java.util.List.of(room1, room2, room3, onlineA, onlineB, onlineC));

        // 1) 待分流（新申请）
        MediationCase c1 = baseCase("TZ" + date(0) + "001", CaseType.LOAN, CaseStatus.PENDING_TRIAGE,
                Urgency.URGENT, "陈小明", "1380000001", party.getId(), "吴志强", "13911110001", "本市文化路12号",
                false, new BigDecimal("50000"), "借款5万元到期未还，要求还本付息",
                "借条原件;银行转账凭证;微信催款记录", true);
        caseRepository.save(c1);
        timelineService.record(c1.getId(), "SUBMIT", "当事人提交调解申请",
                "民间借贷50000元，愿意线上调解，加急。", party);

        // 2) 证据缺件、待分流
        MediationCase c2 = baseCase("TZ" + date(-2) + "002", CaseType.PROPERTY_FEE, CaseStatus.PENDING_TRIAGE,
                Urgency.NORMAL, "和谐物业公司", "0571-86000002", null, "郑海", "13711110002",
                "本市和平小区2-402", false, new BigDecimal("4200"), "拖欠物业费21个月",
                "物业服务合同（缺年度附件）", true);
        c2.setEvidenceComplete(false);
        c2.setEvidenceGapNote("缺少物业费收费标准备案表");
        caseRepository.save(c2);
        timelineService.record(c2.getId(), "SUBMIT", "当事人提交调解申请", "物业费4200元。", null);
        timelineService.record(c2.getId(), "EVIDENCE", "证据材料缺失登记", "缺少物业费收费标准备案表，已通知申请人补正。", window);

        // 3) 已分流待排期
        MediationCase c3 = baseCase("TZ" + date(-3) + "003", CaseType.PROPERTY_FEE, CaseStatus.TRIAGED,
                Urgency.NORMAL, "安泰物业", "0571-86000003", null, "冯丽", "13711110003",
                "本市安居苑8-101", false, new BigDecimal("3600"), "拖欠物业费一年半",
                "物业服务合同;催缴通知书;缴费记录", true);
        c3.setWindowUserId(window.getId());
        c3.setMediatorId(li.getId());
        c3.setChannel(Channel.ONLINE);
        c3.setTriageOpinion("事实清楚、标的额小，适宜线上快速调解。");
        c3.setTriagedAt(LocalDateTime.now().minusDays(2));
        caseRepository.save(c3);
        timelineService.record(c3.getId(), "SUBMIT", "当事人提交调解申请", "物业费3600元。", null);
        timelineService.record(c3.getId(), "TRIAGE", "立案窗口完成分流", "指派李调解员，线上调解。", window);

        // 4) 已排期待调解（交通事故）
        MediationCase c4 = baseCase("TZ" + date(-5) + "004", CaseType.TRAFFIC, CaseStatus.SCHEDULED,
                Urgency.NORMAL, "马骏", "13811110004", null, "罗刚", "13911110004",
                "外地某市货车运输公司", true, new BigDecimal("28000"), "追尾事故车辆维修费、误工费",
                "事故认定书;维修发票;误工证明;保险单", true);
        c4.setWindowUserId(window.getId());
        c4.setMediatorId(zhao.getId());
        c4.setChannel(Channel.ONLINE);
        c4.setTriageOpinion("被申请人在外地，采用线上调解。");
        c4.setTriagedAt(LocalDateTime.now().minusDays(4));
        caseRepository.save(c4);
        timelineService.record(c4.getId(), "TRIAGE", "立案窗口完成分流", "外地当事人，线上调解，赵调解员承办。", window);
        MediationSchedule s4 = schedule(c4.getId(), zhao.getId(), Channel.ONLINE,
                "线上视频会议室A（https://meeting.court.gov.cn/room-a）",
                LocalDateTime.now().plusDays(1).withHour(10).withMinute(0).withSecond(0).withNano(0),
                LocalDateTime.now().plusDays(1).withHour(11).withMinute(0).withSecond(0).withNano(0),
                ScheduleStatus.PLANNED, 1, null);
        s4.setRoomId(onlineA.getId());
        s4.setApplicantConfirmed(true);
        s4.setApplicantConfirmedAt(LocalDateTime.now().minusDays(1));
        scheduleRepository.save(s4);
        timelineService.record(c4.getId(), "SCHEDULE", "第1次调解已排期",
                "明日10:00线上视频调解。申请人已确认出席。", window);

        // 5) 调解中 + 多次改期（邻里纠纷）
        MediationCase c5 = baseCase("TZ" + date(-10) + "005", CaseType.NEIGHBOR, CaseStatus.MEDIATING,
                Urgency.NORMAL, "杨秀兰", "13811110005", null, "陈大勇", "13911110005",
                "本市幸福里5-302", false, new BigDecimal("8000"), "楼上漏水致墙面损坏，要求赔偿",
                "房屋照片;维修报价单;社区证明", false);
        c5.setWindowUserId(window.getId());
        c5.setMediatorId(zhang.getId());
        c5.setChannel(Channel.OFFLINE);
        c5.setRescheduleCount(2);
        caseRepository.save(c5);
        timelineService.record(c5.getId(), "TRIAGE", "立案窗口完成分流", "线下调解，张调解员承办。", window);
        MediationSchedule s51 = schedule(c5.getId(), zhang.getId(), Channel.OFFLINE, "院部第一调解室",
                LocalDateTime.now().minusDays(8), LocalDateTime.now().minusDays(8).plusHours(1),
                ScheduleStatus.RESCHEDULED, 1, "改期原因：被申请人临时务工外出（发起方：当事人/双方临时变更）");
        s51.setRoomId(room1.getId());
        s51.setCancelReason("被申请人临时务工外出");
        s51.setChangeSource("PARTY_TEMP");
        s51.setPartyCaused(true);
        s51.setWindowConfirmed(true);
        scheduleRepository.save(s51);
        timelineService.record(c5.getId(), "RESCHEDULE", "第1次改期",
                "取消原因：被申请人临时务工外出（发起方：当事人/双方临时变更）；当事人临时变更不计入调解员工作人员延误。", zhang);
        MediationSchedule s52 = schedule(c5.getId(), zhang.getId(), Channel.OFFLINE, "院部第一调解室",
                LocalDateTime.now().minusDays(5), LocalDateTime.now().minusDays(5).plusHours(1),
                ScheduleStatus.RESCHEDULED, 2, "改期原因：申请人身体不适就医（发起方：当事人/双方临时变更）");
        s52.setRoomId(room1.getId());
        s52.setCancelReason("申请人身体不适就医");
        s52.setChangeSource("PARTY_TEMP");
        s52.setPartyCaused(true);
        s52.setWindowConfirmed(true);
        scheduleRepository.save(s52);
        timelineService.record(c5.getId(), "RESCHEDULE", "第2次改期",
                "取消原因：申请人就医（发起方：当事人/双方临时变更）。【多次改期预警：已累计2次，已进入调解效率风险与案件分流建议，待窗口人员决定是否继续调解】", zhang);
        MediationSchedule s5 = schedule(c5.getId(), zhang.getId(), Channel.OFFLINE, "院部第一调解室",
                LocalDateTime.now().minusDays(2), LocalDateTime.now().minusDays(2).plusHours(1),
                ScheduleStatus.FINISHED, 3, null);
        s5.setRoomId(room1.getId());
        scheduleRepository.save(s5);
        MediationRecord r5 = new MediationRecord();
        r5.setCaseId(c5.getId());
        r5.setScheduleId(s5.getId());
        r5.setMediatorId(zhang.getId());
        r5.setRoundNo(1);
        r5.setFocusPoints("漏水原因与维修赔偿金额");
        r5.setApplicantConcession("同意放弃误工费主张，仅要求赔偿维修费用6000元");
        r5.setRespondentConcession("认可漏水事实，但认为金额过高，只愿承担3000元");
        r5.setProcessNote("双方对责任无争议，对赔偿金额差距较大，建议各自评估后再组织一次调解。");
        r5.setOutcome("CONTINUE");
        recordRepository.save(r5);
        timelineService.record(c5.getId(), "RECORD", "第1次调解笔录",
                "争议焦点：赔偿金额；申请人让步至6000元，被申请人仅同意3000元；调解继续。", zhang);

        // 6) 分期履行中（民间借贷，当事人账号可见）
        MediationCase c6 = baseCase("TZ" + date(-20) + "006", CaseType.LOAN, CaseStatus.PERFORMING,
                Urgency.NORMAL, "陈小明", "1380000001", party.getId(), "黄伟", "13911110006",
                "本市建设路9号", false, new BigDecimal("60000"), "借款6万元，约定分期偿还",
                "借条;转账凭证;还款承诺书", true);
        c6.setWindowUserId(window.getId());
        c6.setMediatorId(zhang.getId());
        c6.setChannel(Channel.ONLINE);
        caseRepository.save(c6);
        seedAgreementFlow(c6, zhang, window, execution, new BigDecimal("60000"), true,
                "分3期，每期20000元", LocalDate.now().plusMonths(2), null);
        // 3期：第1期已付、第2期部分履行、第3期待付
        PerformancePlan p61 = plan(c6.getId(), 1, new BigDecimal("20000"), LocalDate.now().minusMonths(1),
                "PAID", new BigDecimal("20000"), LocalDate.now().minusMonths(1).plusDays(2), "银行转账到账", execution.getId());
        PerformancePlan p62 = plan(c6.getId(), 2, new BigDecimal("20000"), LocalDate.now().minusDays(5),
                "PARTIAL", new BigDecimal("8000"), LocalDate.now().minusDays(3), "仅到账8000元，承诺月底补足", execution.getId());
        PerformancePlan p63 = plan(c6.getId(), 3, new BigDecimal("20000"), LocalDate.now().plusMonths(1),
                "PENDING", BigDecimal.ZERO, null, null, null);
        planRepository.saveAll(java.util.List.of(p61, p62, p63));
        timelineService.record(c6.getId(), "PERFORMANCE", "第1期足额履行", "应收20000元，已全额到账。", execution);
        timelineService.record(c6.getId(), "PERFORMANCE", "第2期部分履行", "应收20000元，到账8000元，尚欠12000元，继续跟踪。", execution);
        FollowUp f6 = new FollowUp();
        f6.setCaseId(c6.getId());
        f6.setOperatorId(execution.getId());
        f6.setOperatorName("周执行联络");
        f6.setFollowUpDate(LocalDate.now().minusDays(1));
        f6.setResult("PARTIAL");
        f6.setWay("PHONE");
        f6.setNote("被申请人承诺本月底前补足第二期12000元");
        followUpRepository.save(f6);
        timelineService.record(c6.getId(), "FOLLOWUP", "履行回访（部分履行）", "电话回访，承诺月底补足。", execution);

        // 7) 履行逾期（小额买卖）
        MediationCase c7 = baseCase("TZ" + date(-30) + "007", CaseType.SALES, CaseStatus.OVERDUE,
                Urgency.URGENT, "诚信建材经营部", "13811110007", null, "赵德柱", "13911110007",
                "本市民生路仓库", false, new BigDecimal("15000"), "拖欠货款15000元",
                "供货合同;送货单;对账单", true);
        c7.setWindowUserId(window.getId());
        c7.setMediatorId(li.getId());
        c7.setChannel(Channel.ONLINE);
        caseRepository.save(c7);
        seedAgreementFlow(c7, li, window, execution, new BigDecimal("15000"), false,
                "协议生效后15日内一次性付清", LocalDate.now().minusDays(10), null);
        PerformancePlan p7 = plan(c7.getId(), 1, new BigDecimal("15000"), LocalDate.now().minusDays(10),
                "OVERDUE", BigDecimal.ZERO, null, "到期未付，电话不接", execution.getId());
        planRepository.save(p7);
        timelineService.record(c7.getId(), "OVERDUE", "第1期付款逾期",
                "应于" + LocalDate.now().minusDays(10) + "支付15000元，未到账，平台预警并通知执行联络人。", null);

        // 8) 履行完毕
        MediationCase c8 = baseCase("TZ" + date(-40) + "008", CaseType.LOAN, CaseStatus.PERFORMED_DONE,
                Urgency.NORMAL, "孙丽华", "13811110008", null, "钱多金", "13911110008",
                "本市金融街1号", false, new BigDecimal("20000"), "借款2万元",
                "借条;转账记录", true);
        c8.setWindowUserId(window.getId());
        c8.setMediatorId(zhang.getId());
        c8.setChannel(Channel.OFFLINE);
        caseRepository.save(c8);
        seedAgreementFlow(c8, zhang, window, execution, new BigDecimal("20000"), false,
                "一次性付清", LocalDate.now().minusDays(20), null);
        PerformancePlan p8 = plan(c8.getId(), 1, new BigDecimal("20000"), LocalDate.now().minusDays(20),
                "PAID", new BigDecimal("20000"), LocalDate.now().minusDays(21), "已全额到账", execution.getId());
        planRepository.save(p8);
        timelineService.record(c8.getId(), "PERFORMANCE", "第1期足额履行", "20000元全额到账，全部期次履行完毕。", execution);
        c8.setLastFollowUpDate(LocalDate.now().minusDays(18));
        c8.setLastFollowUpResult("双方无争议");
        caseRepository.save(c8);

        // 9) 被申请人失联、多次改期、转立案材料已生成待窗口接收（在办）
        MediationCase c9 = baseCase("TZ" + date(-25) + "009", CaseType.LOAN, CaseStatus.MEDIATING,
                Urgency.URGENT, "周文斌", "13811110009", null, "失联人员马某", "",
                "户籍地为本市但长期不在", false, new BigDecimal("35000"), "借款35000元，被申请人失联",
                "借条;转账凭证", false);
        c9.setWindowUserId(window.getId());
        c9.setMediatorId(zhang.getId());
        c9.setChannel(Channel.OFFLINE);
        c9.setRespondentMissing(true);
        c9.setMissingAt(LocalDateTime.now().minusDays(15));
        c9.setRescheduleCount(2);
        c9.setTransferRequested(true);
        c9.setTransferReason("被申请人失联，调解无法进行");
        caseRepository.save(c9);
        timelineService.record(c9.getId(), "TRIAGE", "立案窗口完成分流", "线下调解，张调解员承办。", window);
        timelineService.record(c9.getId(), "MISSING", "被申请人失联登记",
                "电话停机、户籍地走访不在、社区反映长期外出，多种方式联系未果。", zhang);
        timelineService.record(c9.getId(), "RESCHEDULE", "第2次改期", "因被申请人无法到庭再次改期，累计2次。", zhang);
        timelineService.record(c9.getId(), "TRANSFER", "生成转立案材料包",
                "系统自动汇编申请、分流、失联情况、改期记录等连续材料，等待立案窗口接收。", assistant);
        DeliveryRecord d9 = new DeliveryRecord();
        d9.setCaseId(c9.getId());
        d9.setTarget("RESPONDENT");
        d9.setTargetName("失联人员马某");
        d9.setTargetPhone("");
        d9.setDocumentName("调解排期通知");
        d9.setChannel("SMS");
        d9.setStatus("FAILED");
        d9.setDetail("无有效联系方式，电子送达失败，已转为其他送达方式提示");
        deliveryRepository.save(d9);
        TransferMaterial tm9 = new TransferMaterial();
        tm9.setCaseId(c9.getId());
        tm9.setReasonType("MISSING");
        tm9.setReasonDetail("被申请人失联，调解无法进行");
        tm9.setMaterialList("1. 诉前调解申请书\n2. 分流意见\n3. 失联情况说明\n4. 排期/改期记录\n5. 转立案审批表");
        tm9.setStatus("PREPARED");
        tm9.setPreparedBy(assistant.getId());
        transferRepository.save(tm9);

        // 12) 协议逾期后拒不履行，已正式转立案
        MediationCase c12 = baseCase("TZ" + date(-35) + "012", CaseType.PROPERTY_FEE, CaseStatus.TRANSFERRED,
                Urgency.NORMAL, "安居物业", "0571-86000012", null, "赖账户孙某", "13711110012",
                "本市安居苑12-501", false, new BigDecimal("6800"), "拖欠物业费及调解后仍不履行",
                "物业服务合同;催缴记录;调解协议", true);
        c12.setWindowUserId(window.getId());
        c12.setMediatorId(li.getId());
        c12.setChannel(Channel.ONLINE);
        c12.setTransferRequested(true);
        c12.setTransferReason("协议达成后付款逾期/拒不履行");
        c12.setTransferredAt(LocalDateTime.now().minusDays(3));
        c12.setLitigationCaseNo("(2026)诉前调转民初字第102号");
        caseRepository.save(c12);
        timelineService.record(c12.getId(), "TRIAGE", "立案窗口完成分流", "线上调解，李调解员承办。", window);
        timelineService.record(c12.getId(), "OVERDUE", "第1期付款逾期", "协议约定6800元到期未付。", execution);
        timelineService.record(c12.getId(), "TRANSFER", "生成转立案材料包", "因协议逾期申请转立案，自动汇编全过程材料。", assistant);
        timelineService.record(c12.getId(), "TRANSFER", "案件正式转立案",
                "诉讼案号：(2026)诉前调转民初字第102号，调解-立案材料一次移交完成。", window);
        TransferMaterial tm12 = new TransferMaterial();
        tm12.setCaseId(c12.getId());
        tm12.setReasonType("OVERDUE");
        tm12.setReasonDetail("协议达成后付款逾期/拒不履行");
        tm12.setMaterialList("1. 诉前调解申请书\n2. 分流意见\n3. 调解协议及签署记录\n4. 履行逾期记录\n5. 转立案审批表");
        tm12.setStatus("ACCEPTED");
        tm12.setPreparedBy(assistant.getId());
        tm12.setAcceptedAt(LocalDateTime.now().minusDays(3));
        transferRepository.save(tm12);

        // 10) 司法确认审查中（交通事故，含担保人）
        MediationCase c10 = baseCase("TZ" + date(-8) + "010", CaseType.TRAFFIC, CaseStatus.JUDICIAL_CONFIRMING,
                Urgency.NORMAL, "许志刚", "13811110010", null, "何大军", "13911110010",
                "本市运输公司宿舍", false, new BigDecimal("26000"), "车辆维修费及误工赔偿",
                "事故认定书;维修发票;误工证明", true);
        c10.setWindowUserId(window.getId());
        c10.setMediatorId(zhao.getId());
        c10.setChannel(Channel.ONLINE);
        caseRepository.save(c10);
        seedAgreementFlow(c10, zhao, window, execution, new BigDecimal("26000"), false,
                "协议生效后30日内一次性付清", LocalDate.now().plusDays(20), "何保国");
        timelineService.record(c10.getId(), "TRANSFER", "生成司法确认申请书",
                "当事人申请司法确认，材料已提交法官助理审查。", assistant);

        // 11) 调解失败（拒绝调解）
        MediationCase c11 = baseCase("TZ" + date(-6) + "011", CaseType.SALES, CaseStatus.MEDIATION_FAILED,
                Urgency.NORMAL, "宏达五金店", "13811110011", null, "钱有财", "13911110011",
                "本市建材城B区", false, new BigDecimal("9800"), "拖欠货款9800元",
                "送货单;微信对账记录", true);
        c11.setWindowUserId(window.getId());
        c11.setMediatorId(li.getId());
        c11.setChannel(Channel.OFFLINE);
        caseRepository.save(c11);
        timelineService.record(c11.getId(), "TRIAGE", "立案窗口完成分流", "线下调解，李调解员承办。", window);
        MediationRecord r11 = new MediationRecord();
        r11.setCaseId(c11.getId());
        r11.setMediatorId(li.getId());
        r11.setRoundNo(1);
        r11.setFocusPoints("货款金额及质量争议");
        r11.setApplicantConcession("同意减免800元");
        r11.setRespondentConcession("坚持货物质量不合格，拒绝付款");
        r11.setProcessNote("被申请人明确拒绝继续调解，建议转立案。");
        r11.setOutcome("FAIL");
        recordRepository.save(r11);
        timelineService.record(c11.getId(), "RECORD", "第1次调解笔录", "被申请人拒绝调解，调解终结。", li);

        // 13) 调解中 + 工作人员原因改期1次（调解员临时参加紧急会议，计入工作人员延误）
        MediationCase c13 = baseCase("TZ" + date(-4) + "013", CaseType.LOAN, CaseStatus.SCHEDULED,
                Urgency.NORMAL, "林芳", "13811110013", null, "马守财", "13911110013",
                "本市胜利街22号", false, new BigDecimal("12000"), "朋友借款1.2万元到期未还",
                "借条;微信聊天记录", true);
        c13.setWindowUserId(window.getId());
        c13.setMediatorId(zhang.getId());
        c13.setChannel(Channel.OFFLINE);
        c13.setRescheduleCount(1);
        caseRepository.save(c13);
        timelineService.record(c13.getId(), "TRIAGE", "立案窗口完成分流", "线下调解，张调解员承办。", window);
        MediationSchedule s131 = schedule(c13.getId(), zhang.getId(), Channel.OFFLINE, "院部第一调解室",
                LocalDateTime.now().minusDays(3), LocalDateTime.now().minusDays(3).plusHours(1),
                ScheduleStatus.RESCHEDULED, 1, "改期原因：调解员临时参加紧急庭务会议（发起方：工作人员）");
        s131.setRoomId(room1.getId());
        s131.setCancelReason("调解员临时参加紧急庭务会议");
        s131.setChangeSource("STAFF");
        s131.setPartyCaused(false);
        s131.setWindowConfirmed(true);
        scheduleRepository.save(s131);
        timelineService.record(c13.getId(), "RESCHEDULE", "第1次改期",
                "取消原因：调解员临时参加紧急庭务会议（发起方：工作人员）；本次为工作人员原因改期，计入调解员排班延误记录。", zhang);
        MediationSchedule s132 = schedule(c13.getId(), zhang.getId(), Channel.OFFLINE, "院部第一调解室",
                LocalDateTime.now().plusDays(2).withHour(14).withMinute(0).withSecond(0).withNano(0),
                LocalDateTime.now().plusDays(2).withHour(15).withMinute(0).withSecond(0).withNano(0),
                ScheduleStatus.PLANNED, 2, "由第1次改期而来；发起方：工作人员（调解员/窗口）");
        s132.setRoomId(room1.getId());
        scheduleRepository.save(s132);
        timelineService.record(c13.getId(), "SCHEDULE", "第2次调解已排期",
                "后天14:00 院部第一调解室线下调解。", window);
    }

    /** 协议起草→双方人脸核验→线上签署 的完整留痕 */
    private void seedAgreementFlow(MediationCase c, User mediator, User window, User execution,
                                   BigDecimal amount, boolean installment, String plan,
                                   LocalDate deadline, String guarantor) {
        timelineService.record(c.getId(), "RECORD", "末次调解笔录", "双方达成一致意见。", mediator);
        MediationAgreement a = new MediationAgreement();
        a.setCaseId(c.getId());
        a.setTotalAmount(amount);
        a.setInstallment(installment);
        a.setPaymentPlan(plan);
        a.setDeadline(deadline);
        if (guarantor != null) {
            a.setGuarantorName(guarantor);
            a.setGuarantorPhone("13700000099");
            a.setGuarantorNote("对全部债务承担连带保证责任");
        }
        a.setJudicialConfirmRequested(c.getStatus() == CaseStatus.JUDICIAL_CONFIRMING);
        a.setContent("人民法院诉前调解协议书：双方就" + c.getCaseType().getLabel()
                + "自愿达成协议，被申请人" + c.getRespondentName() + "向申请人" + c.getApplicantName()
                + "支付人民币" + amount + "元。" + plan + "，履行期限至" + deadline + "。");
        a.setFaceVerified(true);
        a.setFaceVerifiedAt(LocalDateTime.now().minusDays(12));
        a.setApplicantSigned(true);
        a.setApplicantSignedAt(LocalDateTime.now().minusDays(12));
        a.setRespondentSigned(true);
        a.setRespondentSignedAt(LocalDateTime.now().minusDays(12).plusHours(1));
        a.setEffectiveAt(LocalDateTime.now().minusDays(12).plusHours(1));
        agreementRepository.save(a);
        timelineService.record(c.getId(), "FACE", "双方人脸核验通过", "活体检测+实名比对，得分均≥90。", mediator);
        timelineService.record(c.getId(), "SIGN", "双方完成线上签署", "协议于" + a.getEffectiveAt() + "生效。", mediator);

        DeliveryRecord d = new DeliveryRecord();
        d.setCaseId(c.getId());
        d.setTarget("RESPONDENT");
        d.setTargetName(c.getRespondentName());
        d.setTargetPhone(c.getRespondentPhone());
        d.setDocumentName("调解协议电子送达");
        d.setChannel("SMS");
        d.setStatus("SENT");
        d.setDetail("已通过短信平台送达，系统记录已读");
        d.setReadAt(LocalDateTime.now().minusDays(11));
        deliveryRepository.save(d);
        timelineService.record(c.getId(), "DELIVERY", "电子送达：调解协议（被申请人）", "短信送达成功并已读。", mediator);
    }

    private User user(String username, String pwd, String name, String phone, Role role, String expertise, String room) {
        User u = new User();
        u.setUsername(username);
        u.setPassword(pwd);
        u.setRealName(name);
        u.setPhone(phone);
        u.setRole(role);
        u.setExpertise(expertise);
        u.setMediationRoom(room);
        u.setCapacity(20);
        return u;
    }

    private MediationCase baseCase(String no, CaseType type, CaseStatus status, Urgency urgency,
                                   String ap, String apPhone, Long apUserId,
                                   String rp, String rpPhone, String rpAddr, boolean outOfTown,
                                   BigDecimal amount, String summary, String evidence, boolean online) {
        MediationCase c = new MediationCase();
        c.setCaseNo(no);
        c.setCaseType(type);
        c.setStatus(status);
        c.setUrgency(urgency);
        c.setApplicantName(ap);
        c.setApplicantPhone(apPhone);
        c.setApplicantUserId(apUserId);
        c.setRespondentName(rp);
        c.setRespondentPhone(rpPhone);
        c.setRespondentAddress(rpAddr);
        c.setRespondentOutOfTown(outOfTown);
        c.setClaimAmount(amount);
        c.setClaimSummary(summary);
        c.setEvidenceList(evidence);
        c.setOnlineWilling(online);
        c.setEvidenceComplete(true);
        c.setRescheduleCount(0);
        c.setCreatedAt(LocalDateTime.now());
        return c;
    }

    private MediationRoom room(String name, Channel channel, int capacity, String meetingUrl, String openHours) {
        MediationRoom r = new MediationRoom();
        r.setName(name);
        r.setChannel(channel);
        r.setCapacity(capacity);
        r.setMeetingUrl(meetingUrl);
        r.setOpenHours(openHours);
        r.setEnabled(true);
        return r;
    }

    private MediationSchedule schedule(Long caseId, Long mediatorId, Channel channel, String location,
                                       LocalDateTime start, LocalDateTime end, ScheduleStatus status,
                                       int round, String note) {
        MediationSchedule s = new MediationSchedule();
        s.setCaseId(caseId);
        s.setMediatorId(mediatorId);
        s.setChannel(channel);
        s.setLocation(location);
        s.setStartTime(start);
        s.setEndTime(end);
        s.setStatus(status);
        s.setRoundNo(round);
        s.setNote(note);
        return s;
    }

    private PerformancePlan plan(Long caseId, int no, BigDecimal due, LocalDate dueDate, String status,
                                 BigDecimal paid, LocalDate paidDate, String note, Long confirmedBy) {
        PerformancePlan p = new PerformancePlan();
        p.setCaseId(caseId);
        p.setInstallmentNo(no);
        p.setDueAmount(due);
        p.setDueDate(dueDate);
        p.setStatus(status);
        p.setPaidAmount(paid);
        p.setPaidDate(paidDate);
        p.setNote(note);
        p.setConfirmedBy(confirmedBy);
        return p;
    }

    private String date(int deltaDays) {
        return LocalDate.now().plusDays(deltaDays).format(java.time.format.DateTimeFormatter.ofPattern("yyyyMMdd"));
    }
}
