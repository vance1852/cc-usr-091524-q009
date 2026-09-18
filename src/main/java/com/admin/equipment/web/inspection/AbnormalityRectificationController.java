package com.admin.equipment.web.inspection;

import com.admin.equipment.model.AppUser;
import com.admin.equipment.model.inspection.*;
import com.admin.equipment.service.inspection.AbnormalityRectificationService;
import com.admin.equipment.service.inspection.AbnormalityRectificationService.CreateActionRequest;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * 异常整改闭环 API。
 * 质量主管使用聚合视图（依赖、逾期、验证人、风险接受到期日）；
 * 巡检员通过 /tasks/{taskId}/rectification 从原任务追溯整改进展。
 */
@RestController
@RequestMapping("/api/inspection")
public class AbnormalityRectificationController {

    private final AbnormalityRectificationService service;

    public AbnormalityRectificationController(AbnormalityRectificationService service) {
        this.service = service;
    }

    public record ActionCreateRequest(String category, String title, String content, String ownerName,
                                      LocalDateTime dueDate, List<Long> prerequisiteIds, String actor) {}

    public record SubmitRequest(String evidence, String actor) {}

    public record VerifyRequest(Boolean pass, String comment, String actor) {}

    public record CancelRequest(String reason, String actor) {}

    public record CauseAnalysisRequest(String causeAnalysis, String actor) {}

    public record CauseApprovalRequest(Boolean approved, String reason, String actor) {}

    public record RiskAcceptanceRequest(String reason, LocalDateTime expiryDate, String actor) {}

    private String resolveActor(HttpServletRequest request, String bodyActor) {
        if (bodyActor != null && !bodyActor.isBlank()) return bodyActor.trim();
        AppUser user = (AppUser) request.getAttribute("currentUser");
        if (user != null) {
            return user.getDisplayName() != null && !user.getDisplayName().isBlank()
                    ? user.getDisplayName() : user.getUsername();
        }
        return "";
    }

    private ResponseEntity<?> handle(java.util.function.Supplier<Object> fn) {
        try {
            return ResponseEntity.ok(fn.get());
        } catch (IllegalArgumentException e) {
            return ResponseEntity.unprocessableEntity().body(Map.of("detail", e.getMessage()));
        } catch (IllegalStateException e) {
            return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of("detail", e.getMessage()));
        }
    }

    // ---------------- 质量主管 / 巡检员视图 ----------------

    /** 按异常聚合的整改总览：可按状态过滤、仅看逾期、按设备过滤 */
    @GetMapping("/abnormalities/rectification")
    public ResponseEntity<?> overview(@RequestParam(required = false) String status,
                                      @RequestParam(required = false, defaultValue = "false") boolean overdueOnly,
                                      @RequestParam(required = false) Long equipmentId) {
        return ResponseEntity.ok(service.listRectificationOverview(status, overdueOnly, equipmentId));
    }

    /** 巡检员：从原巡检任务追溯当前整改进展 */
    @GetMapping("/tasks/{taskId}/rectification")
    public ResponseEntity<?> byTask(@PathVariable Long taskId) {
        return ResponseEntity.ok(service.listRectificationByTask(taskId));
    }

    @GetMapping("/abnormalities/{id}/rectification")
    public ResponseEntity<?> detail(@PathVariable Long id) {
        return service.getRectificationView(id)
                .<ResponseEntity<?>>map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.status(HttpStatus.NOT_FOUND)
                        .body(Map.of("detail", "异常不存在")));
    }

    @GetMapping("/abnormalities/{id}/events")
    public ResponseEntity<?> abnormalityEvents(@PathVariable Long id) {
        if (service.getRectificationView(id).isEmpty()) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("detail", "异常不存在"));
        }
        return ResponseEntity.ok(service.getAbnormalityEvents(id));
    }

    @GetMapping("/abnormalities/actions/{actionId}/events")
    public ResponseEntity<?> actionEvents(@PathVariable Long actionId) {
        return ResponseEntity.ok(service.getActionEvents(actionId));
    }

    // ---------------- 整改措施 ----------------

    @PostMapping("/abnormalities/{id}/actions")
    public ResponseEntity<?> createAction(@PathVariable Long id,
                                          @RequestBody ActionCreateRequest req,
                                          HttpServletRequest request) {
        if (req == null || req.title() == null || req.title().isBlank()
                || req.ownerName() == null || req.ownerName().isBlank()) {
            return ResponseEntity.unprocessableEntity()
                    .body(Map.of("detail", "措施标题与负责人必填"));
        }
        String actor = resolveActor(request, req == null ? null : req.actor());
        try {
            CreateActionRequest cmd = new CreateActionRequest(req.category(), req.title(), req.content(),
                    req.ownerName(), req.dueDate(), req.prerequisiteIds());
            AbnormalityCorrectiveAction a = service.createAction(id, cmd, actor);
            return ResponseEntity.status(HttpStatus.CREATED).body(a);
        } catch (IllegalArgumentException e) {
            return ResponseEntity.unprocessableEntity().body(Map.of("detail", e.getMessage()));
        } catch (IllegalStateException e) {
            return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of("detail", e.getMessage()));
        }
    }

    /** 负责人提交完成证据（必须由负责人本人） */
    @PostMapping("/abnormalities/actions/{actionId}/submit")
    public ResponseEntity<?> submit(@PathVariable Long actionId,
                                    @RequestBody SubmitRequest req,
                                    HttpServletRequest request) {
        if (req == null || req.evidence() == null || req.evidence().isBlank()) {
            return ResponseEntity.unprocessableEntity().body(Map.of("detail", "完成证据必填"));
        }
        String actor = resolveActor(request, req.actor());
        return handle(() -> service.submitAction(actionId, req.evidence(), actor));
    }

    /** 由不同人员验证；pass=false 退回原措施并保留轮次 */
    @PostMapping("/abnormalities/actions/{actionId}/verify")
    public ResponseEntity<?> verify(@PathVariable Long actionId,
                                    @RequestBody VerifyRequest req,
                                    HttpServletRequest request) {
        if (req == null || req.pass() == null) {
            return ResponseEntity.unprocessableEntity().body(Map.of("detail", "pass 必填"));
        }
        String actor = resolveActor(request, req.actor());
        return handle(() -> service.verifyAction(actionId, req.pass(), req.comment(), actor));
    }

    @PostMapping("/abnormalities/actions/{actionId}/cancel")
    public ResponseEntity<?> cancel(@PathVariable Long actionId,
                                    @RequestBody CancelRequest req,
                                    HttpServletRequest request) {
        if (req == null || req.reason() == null || req.reason().isBlank()) {
            return ResponseEntity.unprocessableEntity().body(Map.of("detail", "取消原因必填"));
        }
        String actor = resolveActor(request, req.actor());
        return handle(() -> service.cancelAction(actionId, req.reason(), actor));
    }

    // ---------------- 原因分析 ----------------

    @PostMapping("/abnormalities/{id}/cause-analysis")
    public ResponseEntity<?> submitCauseAnalysis(@PathVariable Long id,
                                                 @RequestBody CauseAnalysisRequest req,
                                                 HttpServletRequest request) {
        if (req == null || req.causeAnalysis() == null || req.causeAnalysis().isBlank()) {
            return ResponseEntity.unprocessableEntity().body(Map.of("detail", "原因分析内容必填"));
        }
        String actor = resolveActor(request, req.actor());
        return handle(() -> service.submitCauseAnalysis(id, req.causeAnalysis(), actor));
    }

    @PostMapping("/abnormalities/{id}/cause-approval")
    public ResponseEntity<?> approveCauseAnalysis(@PathVariable Long id,
                                                  @RequestBody CauseApprovalRequest req,
                                                  HttpServletRequest request) {
        if (req == null || req.approved() == null) {
            return ResponseEntity.unprocessableEntity().body(Map.of("detail", "approved 必填"));
        }
        String actor = resolveActor(request, req.actor());
        return handle(() -> service.approveCauseAnalysis(id, req.approved(), req.reason(), actor));
    }

    // ---------------- 风险接受 ----------------

    @PostMapping("/abnormalities/{id}/risk-acceptance")
    public ResponseEntity<?> acceptRisk(@PathVariable Long id,
                                        @RequestBody RiskAcceptanceRequest req,
                                        HttpServletRequest request) {
        if (req == null || req.reason() == null || req.reason().isBlank() || req.expiryDate() == null) {
            return ResponseEntity.unprocessableEntity()
                    .body(Map.of("detail", "风险接受原因与到期日必填"));
        }
        String actor = resolveActor(request, req.actor());
        return handle(() -> service.acceptRisk(id, req.reason(), req.expiryDate(), actor));
    }
}
