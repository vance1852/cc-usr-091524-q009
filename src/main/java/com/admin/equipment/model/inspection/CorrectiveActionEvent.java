package com.admin.equipment.model.inspection;

import jakarta.persistence.*;

import java.time.LocalDateTime;

/**
 * 整改措施状态事件（append-only，只追加不修改不删除）。
 * 每一次创建、提交、验证通过、验证退回、取消都落一条事件，保留提交-验证轮次。
 */
@Entity
@Table(name = "corrective_action_events",
        indexes = {
                @Index(name = "idx_cae_action", columnList = "action_id"),
                @Index(name = "idx_cae_abnormality", columnList = "abnormality_id")
        })
public class CorrectiveActionEvent {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "action_id", nullable = false)
    private Long actionId;

    @Column(name = "abnormality_id", nullable = false)
    private Long abnormalityId;

    // created / submitted / verified / rejected / cancelled
    @Column(nullable = false, length = 24)
    private String type;

    // 该事件所属提交-验证轮次
    @Column(name = "round_no")
    private Integer roundNo = 0;

    @Column(name = "from_status", length = 16)
    private String fromStatus = "";

    @Column(name = "to_status", length = 16)
    private String toStatus = "";

    @Column(length = 64)
    private String actor = "";

    @Column(length = 1024)
    private String evidence = "";

    @Column(length = 512)
    private String comment = "";

    @Column(name = "occurred_at")
    private LocalDateTime occurredAt = LocalDateTime.now();

    public CorrectiveActionEvent() {}

    public CorrectiveActionEvent(Long actionId, Long abnormalityId, String type, Integer roundNo,
                                  String fromStatus, String toStatus, String actor,
                                  String evidence, String comment) {
        this.actionId = actionId;
        this.abnormalityId = abnormalityId;
        this.type = type;
        this.roundNo = roundNo;
        this.fromStatus = fromStatus == null ? "" : fromStatus;
        this.toStatus = toStatus == null ? "" : toStatus;
        this.actor = actor == null ? "" : actor;
        this.evidence = evidence == null ? "" : evidence;
        this.comment = comment == null ? "" : comment;
        this.occurredAt = LocalDateTime.now();
    }

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Long getActionId() { return actionId; }
    public void setActionId(Long actionId) { this.actionId = actionId; }
    public Long getAbnormalityId() { return abnormalityId; }
    public void setAbnormalityId(Long abnormalityId) { this.abnormalityId = abnormalityId; }
    public String getType() { return type; }
    public void setType(String type) { this.type = type; }
    public Integer getRoundNo() { return roundNo; }
    public void setRoundNo(Integer roundNo) { this.roundNo = roundNo; }
    public String getFromStatus() { return fromStatus; }
    public void setFromStatus(String fromStatus) { this.fromStatus = fromStatus; }
    public String getToStatus() { return toStatus; }
    public void setToStatus(String toStatus) { this.toStatus = toStatus; }
    public String getActor() { return actor; }
    public void setActor(String actor) { this.actor = actor; }
    public String getEvidence() { return evidence; }
    public void setEvidence(String evidence) { this.evidence = evidence; }
    public String getComment() { return comment; }
    public void setComment(String comment) { this.comment = comment; }
    public LocalDateTime getOccurredAt() { return occurredAt; }
    public void setOccurredAt(LocalDateTime occurredAt) { this.occurredAt = occurredAt; }
}
