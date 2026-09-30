package cn.court.mediation.controller;

import cn.court.mediation.common.ApiResponse;
import cn.court.mediation.service.MediatorPerformanceService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/** 调解员绩效：改期按发起方归责，当事人临时变更不计工作人员延误 */
@RestController
@RequestMapping("/api/mediator-performance")
public class MediatorPerformanceController {

    private final MediatorPerformanceService service;

    public MediatorPerformanceController(MediatorPerformanceService service) {
        this.service = service;
    }

    @GetMapping
    public ApiResponse<List<Map<String, Object>>> overview() {
        return ApiResponse.ok(service.overview());
    }

    @GetMapping("/{id}")
    public ApiResponse<Map<String, Object>> detail(@PathVariable Long id) {
        return ApiResponse.ok(service.detail(id));
    }
}
