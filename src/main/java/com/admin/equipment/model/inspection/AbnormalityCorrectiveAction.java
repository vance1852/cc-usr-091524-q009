package com.admin.equipment.model.inspection;

import jakarta.persistence.*;

import java.time.LocalDateTime;

/**
 * 异常整改措施。措施状态机：
 * open（待处理）→ submitted（负责人已提交完成，待验证）→ verified（验证通过，终态）
 *                 ↘ 验证失败退回 open（round 加 1，历史轮次以事件保留）
 * cancelled（取消，终态，需说明原因）
 * 前置措施未全部 verified 前，本措施不可提交完成。
 */
@Entity
@Table(name = "abnormality_corrective_actions",
        indexes = @Index(name = "idx_aca_abnormality", columnList = "abnormality_id"))
public class AbnormalityCorrectiveAction {

    public static final String CATEGORY_TEMPORARY = "temporary";   // 临时措施
    public static final String CATEGORY_CAUSE = "cause";           // 原因分析
    public static final String CATEGORY_LONG_TERM = "long_term";   // 长期整改

    public static final String STATUS_OPEN = "open";
    public static final String STATUS_SUBMITTED = "submitted";
    public static final String STATUS_VERIFIED = "verified";
    public static final String STATUS_CANCELLED = "cancelled";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "abnormality_id", nullable = false)
    private Long abnormalityId;

    // 类别：temporary 临时措施 / cause 原因分析 / long_term 长期整改
    @Column(nullable = false, length = 16)
    private String category = CATEGORY_TEMPORARY;

    @Column(nullable = false, length = 256)
    private String title;

    @Column(length = 1024)
    private String content = "";

    // 前置措施ID，逗号分隔；所指措施全部 verified 后本措施方可提交
    @Column(name = "prerequisite_ids", length = 256)
    private String prerequisiteIds = "";

    @Column(name = "owner_name", nullable = false, length = 64)
    private String ownerName;

    @Column(name = "due_date")
    private LocalDateTime dueDate;

    @Column(nullable = false, length = 16)
    private String status = STATUS_OPEN;

    // 提交-验证轮次，首次提交为第 1 轮，每次验证失败退回后 +1
    @Column(name = "current_round")
    private Integer currentRound = 0;

    @Column(name = "submitted_at")
    private LocalDateTime submittedAt;

    @Column(name = "submitted_by", length = 64)
    private String submittedBy = "";

    // 最近一轮提交的完成证据（描述/链接/照片URL等）
    @Column(name = "completion_evidence", length = 1024)
    private String completionEvidence = "";

    @Column(name = "verified_at")
    private LocalDateTime verifiedAt;

    @Column(name = "verified_by", length = 64)
    private String verifiedBy = "";

    @Column(name = "verify_comment", length = 512)
    private String verifyComment = "";

    @Column(name = "cancelled_at")
    private LocalDateTime cancelledAt;

    @Column(name = "cancelled_by", length = 64)
    private String cancelledBy = "";

    @Column(name = "cancel_reason", length = 512)
    private String cancelReason = "";

    @Column(name = "created_at")
    private LocalDateTime createdAt = LocalDateTime.now();

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Long getAbnormalityId() { return abnormalityId; }
    public void setAbnormalityId(Long abnormalityId) { this.abnormalityId = abnormalityId; }
    public String getCategory() { return category; }
    public void setCategory(String category) { this.category = category; }
    public String getTitle() { return title; }
    public void setTitle(String title) { this.title = title; }
    public String getContent() { return content; }
    public void setContent(String content) { this.content = content; }
    public String getPrerequisiteIds() { return prerequisiteIds; }
    public void setPrerequisiteIds(String prerequisiteIds) { this.prerequisiteIds = prerequisiteIds; }
    public String getOwnerName() { return ownerName; }
    public void setOwnerName(String ownerName) { this.ownerName = ownerName; }
    public LocalDateTime getDueDate() { return dueDate; }
    public void setDueDate(LocalDateTime dueDate) { this.dueDate = dueDate; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public Integer getCurrentRound() { return currentRound; }
    public void setCurrentRound(Integer currentRound) { this.currentRound = currentRound; }
    public LocalDateTime getSubmittedAt() { return submittedAt; }
    public void setSubmittedAt(LocalDateTime submittedAt) { this.submittedAt = submittedAt; }
    public String getSubmittedBy() { return submittedBy; }
    public void setSubmittedBy(String submittedBy) { this.submittedBy = submittedBy; }
    public String getCompletionEvidence() { return completionEvidence; }
    public void setCompletionEvidence(String completionEvidence) { this.completionEvidence = completionEvidence; }
    public LocalDateTime getVerifiedAt() { return verifiedAt; }
    public void setVerifiedAt(LocalDateTime verifiedAt) { this.verifiedAt = verifiedAt; }
    public String getVerifiedBy() { return verifiedBy; }
    public void setVerifiedBy(String verifiedBy) { this.verifiedBy = verifiedBy; }
    public String getVerifyComment() { return verifyComment; }
    public void setVerifyComment(String verifyComment) { this.verifyComment = verifyComment; }
    public LocalDateTime getCancelledAt() { return cancelledAt; }
    public void setCancelledAt(LocalDateTime cancelledAt) { this.cancelledAt = cancelledAt; }
    public String getCancelledBy() { return cancelledBy; }
    public void setCancelledBy(String cancelledBy) { this.cancelledBy = cancelledBy; }
    public String getCancelReason() { return cancelReason; }
    public void setCancelReason(String cancelReason) { this.cancelReason = cancelReason; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
}
