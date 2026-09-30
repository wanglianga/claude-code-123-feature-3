package cn.court.mediation.controller;

import cn.court.mediation.common.ApiResponse;
import cn.court.mediation.security.CurrentUser;
import cn.court.mediation.service.EfficiencyService;
import cn.court.mediation.service.MediatorPerformanceService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * 多次改期调解效率风险、案件分流建议、窗口决定；
 * 调解员绩效（当事人临时改期免责，工作人员原因计入延误）。
 */
@RestController
@RequestMapping("/api")
public class EfficiencyController {

    private final EfficiencyService efficiencyService;
    private final MediatorPerformanceService mediatorPerformanceService;

    public EfficiencyController(EfficiencyService efficiencyService,
                                MediatorPerformanceService mediatorPerformanceService) {
        this.efficiencyService = efficiencyService;
        this.mediatorPerformanceService = mediatorPerformanceService;
    }

    /** 案件的改期效率风险与分流建议 */
    @GetMapping("/cases/{id}/efficiency")
    public ApiResponse<Map<String, Object>> suggestion(@PathVariable Long id) {
        return ApiResponse.ok(efficiencyService.suggestion(id));
    }

    /** 窗口人员决定：CONTINUE 继续调解 / TRIAGE 确认分流（自动汇编转立案材料） */
    @PostMapping("/cases/{id}/efficiency/decision")
    public ApiResponse<Map<String, Object>> decide(@PathVariable Long id, @RequestBody Map<String, Object> body,
                                                   HttpServletRequest request) {
        return ApiResponse.ok(efficiencyService.decide(id, body, CurrentUser.get(request)));
    }

    /** 调解员绩效概览（调解员仅看本人；窗口/管理员看全部） */
    @GetMapping("/mediator-performance")
    public ApiResponse<List<Map<String, Object>>> performance(HttpServletRequest request) {
        return ApiResponse.ok(mediatorPerformanceService.overview(CurrentUser.get(request)));
    }

    /** 单个调解员改期责任明细（当事人变更免责 / 工作人员延误） */
    @GetMapping("/mediator-performance/{id}")
    public ApiResponse<Map<String, Object>> performanceDetail(@PathVariable Long id) {
        return ApiResponse.ok(mediatorPerformanceService.detail(id));
    }
}
