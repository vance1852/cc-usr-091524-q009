package com.admin.equipment.model.inspection;

import jakarta.persistence.*;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 风险接受记录。
 * 长期整改措施未完成时，允许以“带风险运行”替代闭环，但必须有到期日并明确责任人；
 * 风险接受绝不等于 closedLoop，到期后自动视为失效（逾期）。
 */
@Entity
@Table(name = "risk_acceptances")
public class RiskAcceptance {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "abnormality_id", nullable = false)
    private Long abnormalityId;

    /** 接受风险时仍未完成的长期措施 ID，逗号分隔。 */
    @Column(name = "outstanding_measure_ids", length = 255)
    private String outstandingMeasureIds = "";

    @Column(nullable = false, length = 512)
    private String reason;

    @Column(name = "accepted_by", nullable = false, length = 64)
    private String acceptedBy;

    @Column(name = "accepted_at", nullable = false)
    private LocalDateTime acceptedAt = LocalDateTime.now();

    @Column(name = "expire_date", nullable = false)
    private LocalDate expireDate;

    /** 闭环达成后解除；解除不删除记录。 */
    @Column(name = "released", nullable = false)
    private Boolean released = false;

    @Column(name = "released_by", length = 64)
    private String releasedBy;

    @Column(name = "released_at")
    private LocalDateTime releasedAt;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Long getAbnormalityId() { return abnormalityId; }
    public void setAbnormalityId(Long abnormalityId) { this.abnormalityId = abnormalityId; }
    public String getOutstandingMeasureIds() { return outstandingMeasureIds; }
    public void setOutstandingMeasureIds(String outstandingMeasureIds) { this.outstandingMeasureIds = outstandingMeasureIds; }
    public String getReason() { return reason; }
    public void setReason(String reason) { this.reason = reason; }
    public String getAcceptedBy() { return acceptedBy; }
    public void setAcceptedBy(String acceptedBy) { this.acceptedBy = acceptedBy; }
    public LocalDateTime getAcceptedAt() { return acceptedAt; }
    public void setAcceptedAt(LocalDateTime acceptedAt) { this.acceptedAt = acceptedAt; }
    public LocalDate getExpireDate() { return expireDate; }
    public void setExpireDate(LocalDate expireDate) { this.expireDate = expireDate; }
    public Boolean getReleased() { return released; }
    public void setReleased(Boolean released) { this.released = released; }
    public String getReleasedBy() { return releasedBy; }
    public void setReleasedBy(String releasedBy) { this.releasedBy = releasedBy; }
    public LocalDateTime getReleasedAt() { return releasedAt; }
    public void setReleasedAt(LocalDateTime releasedAt) { this.releasedAt = releasedAt; }
}
