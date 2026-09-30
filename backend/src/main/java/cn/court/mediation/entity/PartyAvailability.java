package cn.court.mediation.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

/**
 * 当事人可调解时间窗口：申请人/被申请人各自登记一个或多个可用时间段。
 * 平台重新匹配时取双方时间窗口的交集，并叠加调解员排班、调解室/线上会议占用综合推荐。
 */
@Getter
@Setter
@Entity
@Table(name = "party_availability", indexes = {
        @Index(name = "idx_pa_case", columnList = "caseId")
})
public class PartyAvailability {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private Long caseId;

    /** APPLICANT / RESPONDENT */
    @Column(length = 12, nullable = false)
    private String party;

    private LocalDateTime startTime;
    private LocalDateTime endTime;

    @Column(length = 200)
    private String note;

    /** 登记人（当事人本人或窗口代登记） */
    private Long createdBy;
    @Column(length = 50)
    private String createdByName;

    private LocalDateTime createdAt = LocalDateTime.now();
}
