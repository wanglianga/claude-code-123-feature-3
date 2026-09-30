package cn.court.mediation.service;

import cn.court.mediation.entity.MediationCase;
import cn.court.mediation.entity.MediationSchedule;
import cn.court.mediation.entity.User;
import cn.court.mediation.enums.CaseStatus;
import cn.court.mediation.enums.Role;
import cn.court.mediation.enums.ScheduleStatus;
import cn.court.mediation.repository.CaseRepository;
import cn.court.mediation.repository.ScheduleRepository;
import cn.court.mediation.repository.UserRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;

/**
 * 调解员绩效统计：
 * 关键口径——当事人/双方临时改期（partyCaused）只做过程留痕，
 * 不直接算作调解员等工作人员的延误；只有 STAFF 发起的改期计入工作人员延误。
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

    @Transactional(readOnly = true)
    public List<Map<String, Object>> overview(User current) {
        List<User> mediators = userRepository.findByRole(Role.MEDIATOR);
        List<Map<String, Object>> out = new ArrayList<>();
        for (User m : mediators) {
            // 调解员本人只能看自己；窗口/管理员可看全部
            if (current != null && current.getRole() == Role.MEDIATOR && !m.getId().equals(current.getId())) {
                continue;
            }
            out.add(buildFor(m));
        }
        return out;
    }

    @Transactional(readOnly = true)
    public Map<String, Object> detail(Long mediatorId) {
        User m = userRepository.findById(mediatorId)
                .filter(u -> u.getRole() == Role.MEDIATOR)
                .orElseThrow(() -> new IllegalArgumentException("调解员不存在"));
        Map<String, Object> view = buildFor(m);
        List<Map<String, Object>> changes = new ArrayList<>();
        for (MediationCase c : caseRepository.findByMediatorId(mediatorId)) {
            for (MediationSchedule s : scheduleRepository.findByCaseIdOrderByStartTimeDesc(c.getId())) {
                if (s.getStatus() == ScheduleStatus.RESCHEDULED) {
                    Map<String, Object> row = new LinkedHashMap<>();
                    row.put("caseId", c.getId());
                    row.put("caseNo", c.getCaseNo());
                    row.put("roundNo", s.getRoundNo());
                    row.put("cancelReason", s.getCancelReason());
                    row.put("changeSource", s.getChangeSource());
                    row.put("changeSourceLabel", ScheduleService.changeSourceLabel(s.getChangeSource()));
                    row.put("partyCaused", Boolean.TRUE.equals(s.getPartyCaused()));
                    row.put("countsAsDelay", "STAFF".equals(s.getChangeSource()));
                    row.put("windowConfirmed", Boolean.TRUE.equals(s.getWindowConfirmed()));
                    row.put("startTime", s.getStartTime());
                    changes.add(row);
                }
            }
        }
        changes.sort((a, b) -> ((java.time.LocalDateTime) b.get("startTime"))
                .compareTo((java.time.LocalDateTime) a.get("startTime")));
        view.put("changes", changes);
        return view;
    }

    private Map<String, Object> buildFor(User m) {
        List<MediationCase> cases = caseRepository.findByMediatorId(m.getId());

        long total = cases.size();
        long active = cases.stream().filter(this::isActive).count();
        long success = cases.stream().filter(c -> EnumSet.of(
                CaseStatus.MEDIATION_SUCCESS, CaseStatus.JUDICIAL_CONFIRMING,
                CaseStatus.PERFORMING, CaseStatus.PERFORMED_DONE, CaseStatus.OVERDUE).contains(c.getStatus())).count();
        long failed = cases.stream().filter(c -> c.getStatus() == CaseStatus.MEDIATION_FAILED).count();
        long transferred = cases.stream().filter(c -> c.getStatus() == CaseStatus.TRANSFERRED).count();

        // 改期口径（以排期记录为准）
        List<MediationSchedule> schedules = new ArrayList<>();
        for (MediationCase c : cases) {
            schedules.addAll(scheduleRepository.findByCaseIdOrderByStartTimeDesc(c.getId()));
        }
        long rescheduledTotal = schedules.stream()
                .filter(s -> s.getStatus() == ScheduleStatus.RESCHEDULED).count();
        long partyCaused = schedules.stream()
                .filter(s -> s.getStatus() == ScheduleStatus.RESCHEDULED
                        && Boolean.TRUE.equals(s.getPartyCaused())).count();
        long staffCaused = schedules.stream()
                .filter(s -> s.getStatus() == ScheduleStatus.RESCHEDULED
                        && "STAFF".equals(s.getChangeSource())).count();
        long platformCaused = schedules.stream()
                .filter(s -> s.getStatus() == ScheduleStatus.RESCHEDULED
                        && "PLATFORM".equals(s.getChangeSource())).count();

        Map<String, Object> v = new LinkedHashMap<>();
        v.put("mediatorId", m.getId());
        v.put("mediatorName", m.getRealName());
        v.put("mediationRoom", m.getMediationRoom());
        v.put("expertise", m.getExpertise());
        v.put("totalCases", total);
        v.put("activeCases", active);
        v.put("successCases", success);
        v.put("failedCases", failed);
        v.put("transferredCases", transferred);
        v.put("successRate", total == 0 ? 0 : Math.round(success * 1000.0 / total) / 10.0);

        v.put("rescheduledTotal", rescheduledTotal);
        v.put("partyCausedCount", partyCaused);
        v.put("staffCausedCount", staffCaused);
        v.put("platformCausedCount", platformCaused);
        // 工作人员延误仅统计工作人员原因的改期；当事人临时变更免责
        v.put("staffDelayCount", staffCaused);
        v.put("liabilityRule", "当事人/双方临时改期不计入工作人员延误，仅工作人员原因改期计入");
        return v;
    }

    private boolean isActive(MediationCase c) {
        return c.getStatus() != CaseStatus.PERFORMED_DONE
                && c.getStatus() != CaseStatus.TRANSFERRED
                && c.getStatus() != CaseStatus.CLOSED
                && c.getStatus() != CaseStatus.MEDIATION_FAILED;
    }
}
