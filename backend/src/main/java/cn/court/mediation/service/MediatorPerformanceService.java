package cn.court.mediation.service;

import cn.court.mediation.common.BizException;
import cn.court.mediation.entity.MediationCase;
import cn.court.mediation.entity.MediationSchedule;
import cn.court.mediation.entity.User;
import cn.court.mediation.enums.CaseStatus;
import cn.court.mediation.enums.RescheduleInitiator;
import cn.court.mediation.enums.Role;
import cn.court.mediation.enums.ScheduleStatus;
import cn.court.mediation.repository.CaseRepository;
import cn.court.mediation.repository.ScheduleRepository;
import cn.court.mediation.repository.UserRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;

/**
 * 调解员绩效统计。
 * 改期归责原则：申请人/被申请人/双方临时改期属于当事人原因，不计入工作人员延误；
 * 仅调解员或院方（平台）原因的改期计入工作人员排班延误。
 */
@Service
public class MediatorPerformanceService {

    private final UserRepository userRepository;
    private final CaseRepository caseRepository;
    private final ScheduleRepository scheduleRepository;

    public MediatorPerformanceService(UserRepository userRepository, CaseRepository caseRepository,
                                      ScheduleRepository scheduleRepository) {
        this.userRepository = userRepository;
        this.caseRepository = caseRepository;
        this.scheduleRepository = scheduleRepository;
    }

    private static final Set<CaseStatus> SUCCESS_STATUSES = EnumSet.of(
            CaseStatus.MEDIATION_SUCCESS, CaseStatus.JUDICIAL_CONFIRMING,
            CaseStatus.PERFORMING, CaseStatus.PERFORMED_DONE, CaseStatus.OVERDUE);
    private static final Set<CaseStatus> CLOSED_STATUSES = EnumSet.of(
            CaseStatus.PERFORMED_DONE, CaseStatus.TRANSFERRED, CaseStatus.CLOSED, CaseStatus.MEDIATION_FAILED);

    @Transactional(readOnly = true)
    public List<Map<String, Object>> overview() {
        List<Map<String, Object>> rows = new ArrayList<>();
        for (User m : userRepository.findByRole(Role.MEDIATOR)) {
            rows.add(build(m));
        }
        return rows;
    }

    @Transactional(readOnly = true)
    public Map<String, Object> detail(Long mediatorId) {
        User m = userRepository.findById(mediatorId)
                .filter(u -> u.getRole() == Role.MEDIATOR)
                .orElseThrow(() -> new BizException("调解员不存在"));
        Map<String, Object> view = build(m);
        // 附：当事人原因 vs 工作人员原因 的改期明细
        List<MediationSchedule> rescheduled = scheduleRepository
                .findByMediatorIdAndStatus(mediatorId, ScheduleStatus.RESCHEDULED);
        List<Map<String, Object>> records = new ArrayList<>();
        for (MediationSchedule s : rescheduled) {
            Map<String, Object> r = new LinkedHashMap<>();
            r.put("scheduleId", s.getId());
            r.put("caseId", s.getCaseId());
            r.put("roundNo", s.getRoundNo());
            r.put("startTime", s.getStartTime());
            r.put("cancelReason", s.getCancelReason());
            RescheduleInitiator init = s.getInitiator() == null ? RescheduleInitiator.BOTH_PARTIES : s.getInitiator();
            r.put("initiator", init.name());
            r.put("initiatorLabel", init.getLabel());
            r.put("partySide", init.isPartySide());
            r.put("countsAsStaffDelay", !init.isPartySide());
            records.add(r);
        }
        view.put("rescheduleRecords", records);
        return view;
    }

    private Map<String, Object> build(User m) {
        List<MediationCase> cases = caseRepository.findByMediatorId(m.getId());
        long active = cases.stream().filter(c -> !CLOSED_STATUSES.contains(c.getStatus())).count();
        long success = cases.stream().filter(c -> SUCCESS_STATUSES.contains(c.getStatus())).count();
        long closed = cases.stream().filter(c -> CLOSED_STATUSES.contains(c.getStatus())).count();

        // 该调解员承办排期中被改期的记录（每条 RESCHEDULED 即一次改期）
        List<MediationSchedule> rescheduled = scheduleRepository
                .findByMediatorIdAndStatus(m.getId(), ScheduleStatus.RESCHEDULED);

        Map<RescheduleInitiator, Long> byInitiator = new LinkedHashMap<>();
        for (RescheduleInitiator init : RescheduleInitiator.values()) byInitiator.put(init, 0L);
        long partySide = 0;
        long staffSide = 0;
        for (MediationSchedule s : rescheduled) {
            RescheduleInitiator init = s.getInitiator() == null ? RescheduleInitiator.BOTH_PARTIES : s.getInitiator();
            byInitiator.merge(init, 1L, Long::sum);
            if (init.isPartySide()) partySide++;
            else staffSide++;
        }

        Map<String, Object> v = new LinkedHashMap<>();
        v.put("mediatorId", m.getId());
        v.put("mediatorName", m.getRealName());
        v.put("mediationRoom", m.getMediationRoom());
        v.put("expertise", m.getExpertise());
        v.put("totalCases", cases.size());
        v.put("activeCases", active);
        v.put("successCases", success);
        v.put("closedCases", closed);
        v.put("successRate", closed == 0 ? 0 : Math.round(success * 1000.0 / closed) / 10.0);

        v.put("totalReschedules", rescheduled.size());
        v.put("partySideReschedules", partySide);
        v.put("staffSideReschedules", staffSide);
        v.put("rescheduleByInitiator", byInitiator.entrySet().stream().collect(
                java.util.stream.Collectors.toMap(e -> e.getKey().name(), e -> e.getValue(), (a, b) -> a, LinkedHashMap::new)));

        // 工作人员可归责延误率 = 工作人员原因改期次数 / 承办案件总数（当事人改期不计）
        v.put("staffDelayRate", cases.isEmpty() ? 0
                : Math.round(staffSide * 1000.0 / cases.size()) / 10.0);

        // 多次改期（≥2次）在办案件数，供分流联动参考
        long repeatCases = cases.stream()
                .filter(c -> c.getRescheduleCount() != null && c.getRescheduleCount() >= 2)
                .filter(c -> !CLOSED_STATUSES.contains(c.getStatus()))
                .count();
        v.put("repeatRescheduleActiveCases", repeatCases);
        return v;
    }
}
