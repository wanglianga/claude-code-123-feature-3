package cn.court.mediation.controller;

import cn.court.mediation.common.ApiResponse;
import cn.court.mediation.entity.MediationCase;
import cn.court.mediation.security.CurrentUser;
import cn.court.mediation.service.DivertService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/** 多次改期案件分流建议与窗口"继续调解/确认分流"决定 */
@RestController
@RequestMapping("/api")
public class DivertController {

    private final DivertService divertService;

    public DivertController(DivertService divertService) {
        this.divertService = divertService;
    }

    /** 分流建议清单（多次改期≥2次、仍在调解进程的案件） */
    @GetMapping("/divert-suggestions")
    public ApiResponse<Map<String, Object>> suggestions() {
        return ApiResponse.ok(divertService.suggestions());
    }

    /** 窗口决定是否继续调解：CONTINUE / DIVERT */
    @PostMapping("/cases/{id}/continuation-review")
    public ApiResponse<MediationCase> review(@PathVariable Long id, @RequestBody Map<String, Object> body,
                                             HttpServletRequest request) {
        return ApiResponse.ok(divertService.review(id, body, CurrentUser.get(request)));
    }
}
