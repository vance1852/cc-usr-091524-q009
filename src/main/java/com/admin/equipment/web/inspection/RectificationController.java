package com.admin.equipment.web.inspection;

import com.admin.equipment.model.inspection.InspectionAbnormality;
import com.admin.equipment.model.inspection.RectificationMeasure;
import com.admin.equipment.model.inspection.RiskAcceptance;
import com.admin.equipment.service.inspection.RectificationService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/**
 * 异常整改措施 / 原因分析 / 风险接受 / 闭环跟踪接口。
 * 复检仍走 /api/inspection/tasks/abnormality/recheck，闭环判定已收敛到本特性。
 */
@RestController
@RequestMapping("/api/inspection")
public class RectificationController {

    private final RectificationService service;

    public RectificationController(RectificationService service) {
        this.service = service;
    }

    public record MeasureRequest(String category, String title, String description,
                                 String owner, LocalDate dueDate, List<Long> predecessorIds) {}

    public record SubmitRequest(String submittedBy, String evidence) {}

    public record VerifyRequest(String verifier, Boolean passed, String comment) {}

    public record CauseSubmitRequest(String analysis, String submitter) {}

    public record CauseApproveRequest(String approver, Boolean approved, String comment) {}

    public record RiskAcceptRequest(String reason, String acceptedBy, LocalDate expireDate) {}

    // -------- 异常整改进展（质量主管 / 巡检员共用） --------

    @GetMapping("/abnormalities/{id}/rectification")
    public ResponseEntity<?> detail(@PathVariable Long id) {
        try {
            return ResponseEntity.ok(service.getDetail(id));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("detail", e.getMessage()));
        }
    }

    /** 巡检员：从原任务追到每条异常的当前整改进展。 */
    @GetMapping("/tasks/{taskId}/rectification-progress")
    public ResponseEntity<?> taskProgress(@PathVariable Long taskId) {
        return ResponseEntity.ok(service.getTaskProgress(taskId));
    }

    /** 质量主管：按异常聚合的逾期措施、风险接受到期日、等待原因分析审批的严重异常。 */
    @GetMapping("/supervision/overview")
    public ResponseEntity<?> supervisorOverview() {
        return ResponseEntity.ok(service.supervisorOverview());
    }

    // -------- 整改措施 --------

    @PostMapping("/abnormalities/{id}/measures")
    public ResponseEntity<?> createMeasure(@PathVariable Long id, @RequestBody MeasureRequest req) {
        try {
            RectificationService.MeasureSpec spec = new RectificationService.MeasureSpec(
                    req.category(), req.title(), req.description(),
                    req.owner(), req.dueDate(), req.predecessorIds());
            RectificationMeasure m = service.createMeasure(id, spec);
            return ResponseEntity.status(HttpStatus.CREATED).body(m);
        } catch (IllegalArgumentException e) {
            return ResponseEntity.unprocessableEntity().body(Map.of("detail", e.getMessage()));
        } catch (IllegalStateException e) {
            return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of("detail", e.getMessage()));
        }
    }

    @PostMapping("/abnormalities/{id}/measures/{measureId}/submit")
    public ResponseEntity<?> submitMeasure(@PathVariable Long id, @PathVariable Long measureId,
                                           @RequestBody SubmitRequest req) {
        try {
            RectificationMeasure m = service.submitMeasure(id, measureId,
                    req.submittedBy(), req.evidence());
            return ResponseEntity.ok(m);
        } catch (IllegalArgumentException e) {
            return ResponseEntity.unprocessableEntity().body(Map.of("detail", e.getMessage()));
        } catch (IllegalStateException e) {
            return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of("detail", e.getMessage()));
        }
    }

    @PostMapping("/abnormalities/{id}/measures/{measureId}/verify")
    public ResponseEntity<?> verifyMeasure(@PathVariable Long id, @PathVariable Long measureId,
                                           @RequestBody VerifyRequest req) {
        try {
            boolean passed = req.passed() == null || req.passed();
            RectificationMeasure m = service.verifyMeasure(id, measureId,
                    req.verifier(), passed, req.comment());
            return ResponseEntity.ok(m);
        } catch (IllegalArgumentException e) {
            return ResponseEntity.unprocessableEntity().body(Map.of("detail", e.getMessage()));
        } catch (IllegalStateException e) {
            return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of("detail", e.getMessage()));
        }
    }

    // -------- 原因分析 --------

    @PostMapping("/abnormalities/{id}/cause-analysis")
    public ResponseEntity<?> submitCause(@PathVariable Long id, @RequestBody CauseSubmitRequest req) {
        try {
            InspectionAbnormality ab = service.submitCauseAnalysis(id, req.analysis(), req.submitter());
            return ResponseEntity.ok(ab);
        } catch (IllegalArgumentException e) {
            return ResponseEntity.unprocessableEntity().body(Map.of("detail", e.getMessage()));
        }
    }

    @PostMapping("/abnormalities/{id}/cause-analysis/approval")
    public ResponseEntity<?> approveCause(@PathVariable Long id, @RequestBody CauseApproveRequest req) {
        try {
            if (req.approved() == null) {
                return ResponseEntity.unprocessableEntity().body(Map.of("detail", "approved 必填"));
            }
            InspectionAbnormality ab = service.approveCauseAnalysis(id, req.approver(),
                    req.approved(), req.comment());
            return ResponseEntity.ok(ab);
        } catch (IllegalArgumentException e) {
            return ResponseEntity.unprocessableEntity().body(Map.of("detail", e.getMessage()));
        } catch (IllegalStateException e) {
            return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of("detail", e.getMessage()));
        }
    }

    // -------- 风险接受 --------

    @PostMapping("/abnormalities/{id}/risk-acceptance")
    public ResponseEntity<?> acceptRisk(@PathVariable Long id, @RequestBody RiskAcceptRequest req) {
        try {
            RiskAcceptance ra = service.acceptRisk(id, req.reason(), req.acceptedBy(), req.expireDate());
            return ResponseEntity.status(HttpStatus.CREATED).body(ra);
        } catch (IllegalArgumentException e) {
            return ResponseEntity.unprocessableEntity().body(Map.of("detail", e.getMessage()));
        } catch (IllegalStateException e) {
            return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of("detail", e.getMessage()));
        }
    }
}
