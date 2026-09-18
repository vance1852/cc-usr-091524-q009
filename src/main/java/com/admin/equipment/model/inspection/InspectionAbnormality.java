package com.admin.equipment.model.inspection;

import jakarta.persistence.*;
import java.time.LocalDateTime;

@Entity
@Table(name = "inspection_abnormalities")
public class InspectionAbnormality {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "task_id", nullable = false)
    private Long taskId;

    @Column(name = "task_point_id", nullable = false)
    private Long taskPointId;

    @Column(name = "record_id")
    private Long recordId;

    @Column(name = "point_id")
    private Long pointId;

    @Column(name = "equipment_id")
    private Long equipmentId;

    @Column(name = "equipment_code", length = 32)
    private String equipmentCode = "";

    @Column(name = "equipment_name", length = 128)
    private String equipmentName = "";

    @Column(name = "item_name", length = 128)
    private String itemName = "";

    @Column(nullable = false, length = 256)
    private String title;

    @Column(length = 1024)
    private String description = "";

    @Column(length = 16)
    private String severity = "medium";

    @Column(length = 16)
    private String status = "reported";

    @Column(name = "work_order_id")
    private Long workOrderId;

    @Column(name = "work_order_type", length = 16)
    private String workOrderType = "repair";

    @Column(name = "work_order_created")
    private Boolean workOrderCreated = false;

    @Column(name = "recheck_result", length = 16)
    private String recheckResult;

    @Column(name = "recheck_at")
    private LocalDateTime recheckAt;

    @Column(name = "recheck_by", length = 64)
    private String recheckBy = "";

    @Column(name = "reported_at")
    private LocalDateTime reportedAt = LocalDateTime.now();

    @Column(name = "resolved_at")
    private LocalDateTime resolvedAt;

    @Column(name = "closed_loop")
    private Boolean closedLoop = false;

    @Column(name = "closed_loop_at")
    private LocalDateTime closedLoopAt;

    // ---- 原因分析（严重异常 high/urgent 关闭前必须获批）----
    @Column(name = "cause_analysis", length = 2048)
    private String causeAnalysis = "";

    @Column(name = "cause_submitted_by", length = 64)
    private String causeSubmittedBy = "";

    @Column(name = "cause_submitted_at")
    private LocalDateTime causeSubmittedAt;

    // null 未审批 / true 批准 / false 驳回
    @Column(name = "cause_approved")
    private Boolean causeApproved;

    @Column(name = "cause_approved_by", length = 64)
    private String causeApprovedBy = "";

    @Column(name = "cause_approved_at")
    private LocalDateTime causeApprovedAt;

    @Column(name = "cause_reject_reason", length = 512)
    private String causeRejectReason = "";

    // ---- 风险接受（长期措施无法按期完成时登记，绝不视为闭环）----
    @Column(name = "risk_accepted")
    private Boolean riskAccepted = false;

    @Column(name = "risk_accepted_by", length = 64)
    private String riskAcceptedBy = "";

    @Column(name = "risk_accepted_at")
    private LocalDateTime riskAcceptedAt;

    @Column(name = "risk_expiry_date")
    private LocalDateTime riskExpiryDate;

    @Column(name = "risk_reason", length = 1024)
    private String riskReason = "";

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Long getTaskId() { return taskId; }
    public void setTaskId(Long taskId) { this.taskId = taskId; }
    public Long getTaskPointId() { return taskPointId; }
    public void setTaskPointId(Long taskPointId) { this.taskPointId = taskPointId; }
    public Long getRecordId() { return recordId; }
    public void setRecordId(Long recordId) { this.recordId = recordId; }
    public Long getPointId() { return pointId; }
    public void setPointId(Long pointId) { this.pointId = pointId; }
    public Long getEquipmentId() { return equipmentId; }
    public void setEquipmentId(Long equipmentId) { this.equipmentId = equipmentId; }
    public String getEquipmentCode() { return equipmentCode; }
    public void setEquipmentCode(String equipmentCode) { this.equipmentCode = equipmentCode; }
    public String getEquipmentName() { return equipmentName; }
    public void setEquipmentName(String equipmentName) { this.equipmentName = equipmentName; }
    public String getItemName() { return itemName; }
    public void setItemName(String itemName) { this.itemName = itemName; }
    public String getTitle() { return title; }
    public void setTitle(String title) { this.title = title; }
    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }
    public String getSeverity() { return severity; }
    public void setSeverity(String severity) { this.severity = severity; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public Long getWorkOrderId() { return workOrderId; }
    public void setWorkOrderId(Long workOrderId) { this.workOrderId = workOrderId; }
    public String getWorkOrderType() { return workOrderType; }
    public void setWorkOrderType(String workOrderType) { this.workOrderType = workOrderType; }
    public Boolean getWorkOrderCreated() { return workOrderCreated; }
    public void setWorkOrderCreated(Boolean workOrderCreated) { this.workOrderCreated = workOrderCreated; }
    public String getRecheckResult() { return recheckResult; }
    public void setRecheckResult(String recheckResult) { this.recheckResult = recheckResult; }
    public LocalDateTime getRecheckAt() { return recheckAt; }
    public void setRecheckAt(LocalDateTime recheckAt) { this.recheckAt = recheckAt; }
    public String getRecheckBy() { return recheckBy; }
    public void setRecheckBy(String recheckBy) { this.recheckBy = recheckBy; }
    public LocalDateTime getReportedAt() { return reportedAt; }
    public void setReportedAt(LocalDateTime reportedAt) { this.reportedAt = reportedAt; }
    public LocalDateTime getResolvedAt() { return resolvedAt; }
    public void setResolvedAt(LocalDateTime resolvedAt) { this.resolvedAt = resolvedAt; }
    public Boolean getClosedLoop() { return closedLoop; }
    public void setClosedLoop(Boolean closedLoop) { this.closedLoop = closedLoop; }
    public LocalDateTime getClosedLoopAt() { return closedLoopAt; }
    public void setClosedLoopAt(LocalDateTime closedLoopAt) { this.closedLoopAt = closedLoopAt; }
    public String getCauseAnalysis() { return causeAnalysis; }
    public void setCauseAnalysis(String causeAnalysis) { this.causeAnalysis = causeAnalysis; }
    public String getCauseSubmittedBy() { return causeSubmittedBy; }
    public void setCauseSubmittedBy(String causeSubmittedBy) { this.causeSubmittedBy = causeSubmittedBy; }
    public LocalDateTime getCauseSubmittedAt() { return causeSubmittedAt; }
    public void setCauseSubmittedAt(LocalDateTime causeSubmittedAt) { this.causeSubmittedAt = causeSubmittedAt; }
    public Boolean getCauseApproved() { return causeApproved; }
    public void setCauseApproved(Boolean causeApproved) { this.causeApproved = causeApproved; }
    public String getCauseApprovedBy() { return causeApprovedBy; }
    public void setCauseApprovedBy(String causeApprovedBy) { this.causeApprovedBy = causeApprovedBy; }
    public LocalDateTime getCauseApprovedAt() { return causeApprovedAt; }
    public void setCauseApprovedAt(LocalDateTime causeApprovedAt) { this.causeApprovedAt = causeApprovedAt; }
    public String getCauseRejectReason() { return causeRejectReason; }
    public void setCauseRejectReason(String causeRejectReason) { this.causeRejectReason = causeRejectReason; }
    public Boolean getRiskAccepted() { return riskAccepted; }
    public void setRiskAccepted(Boolean riskAccepted) { this.riskAccepted = riskAccepted; }
    public String getRiskAcceptedBy() { return riskAcceptedBy; }
    public void setRiskAcceptedBy(String riskAcceptedBy) { this.riskAcceptedBy = riskAcceptedBy; }
    public LocalDateTime getRiskAcceptedAt() { return riskAcceptedAt; }
    public void setRiskAcceptedAt(LocalDateTime riskAcceptedAt) { this.riskAcceptedAt = riskAcceptedAt; }
    public LocalDateTime getRiskExpiryDate() { return riskExpiryDate; }
    public void setRiskExpiryDate(LocalDateTime riskExpiryDate) { this.riskExpiryDate = riskExpiryDate; }
    public String getRiskReason() { return riskReason; }
    public void setRiskReason(String riskReason) { this.riskReason = riskReason; }

    /** 严重异常：high / urgent，原因分析获批前不得闭环 */
    public boolean isCriticalSeverity() {
        return "high".equals(severity) || "urgent".equals(severity);
    }
}
