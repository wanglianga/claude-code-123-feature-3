package cn.court.mediation.repository;

import cn.court.mediation.entity.MediationSchedule;
import cn.court.mediation.enums.RescheduleInitiator;
import cn.court.mediation.enums.ScheduleStatus;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDateTime;
import java.util.List;

public interface ScheduleRepository extends JpaRepository<MediationSchedule, Long> {
    List<MediationSchedule> findByCaseIdOrderByStartTimeDesc(Long caseId);

    List<MediationSchedule> findByMediatorIdAndStatusOrderByStartTime(Long mediatorId, ScheduleStatus status);

    /** 调解员在给定时间段内的有效排期冲突检测 */
    List<MediationSchedule> findByMediatorIdAndStatusInAndStartTimeLessThanAndEndTimeGreaterThan(
            Long mediatorId, List<ScheduleStatus> statuses, LocalDateTime end, LocalDateTime start);

    /** 调解室/线上会议在给定时间段内的占用冲突检测 */
    List<MediationSchedule> findByResourceIdAndStatusInAndStartTimeLessThanAndEndTimeGreaterThan(
            Long resourceId, List<ScheduleStatus> statuses, LocalDateTime end, LocalDateTime start);

    /** 某调解员承办的全部排期（绩效统计用） */
    List<MediationSchedule> findByMediatorId(Long mediatorId);

    /** 某调解员被改期的记录，按发起方归责 */
    List<MediationSchedule> findByMediatorIdAndStatusAndInitiator(
            Long mediatorId, ScheduleStatus status, RescheduleInitiator initiator);

    List<MediationSchedule> findByMediatorIdAndStatus(Long mediatorId, ScheduleStatus status);

    List<MediationSchedule> findByCaseIdAndStatus(Long caseId, ScheduleStatus status);
}
