package cn.court.mediation.controller;

import cn.court.mediation.common.ApiResponse;
import cn.court.mediation.entity.MediationRoom;
import cn.court.mediation.entity.MediationSchedule;
import cn.court.mediation.entity.RescheduleProposal;
import cn.court.mediation.enums.Channel;
import cn.court.mediation.repository.RoomRepository;
import cn.court.mediation.security.CurrentUser;
import cn.court.mediation.service.ScheduleService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api")
public class ScheduleController {

    private final ScheduleService scheduleService;
    private final RoomRepository roomRepository;

    public ScheduleController(ScheduleService scheduleService, RoomRepository roomRepository) {
        this.scheduleService = scheduleService;
        this.roomRepository = roomRepository;
    }

    /** 调解室 / 线上会议资源名册（支持按渠道过滤） */
    @GetMapping("/rooms")
    public ApiResponse<List<MediationRoom>> rooms(@RequestParam(required = false) String channel) {
        if (channel == null || channel.isBlank()) {
            return ApiResponse.ok(roomRepository.findByEnabledTrueOrderByChannelAscIdAsc());
        }
        return ApiResponse.ok(roomRepository.findByChannelAndEnabledTrueOrderByIdAsc(Channel.valueOf(channel)));
    }

    /** 智能推荐调解员（专长/在办量/时间冲突/外地线上） */
    @GetMapping("/cases/{id}/mediator-suggest")
    public ApiResponse<List<Map<String, Object>>> suggest(@PathVariable Long id,
                                                          @RequestParam(required = false) String start,
                                                          @RequestParam(required = false) String end) {
        LocalDateTime s = start == null ? null : LocalDateTime.parse(start);
        LocalDateTime e = end == null ? null : LocalDateTime.parse(end);
        return ApiResponse.ok(scheduleService.recommendMediators(id, s, e));
    }

    @PostMapping("/cases/{id}/schedules")
    public ApiResponse<MediationSchedule> create(@PathVariable Long id, @RequestBody Map<String, Object> body,
                                                 HttpServletRequest request) {
        return ApiResponse.ok(scheduleService.create(id, body, CurrentUser.get(request)));
    }

    @GetMapping("/cases/{id}/schedules")
    public ApiResponse<List<MediationSchedule>> list(@PathVariable Long id) {
        return ApiResponse.ok(scheduleService.caseSchedules(id));
    }

    /** 改期（原记录保留留痕，记录取消原因/发起方；当事人临时变更绩效免责） */
    @PostMapping("/schedules/{id}/reschedule")
    public ApiResponse<MediationSchedule> reschedule(@PathVariable Long id, @RequestBody Map<String, Object> body,
                                                     HttpServletRequest request) {
        return ApiResponse.ok(scheduleService.reschedule(id, body, CurrentUser.get(request)));
    }

    /** 改期前实时冲突检测（调解员排班 + 调解室/线上会议占用） */
    @GetMapping("/schedules/{id}/conflict-check")
    public ApiResponse<Map<String, Object>> conflictCheck(@PathVariable Long id,
                                                          @RequestParam String start,
                                                          @RequestParam String end,
                                                          @RequestParam(required = false) String channel,
                                                          @RequestParam(required = false) Long roomId) {
        return ApiResponse.ok(scheduleService.checkConflict(id, LocalDateTime.parse(start),
                LocalDateTime.parse(end), channel == null ? null : Channel.valueOf(channel), roomId));
    }

    /** 平台重匹配：在双方可用时间交集内匹配调解员 + 调解室/线上会议 */
    @PostMapping("/cases/{id}/rematch/options")
    public ApiResponse<Map<String, Object>> rematch(@PathVariable Long id, @RequestBody Map<String, Object> body) {
        return ApiResponse.ok(scheduleService.rematchOptions(id, body));
    }

    /** 提交重匹配建议，等待窗口人员确认分流 */
    @PostMapping("/cases/{id}/rematch/propose")
    public ApiResponse<RescheduleProposal> propose(@PathVariable Long id, @RequestBody Map<String, Object> body,
                                                   HttpServletRequest request) {
        return ApiResponse.ok(scheduleService.propose(id, body, CurrentUser.get(request)));
    }

    /** 案件的重匹配建议列表 */
    @GetMapping("/cases/{id}/proposals")
    public ApiResponse<List<RescheduleProposal>> proposals(@PathVariable Long id) {
        return ApiResponse.ok(scheduleService.caseProposals(id));
    }

    /** 窗口人员确认分流，按建议落定改期 */
    @PostMapping("/proposals/{id}/confirm")
    public ApiResponse<RescheduleProposal> confirmProposal(@PathVariable Long id, HttpServletRequest request) {
        return ApiResponse.ok(scheduleService.confirmProposal(id, CurrentUser.get(request)));
    }

    /** 窗口驳回重匹配建议 */
    @PostMapping("/proposals/{id}/reject")
    public ApiResponse<RescheduleProposal> rejectProposal(@PathVariable Long id, @RequestBody Map<String, Object> body,
                                                          HttpServletRequest request) {
        return ApiResponse.ok(scheduleService.rejectProposal(id, (String) body.get("reason"),
                CurrentUser.get(request)));
    }

    @PostMapping("/schedules/{id}/confirm")
    public ApiResponse<Void> confirm(@PathVariable Long id, @RequestBody Map<String, Object> body,
                                     HttpServletRequest request) {
        scheduleService.confirmAttend(id, (String) body.getOrDefault("party", "APPLICANT"),
                CurrentUser.get(request));
        return ApiResponse.ok();
    }
}
