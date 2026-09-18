package com.admin.equipment.web;

import com.admin.equipment.model.WorkOrder;
import com.admin.equipment.repo.EquipmentRepository;
import com.admin.equipment.repo.WorkOrderRepository;
import com.admin.equipment.service.inspection.AbnormalityRectificationService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import java.util.Set;

@RestController
@RequestMapping("/api/work-orders")
public class WorkOrderController {

    private static final Set<String> TYPES = Set.of("inspection", "repair", "maintenance");
    private static final Set<String> PRIORITIES = Set.of("low", "medium", "high", "urgent");
    // open 待处理 / in_progress 处理中 / done 已完成 / cancelled 已取消
    private static final Set<String> STATUSES = Set.of("open", "in_progress", "done", "cancelled");

    private final WorkOrderRepository repo;
    private final EquipmentRepository equipmentRepo;
    private final AbnormalityRectificationService rectificationService;

    public WorkOrderController(WorkOrderRepository repo, EquipmentRepository equipmentRepo,
                               AbnormalityRectificationService rectificationService) {
        this.repo = repo;
        this.equipmentRepo = equipmentRepo;
        this.rectificationService = rectificationService;
    }

    public record WorkOrderRequest(Long equipmentId, String title, String type, String priority,
                                   String description, String assignee) {}

    public record StatusRequest(String status) {}

    @GetMapping
    public List<WorkOrder> list(@RequestParam(required = false) Long equipmentId,
                                @RequestParam(required = false) String status) {
        if (equipmentId != null) {
            return repo.findByEquipmentIdOrderByIdDesc(equipmentId);
        }
        if (status != null) {
            return repo.findByStatusOrderByIdDesc(status);
        }
        return repo.findAllByOrderByIdDesc();
    }

    @PostMapping
    public ResponseEntity<?> create(@RequestBody WorkOrderRequest req) {
        if (req.equipmentId() == null || req.title() == null || req.title().isBlank()) {
            return ResponseEntity.unprocessableEntity().body(Map.of("detail", "设备和标题必填"));
        }
        if (!equipmentRepo.existsById(req.equipmentId())) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("detail", "设备不存在"));
        }
        WorkOrder w = new WorkOrder();
        w.setEquipmentId(req.equipmentId());
        w.setTitle(req.title());
        w.setType(TYPES.contains(req.type()) ? req.type() : "inspection");
        w.setPriority(PRIORITIES.contains(req.priority()) ? req.priority() : "medium");
        w.setDescription(req.description() == null ? "" : req.description());
        w.setAssignee(req.assignee() == null ? "" : req.assignee());
        w.setStatus("open");
        return ResponseEntity.status(HttpStatus.CREATED).body(repo.save(w));
    }

    @PatchMapping("/{id}/status")
    public ResponseEntity<?> updateStatus(@PathVariable Long id, @RequestBody StatusRequest req) {
        if (req.status() == null || !STATUSES.contains(req.status())) {
            return ResponseEntity.unprocessableEntity().body(Map.of("detail", "状态不合法"));
        }
        if (!repo.existsById(id)) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("detail", "工单不存在"));
        }
        try {
            // 统一入口：工单状态与关联异常闭环状态在同一事务内按规则同步
            WorkOrder w = rectificationService.applyWorkOrderStatusChange(id, req.status());
            return ResponseEntity.ok(w);
        } catch (IllegalArgumentException e) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("detail", e.getMessage()));
        }
    }
}
