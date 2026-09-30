package cn.court.mediation.entity;

import cn.court.mediation.enums.Channel;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

/**
 * 改期冲突重匹配建议：
 * 双方临时改期导致调解员排班冲突时，平台在双方可用时间交集内
 * 重新匹配调解员 + 调解室/线上会议生成候选方案，由窗口人员确认分流。
 */
@Getter
@Setter
@Entity
@Table(name = "reschedule_proposal", indexes = {
        @Index(name = "idx_proposal_case", columnList = "caseId"),
        @Index(name = "idx_proposal_status", columnList = "status")
})
public class RescheduleProposal {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private Long caseId;

    /** 触发重匹配的原排期 */
    private Long fromScheduleId;

    /** 改期取消原因（当事人临时变更等，随记录保留） */
    @Column(length = 300)
    private String cancelReason;

    /** 改期发起方：PARTY_TEMP / STAFF / PLATFORM */
    @Column(length = 15)
    private String changeSource;

    /** 平台匹配出的新安排 */
    private Long mediatorId;
    private Long roomId;

    @Enumerated(EnumType.STRING)
    @Column(length = 10)
    private Channel channel;

    @Column(length = 300)
    private String location;

    private LocalDateTime startTime;
    private LocalDateTime endTime;

    /** 匹配得分与说明（专长/在办量/时间/资源） */
    private Integer score = 0;
    @Column(length = 500)
    private String matchNote;

    /**
     * 状态：
     * PROPOSED 已生成待窗口确认；CONFIRMED 窗口确认分流并完成改期；
     * REJECTED 窗口驳回；EXPIRED 双方时间变化失效。
     */
    @Column(length = 15)
    private String status = "PROPOSED";

    /** 提交建议的人（调解员/窗口） */
    private Long proposedBy;
    private LocalDateTime proposedAt = LocalDateTime.now();

    /** 窗口确认分流人 */
    private Long confirmedBy;
    private LocalDateTime confirmedAt;

    /** 确认后生成的新排期 */
    private Long newScheduleId;

    @Column(length = 500)
    private String rejectReason;
}
