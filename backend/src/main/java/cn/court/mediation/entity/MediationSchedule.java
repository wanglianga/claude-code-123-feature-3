package cn.court.mediation.entity;

import cn.court.mediation.enums.Channel;
import cn.court.mediation.enums.ScheduleStatus;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

/** 调解排期：每次排期/改期都保留一行，形成连续排期记录 */
@Getter
@Setter
@Entity
@Table(name = "mediation_schedule")
public class MediationSchedule {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private Long caseId;
    private Long mediatorId;

    @Enumerated(EnumType.STRING)
    @Column(length = 10)
    private Channel channel;

    /** 线下地点 / 线上会议链接 */
    @Column(length = 300)
    private String location;

    /** 占用的调解室/线上会议资源（可为空，表示仅登记地点文本） */
    private Long roomId;

    private LocalDateTime startTime;
    private LocalDateTime endTime;

    @Enumerated(EnumType.STRING)
    @Column(length = 15)
    private ScheduleStatus status = ScheduleStatus.PLANNED;

    /** 第几次调解（从1开始） */
    private Integer roundNo = 1;

    /** 改期原因或备注 */
    @Column(length = 500)
    private String note;

    /** 排期被取消/改期时保留的取消原因 */
    @Column(length = 300)
    private String cancelReason;

    /**
     * 改期发起方：
     * PARTY_TEMP 双方/当事人临时变更（调解员排班冲突的常见来源，绩效免责）；
     * STAFF 调解员/窗口等工作人员原因（计入工作人员延误）；
     * PLATFORM 平台原因（如系统维护）。
     */
    @Column(length = 15)
    private String changeSource;

    /** 是否当事人临时变更导致（便于绩效直接判定免责） */
    private Boolean partyCaused = false;

    /** 该次改期是否已经窗口人员确认分流 */
    private Boolean windowConfirmed = false;

    /** 申请人、被申请人是否确认出席（改期后需对新安排重新确认） */
    private Boolean applicantConfirmed = false;
    private Boolean respondentConfirmed = false;
    private LocalDateTime applicantConfirmedAt;
    private LocalDateTime respondentConfirmedAt;

    private LocalDateTime createdAt = LocalDateTime.now();
}
