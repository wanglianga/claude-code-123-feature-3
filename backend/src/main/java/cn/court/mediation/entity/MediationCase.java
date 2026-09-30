package cn.court.mediation.entity;

import cn.court.mediation.enums.CaseStatus;
import cn.court.mediation.enums.CaseType;
import cn.court.mediation.enums.Channel;
import cn.court.mediation.enums.Urgency;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

/** 调解案件主表 */
@Getter
@Setter
@Entity
@Table(name = "mediation_case", indexes = {
        @Index(name = "idx_case_no", columnList = "caseNo", unique = true),
        @Index(name = "idx_status", columnList = "status")
})
public class MediationCase {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 案号样式编号，如 TZ20260926001 */
    @Column(nullable = false, length = 30)
    private String caseNo;

    @Enumerated(EnumType.STRING)
    @Column(length = 30, nullable = false)
    private CaseType caseType;

    @Enumerated(EnumType.STRING)
    @Column(length = 20, nullable = false)
    private CaseStatus status;

    @Enumerated(EnumType.STRING)
    @Column(length = 20)
    private Urgency urgency = Urgency.NORMAL;

    // ---------- 当事人信息 ----------
    @Column(length = 50)
    private String applicantName;
    @Column(length = 20)
    private String applicantPhone;
    /** 申请人若为平台注册用户，关联其账号 */
    private Long applicantUserId;

    @Column(length = 50)
    private String respondentName;
    @Column(length = 20)
    private String respondentPhone;
    @Column(length = 200)
    private String respondentAddress;
    /** 是否外地当事人 */
    private Boolean respondentOutOfTown = false;

    // ---------- 诉求 ----------
    /** 诉求金额（元） */
    @Column(precision = 14, scale = 2)
    private BigDecimal claimAmount;

    @Column(length = 1000)
    private String claimSummary;

    /** 证据清单，文本条目换行分隔 */
    @Lob
    @Column(columnDefinition = "TEXT")
    private String evidenceList;

    /** 是否愿意线上调解 */
    private Boolean onlineWilling = true;

    // ---------- 分流与承办 ----------
    private Long windowUserId;
    @Column(length = 500)
    private String triageOpinion;
    private LocalDateTime triagedAt;

    private Long mediatorId;

    @Enumerated(EnumType.STRING)
    @Column(length = 10)
    private Channel channel;

    /** 改期次数 */
    private Integer rescheduleCount = 0;

    /**
     * 改期效率风险等级：null/0=无，1=提示（第1次），2=黄色（≥2次多次改期），3=红色（≥3次且跨度长）。
     * 多次改期由窗口人员判断是否继续调解。
     */
    private Integer rescheduleRiskLevel = 0;

    /** 多次改期后窗口是否已作出"继续调解/分流"决定 */
    private Boolean continuationReviewed = false;
    /** 窗口决定：CONTINUE=继续调解，DIVERT=分流（进入案件分流建议/转立案等） */
    @Column(length = 10)
    private String continuationDecision;
    @Column(length = 500)
    private String continuationNote;
    private LocalDateTime continuationReviewedAt;
    private Long continuationReviewedBy;

    // ---------- 失联 / 缺材料 标记 ----------
    private Boolean respondentMissing = false;
    private LocalDateTime missingAt;
    /** 证据材料是否齐备（窗口分流时核验） */
    private Boolean evidenceComplete = true;
    @Column(length = 500)
    private String evidenceGapNote;

    // ---------- 转立案 ----------
    /** 是否申请/决定转立案 */
    private Boolean transferRequested = false;
    @Column(length = 500)
    private String transferReason;
    private LocalDateTime transferredAt;
    /** 转入诉讼后的立案案号 */
    @Column(length = 30)
    private String litigationCaseNo;

    // ---------- 回访 ----------
    private LocalDate lastFollowUpDate;
    @Column(length = 500)
    private String lastFollowUpResult;

    private LocalDateTime createdAt = LocalDateTime.now();
    private LocalDateTime updatedAt = LocalDateTime.now();

    @PreUpdate
    public void preUpdate() {
        this.updatedAt = LocalDateTime.now();
    }
}
