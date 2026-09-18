package com.admin.equipment.model.inspection;

import jakarta.persistence.*;
import java.time.LocalDateTime;

/**
 * 整改措施 / 异常闭环相关事件，只追加、不修改、不删除。
 * type 取值见 RectificationService.EVENT_*。
 */
@Entity
@Table(name = "rectification_events",
        indexes = {
                @Index(name = "idx_re_event_ab", columnList = "abnormality_id"),
                @Index(name = "idx_re_event_measure", columnList = "measure_id")
        })
public class RectificationEvent {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "abnormality_id", nullable = false)
    private Long abnormalityId;

    /** 异常级别的事件（复检、原因分析、风险接受、工单同步等）该字段为空。 */
    @Column(name = "measure_id")
    private Long measureId;

    @Column(nullable = false, length = 32)
    private String type;

    @Column(length = 64)
    private String actor = "";

    @Column(length = 512)
    private String comment = "";

    /** 事件发生时措施所处的验证轮次（措施类事件使用）。 */
    @Column(name = "verify_round")
    private Integer verifyRound;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt = LocalDateTime.now();

    public RectificationEvent() {}

    public RectificationEvent(Long abnormalityId, Long measureId, String type,
                               String actor, String comment, Integer verifyRound) {
        this.abnormalityId = abnormalityId;
        this.measureId = measureId;
        this.type = type;
        this.actor = actor == null ? "" : actor;
        this.comment = comment == null ? "" : comment;
        this.verifyRound = verifyRound;
        this.createdAt = LocalDateTime.now();
    }

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Long getAbnormalityId() { return abnormalityId; }
    public void setAbnormalityId(Long abnormalityId) { this.abnormalityId = abnormalityId; }
    public Long getMeasureId() { return measureId; }
    public void setMeasureId(Long measureId) { this.measureId = measureId; }
    public String getType() { return type; }
    public void setType(String type) { this.type = type; }
    public String getActor() { return actor; }
    public void setActor(String actor) { this.actor = actor; }
    public String getComment() { return comment; }
    public void setComment(String comment) { this.comment = comment; }
    public Integer getVerifyRound() { return verifyRound; }
    public void setVerifyRound(Integer verifyRound) { this.verifyRound = verifyRound; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
}
