package com.admin.equipment.model.inspection;

import jakarta.persistence.*;

import java.time.LocalDateTime;

/**
 * 异常级领域事件（append-only）：
 * recheck_passed / recheck_failed / recheck_recorded /
 * cause_analysis_submitted / cause_analysis_approved / cause_analysis_rejected /
 * risk_accepted / risk_expired /
 * work_order_synced / closed_loop / closed_loop_reopened
 * 复检记录只追加，工单取消或异常重新打开均不删除既有复检。
 */
@Entity
@Table(name = "abnormality_events",
        indexes = @Index(name = "idx_abe_abnormality", columnList = "abnormality_id"))
public class AbnormalityEvent {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "abnormality_id", nullable = false)
    private Long abnormalityId;

    @Column(nullable = false, length = 32)
    private String type;

    @Column(length = 64)
    private String actor = "";

    @Column(name = "work_order_id")
    private Long workOrderId;

    @Column(name = "work_order_status", length = 16)
    private String workOrderStatus = "";

    // 风险接受到期日（risk_accepted 事件使用）
    @Column(name = "risk_expiry_date")
    private LocalDateTime riskExpiryDate;

    @Column(name = "closed_loop")
    private Boolean closedLoop = false;

    @Column(length = 1024)
    private String detail = "";

    @Column(name = "occurred_at")
    private LocalDateTime occurredAt = LocalDateTime.now();

    public AbnormalityEvent() {}

    public AbnormalityEvent(Long abnormalityId, String type, String actor, String detail) {
        this.abnormalityId = abnormalityId;
        this.type = type;
        this.actor = actor == null ? "" : actor;
        this.detail = detail == null ? "" : detail;
        this.closedLoop = false;
        this.occurredAt = LocalDateTime.now();
    }

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Long getAbnormalityId() { return abnormalityId; }
    public void setAbnormalityId(Long abnormalityId) { this.abnormalityId = abnormalityId; }
    public String getType() { return type; }
    public void setType(String type) { this.type = type; }
    public String getActor() { return actor; }
    public void setActor(String actor) { this.actor = actor; }
    public Long getWorkOrderId() { return workOrderId; }
    public void setWorkOrderId(Long workOrderId) { this.workOrderId = workOrderId; }
    public String getWorkOrderStatus() { return workOrderStatus; }
    public void setWorkOrderStatus(String workOrderStatus) { this.workOrderStatus = workOrderStatus; }
    public LocalDateTime getRiskExpiryDate() { return riskExpiryDate; }
    public void setRiskExpiryDate(LocalDateTime riskExpiryDate) { this.riskExpiryDate = riskExpiryDate; }
    public Boolean getClosedLoop() { return closedLoop; }
    public void setClosedLoop(Boolean closedLoop) { this.closedLoop = closedLoop; }
    public String getDetail() { return detail; }
    public void setDetail(String detail) { this.detail = detail; }
    public LocalDateTime getOccurredAt() { return occurredAt; }
    public void setOccurredAt(LocalDateTime occurredAt) { this.occurredAt = occurredAt; }
}
