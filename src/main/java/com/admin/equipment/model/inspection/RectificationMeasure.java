package com.admin.equipment.model.inspection;

import jakarta.persistence.*;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 异常整改措施。
 * 类别：temporary 临时措施 / long_term 长期整改（原因分析走独立的审批流程）。
 * 状态机：pending -> submitted -> verified；验证失败回到 returned（保留轮次），负责人可再次提交。
 */
@Entity
@Table(name = "rectification_measures")
public class RectificationMeasure {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "abnormality_id", nullable = false)
    private Long abnormalityId;

    /** 异常内的措施序号，从 1 开始，便于人工引用。 */
    @Column(nullable = false)
    private Integer seq = 1;

    // temporary / long_term
    @Column(nullable = false, length = 16)
    private String category = "temporary";

    @Column(nullable = false, length = 128)
    private String title;

    @Column(length = 1024)
    private String description = "";

    @Column(length = 64)
    private String owner = "";

    @Column(name = "due_date")
    private LocalDate dueDate;

    /** 前置措施 ID，逗号分隔，结构同 InspectionTaskPoint.equipmentIds。 */
    @Column(name = "predecessor_ids", length = 255)
    private String predecessorIds = "";

    // pending / submitted / verified / returned
    @Column(nullable = false, length = 16)
    private String status = "pending";

    /** 验证轮次：首次提交置 1，每次退回后重新提交 +1。 */
    @Column(name = "verify_round", nullable = false)
    private Integer verifyRound = 0;

    @Column(length = 2048)
    private String evidence;

    @Column(name = "submitted_by", length = 64)
    private String submittedBy;

    @Column(name = "submitted_at")
    private LocalDateTime submittedAt;

    @Column(name = "verified_by", length = 64)
    private String verifiedBy;

    @Column(name = "verified_at")
    private LocalDateTime verifiedAt;

    @Column(name = "verify_comment", length = 512)
    private String verifyComment;

    @Column(name = "created_at")
    private LocalDateTime createdAt = LocalDateTime.now();

    @Column(name = "updated_at")
    private LocalDateTime updatedAt = LocalDateTime.now();

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Long getAbnormalityId() { return abnormalityId; }
    public void setAbnormalityId(Long abnormalityId) { this.abnormalityId = abnormalityId; }
    public Integer getSeq() { return seq; }
    public void setSeq(Integer seq) { this.seq = seq; }
    public String getCategory() { return category; }
    public void setCategory(String category) { this.category = category; }
    public String getTitle() { return title; }
    public void setTitle(String title) { this.title = title; }
    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }
    public String getOwner() { return owner; }
    public void setOwner(String owner) { this.owner = owner; }
    public LocalDate getDueDate() { return dueDate; }
    public void setDueDate(LocalDate dueDate) { this.dueDate = dueDate; }
    public String getPredecessorIds() { return predecessorIds; }
    public void setPredecessorIds(String predecessorIds) { this.predecessorIds = predecessorIds; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public Integer getVerifyRound() { return verifyRound; }
    public void setVerifyRound(Integer verifyRound) { this.verifyRound = verifyRound; }
    public String getEvidence() { return evidence; }
    public void setEvidence(String evidence) { this.evidence = evidence; }
    public String getSubmittedBy() { return submittedBy; }
    public void setSubmittedBy(String submittedBy) { this.submittedBy = submittedBy; }
    public LocalDateTime getSubmittedAt() { return submittedAt; }
    public void setSubmittedAt(LocalDateTime submittedAt) { this.submittedAt = submittedAt; }
    public String getVerifiedBy() { return verifiedBy; }
    public void setVerifiedBy(String verifiedBy) { this.verifiedBy = verifiedBy; }
    public LocalDateTime getVerifiedAt() { return verifiedAt; }
    public void setVerifiedAt(LocalDateTime verifiedAt) { this.verifiedAt = verifiedAt; }
    public String getVerifyComment() { return verifyComment; }
    public void setVerifyComment(String verifyComment) { this.verifyComment = verifyComment; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
    public LocalDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(LocalDateTime updatedAt) { this.updatedAt = updatedAt; }
}
