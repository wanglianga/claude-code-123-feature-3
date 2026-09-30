package cn.court.mediation.entity;

import cn.court.mediation.enums.Channel;
import cn.court.mediation.enums.RescheduleInitiator;
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

    /** 占用的调解资源（线下调解室/线上视频会议） */
    private Long resourceId;

    @Enumerated(EnumType.STRING)
    @Column(length = 10)
    private Channel channel;

    /** 线下地点 / 线上会议链接 */
    @Column(length = 300)
    private String location;

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

    /** 该排期被取消/改期时登记的取消原因（独立留痕，供卷宗与归责使用） */
    @Column(length = 300)
    private String cancelReason;

    /** 改期发起方：申请人/被申请人/双方/调解员/院方 */
    @Enumerated(EnumType.STRING)
    @Column(length = 15)
    private RescheduleInitiator initiator;

    /** 若由改期产生，指向上一条被改期的排期 */
    private Long rescheduleFromId;

    /** 申请人、被申请人是否确认出席（改期后新排期需双方重新确认） */
    private Boolean applicantConfirmed = false;
    private Boolean respondentConfirmed = false;

    private LocalDateTime createdAt = LocalDateTime.now();
}
