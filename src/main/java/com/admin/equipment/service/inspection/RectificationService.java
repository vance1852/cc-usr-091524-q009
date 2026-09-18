package com.admin.equipment.service.inspection;

import com.admin.equipment.model.WorkOrder;
import com.admin.equipment.model.inspection.*;
import com.admin.equipment.repo.WorkOrderRepository;
import com.admin.equipment.repo.inspection.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;

/**
 * 异常整改与闭环管理。
 *
 * <p>闭环（closedLoop=true）唯一入口是 {@link #recomputeClosure}，条件全部满足才置位：
 * <ol>
 *   <li>最近一次复检通过，且复检晚于工单最近一次重新打开时间（复检仍新鲜）；</li>
 *   <li>关联工单（若有）已完成且未被取消；</li>
 *   <li>异常至少有一项整改措施，且所有措施均为 verified；</li>
 *   <li>严重（high/urgent）异常的原因分析已获批。</li>
 * </ol>
 * 风险接受不参与闭环判定，永远不会“凭接受闭环”。
 *
 * <p>所有写操作均在异常行级悲观锁内进行，措施事件只追加，保证并发验证 / 重复回调 /
 * 部分措施完成时闭环判定一致。
 */
@Service
public class RectificationService {

    public static final String EV_MEASURE_CREATED = "measure_created";
    public static final String EV_MEASURE_SUBMITTED = "measure_submitted";
    public static final String EV_MEASURE_VERIFIED = "measure_verified";
    public static final String EV_MEASURE_RETURNED = "measure_returned";
    public static final String EV_CAUSE_SUBMITTED = "cause_submitted";
    public static final String EV_CAUSE_APPROVED = "cause_approved";
    public static final String EV_CAUSE_REJECTED = "cause_rejected";
    public static final String EV_RISK_ACCEPTED = "risk_accepted";
    public static final String EV_RISK_RELEASED = "risk_released";
    public static final String EV_RECHECK_PASSED = "recheck_passed";
    public static final String EV_RECHECK_FAILED = "recheck_failed";
    public static final String EV_RECHECK_OTHER = "recheck_recorded";
    public static final String EV_WO_DONE = "wo_done";
    public static final String EV_WO_CANCELLED = "wo_cancelled";
    public static final String EV_WO_REOPENED = "wo_reopened";
    public static final String EV_CLOSURE_CLOSED = "closure_closed";
    public static final String EV_CLOSURE_OPENED = "closure_opened";

    public record MeasureSpec(String category, String title, String description,
                              String owner, LocalDate dueDate, List<Long> predecessorIds) {}

    private final InspectionAbnormalityRepository abnormalityRepo;
    private final RectificationMeasureRepository measureRepo;
    private final RectificationEventRepository eventRepo;
    private final RiskAcceptanceRepository riskRepo;
    private final WorkOrderRepository workOrderRepo;

    public RectificationService(InspectionAbnormalityRepository abnormalityRepo,
                                RectificationMeasureRepository measureRepo,
                                RectificationEventRepository eventRepo,
                                RiskAcceptanceRepository riskRepo,
                                WorkOrderRepository workOrderRepo) {
        this.abnormalityRepo = abnormalityRepo;
        this.measureRepo = measureRepo;
        this.eventRepo = eventRepo;
        this.riskRepo = riskRepo;
        this.workOrderRepo = workOrderRepo;
    }

    // ============================ 措施管理 ============================

    @Transactional
    public RectificationMeasure createMeasure(Long abnormalityId, MeasureSpec spec) {
        if (spec == null || spec.title() == null || spec.title().isBlank()) {
            throw new IllegalArgumentException("措施标题必填");
        }
        InspectionAbnormality ab = lockAbnormality(abnormalityId);
        List<RectificationMeasure> measures = measureRepo.findByAbnormalityIdOrderBySeqAsc(abnormalityId);
        Set<Long> existingIds = measures.stream().map(RectificationMeasure::getId).collect(Collectors.toSet());

        List<Long> predIds = normalizeIds(spec.predecessorIds());
        for (Long pid : predIds) {
            if (!existingIds.contains(pid)) {
                throw new IllegalArgumentException("前置措施不存在：" + pid);
            }
        }

        RectificationMeasure m = new RectificationMeasure();
        m.setAbnormalityId(abnormalityId);
        m.setSeq(measures.size() + 1);
        m.setCategory(validCategory(spec.category()));
        m.setTitle(spec.title().trim());
        m.setDescription(spec.description() == null ? "" : spec.description());
        m.setOwner(spec.owner() == null ? "" : spec.owner().trim());
        m.setDueDate(spec.dueDate());
        m.setPredecessorIds(idsToString(predIds));
        m.setStatus("pending");
        RectificationMeasure saved = measureRepo.save(m);

        eventRepo.save(new RectificationEvent(abnormalityId, saved.getId(), EV_MEASURE_CREATED,
                m.getOwner(), m.getTitle(), 0));

        // 新增措施意味着出现新的整改要求，若此前已闭环则解除。
        recomputeClosure(ab);
        return saved;
    }

    @Transactional
    public RectificationMeasure submitMeasure(Long abnormalityId, Long measureId,
                                              String submittedBy, String evidence) {
        if (submittedBy == null || submittedBy.isBlank()) {
            throw new IllegalArgumentException("负责人（提交人）必填");
        }
        if (evidence == null || evidence.isBlank()) {
            throw new IllegalArgumentException("完成证据必填");
        }
        InspectionAbnormality ab = lockAbnormality(abnormalityId);
        RectificationMeasure m = getMeasure(abnormalityId, measureId);

        if ("verified".equals(m.getStatus())) {
            // 重复回调：措施已通过验证，直接幂等返回。
            return m;
        }
        // 幂等：同一提交人带相同证据重复回调，不重复计轮次、不重复写事件。
        boolean duplicateCallback = "submitted".equals(m.getStatus())
                && submittedBy.trim().equals(m.getSubmittedBy())
                && evidence.trim().equals(m.getEvidence());
        if (duplicateCallback) {
            return m;
        }
        if ("submitted".equals(m.getStatus())) {
            throw new IllegalStateException("措施已提交，等待验证，不能重复提交");
        }

        // 前置措施必须全部验证通过。
        List<RectificationMeasure> all = measureRepo.findByAbnormalityIdOrderBySeqAsc(abnormalityId);
        Map<Long, RectificationMeasure> byId = all.stream()
                .collect(Collectors.toMap(RectificationMeasure::getId, x -> x));
        for (Long pid : parseIds(m.getPredecessorIds())) {
            RectificationMeasure pred = byId.get(pid);
            if (pred == null || !"verified".equals(pred.getStatus())) {
                throw new IllegalStateException("前置措施尚未验证通过：措施#" + (pred == null ? pid : pred.getSeq()));
            }
        }

        int round = (m.getVerifyRound() == null ? 0 : m.getVerifyRound()) + 1;
        m.setStatus("submitted");
        m.setVerifyRound(round);
        m.setEvidence(evidence.trim());
        m.setSubmittedBy(submittedBy.trim());
        m.setSubmittedAt(LocalDateTime.now());
        m.setVerifyComment(null);
        m.setUpdatedAt(LocalDateTime.now());
        measureRepo.save(m);
        eventRepo.save(new RectificationEvent(abnormalityId, measureId, EV_MEASURE_SUBMITTED,
                submittedBy.trim(), evidence.trim(), round));

        recomputeClosure(ab);
        return m;
    }

    @Transactional
    public RectificationMeasure verifyMeasure(Long abnormalityId, Long measureId,
                                              String verifier, boolean passed, String comment) {
        if (verifier == null || verifier.isBlank()) {
            throw new IllegalArgumentException("验证人必填");
        }
        verifier = verifier.trim();
        InspectionAbnormality ab = lockAbnormality(abnormalityId);
        RectificationMeasure m = getMeasure(abnormalityId, measureId);

        if (!"submitted".equals(m.getStatus())) {
            if ("verified".equals(m.getStatus()) && verifier.equals(m.getVerifiedBy()) && passed) {
                // 同一验证人的重复成功回调：幂等返回。
                return m;
            }
            throw new IllegalStateException("措施当前状态为[" + m.getStatus() + "]，无法验证");
        }
        if (verifier.equals(m.getSubmittedBy())) {
            throw new IllegalArgumentException("验证人不能与提交负责人为同一人");
        }

        LocalDateTime now = LocalDateTime.now();
        if (passed) {
            m.setStatus("verified");
            m.setVerifiedBy(verifier);
            m.setVerifiedAt(now);
            m.setVerifyComment(comment == null ? "" : comment);
            m.setUpdatedAt(now);
            measureRepo.save(m);
            eventRepo.save(new RectificationEvent(abnormalityId, measureId, EV_MEASURE_VERIFIED,
                    verifier, comment == null ? "" : comment, m.getVerifyRound()));
        } else {
            // 退回原措施，轮次保留，负责人修改证据后再次提交。
            m.setStatus("returned");
            m.setVerifyComment(comment == null ? "验证未通过" : comment);
            m.setUpdatedAt(now);
            measureRepo.save(m);
            eventRepo.save(new RectificationEvent(abnormalityId, measureId, EV_MEASURE_RETURNED,
                    verifier, comment == null ? "验证未通过" : comment, m.getVerifyRound()));
        }

        recomputeClosure(ab);
        return m;
    }

    // ============================ 原因分析 ============================

    @Transactional
    public InspectionAbnormality submitCauseAnalysis(Long abnormalityId, String analysis, String submitter) {
        if (analysis == null || analysis.isBlank()) {
            throw new IllegalArgumentException("原因分析内容必填");
        }
        InspectionAbnormality ab = lockAbnormality(abnormalityId);
        ab.setCauseAnalysis(analysis.trim());
        ab.setCauseSubmittedBy(submitter == null ? "" : submitter.trim());
        ab.setCauseSubmittedAt(LocalDateTime.now());
        // 重新提交原因分析后，原审批结论作废。
        ab.setCauseApproved(null);
        ab.setCauseApprovedBy(null);
        ab.setCauseApprovedAt(null);
        ab.setCauseComment(null);
        abnormalityRepo.save(ab);
        eventRepo.save(new RectificationEvent(abnormalityId, null, EV_CAUSE_SUBMITTED,
                submitter == null ? "" : submitter.trim(), analysis.trim(), null));
        recomputeClosure(ab);
        return ab;
    }

    @Transactional
    public InspectionAbnormality approveCauseAnalysis(Long abnormalityId, String approver,
                                                      boolean approved, String comment) {
        if (approver == null || approver.isBlank()) {
            throw new IllegalArgumentException("审批人必填");
        }
        InspectionAbnormality ab = lockAbnormality(abnormalityId);
        if (ab.getCauseAnalysis() == null || ab.getCauseAnalysis().isBlank()) {
            throw new IllegalStateException("尚未提交原因分析，无法审批");
        }
        approver = approver.trim();
        LocalDateTime now = LocalDateTime.now();
        ab.setCauseApproved(approved);
        ab.setCauseApprovedBy(approver);
        ab.setCauseApprovedAt(now);
        ab.setCauseComment(comment == null ? "" : comment);
        abnormalityRepo.save(ab);
        eventRepo.save(new RectificationEvent(abnormalityId, null,
                approved ? EV_CAUSE_APPROVED : EV_CAUSE_REJECTED,
                approver, comment == null ? "" : comment, null));
        recomputeClosure(ab);
        return ab;
    }

    // ============================ 风险接受 ============================

    @Transactional
    public RiskAcceptance acceptRisk(Long abnormalityId, String reason, String acceptedBy,
                                     LocalDate expireDate) {
        if (reason == null || reason.isBlank()) {
            throw new IllegalArgumentException("风险接受理由必填");
        }
        if (acceptedBy == null || acceptedBy.isBlank()) {
            throw new IllegalArgumentException("风险接受人必填");
        }
        if (expireDate == null || !expireDate.isAfter(LocalDate.now())) {
            throw new IllegalArgumentException("风险接受到期日必须晚于今天");
        }
        InspectionAbnormality ab = lockAbnormality(abnormalityId);

        List<RectificationMeasure> measures = measureRepo.findByAbnormalityIdOrderBySeqAsc(abnormalityId);
        List<Long> outstandingLongTerm = measures.stream()
                .filter(m -> "long_term".equals(m.getCategory()))
                .filter(m -> !"verified".equals(m.getStatus()))
                .map(RectificationMeasure::getId)
                .toList();
        if (outstandingLongTerm.isEmpty()) {
            throw new IllegalStateException("不存在未完成的长期整改措施，不能登记风险接受");
        }

        // 同一异常只保留一份生效中的风险接受；登记新接受时旧接受标记解除（记录保留）。
        riskRepo.findFirstByAbnormalityIdAndReleasedFalseOrderByIdDesc(abnormalityId).ifPresent(old -> {
            old.setReleased(true);
            old.setReleasedBy(acceptedBy.trim());
            old.setReleasedAt(LocalDateTime.now());
            riskRepo.save(old);
            eventRepo.save(new RectificationEvent(abnormalityId, null, EV_RISK_RELEASED,
                    acceptedBy.trim(), "被新的风险接受替代", null));
        });

        RiskAcceptance ra = new RiskAcceptance();
        ra.setAbnormalityId(abnormalityId);
        ra.setOutstandingMeasureIds(idsToString(outstandingLongTerm));
        ra.setReason(reason.trim());
        ra.setAcceptedBy(acceptedBy.trim());
        ra.setAcceptedAt(LocalDateTime.now());
        ra.setExpireDate(expireDate);
        ra.setReleased(false);
        RiskAcceptance saved = riskRepo.save(ra);
        eventRepo.save(new RectificationEvent(abnormalityId, null, EV_RISK_ACCEPTED,
                acceptedBy.trim(), reason.trim() + "；到期日：" + expireDate, null));

        // 风险接受绝不等于闭环。
        recomputeClosure(ab);
        return saved;
    }

    // ============================ 复检 ============================

    @Transactional
    public InspectionAbnormality recheck(Long abnormalityId, String result, String recheckBy) {
        InspectionAbnormality ab = lockAbnormality(abnormalityId);
        LocalDateTime now = LocalDateTime.now();
        ab.setRecheckResult(result);
        ab.setRecheckAt(now);
        ab.setRecheckBy(recheckBy == null ? "" : recheckBy.trim());
        String eventType;
        if ("passed".equals(result)) {
            eventType = EV_RECHECK_PASSED;
        } else if ("failed".equals(result)) {
            eventType = EV_RECHECK_FAILED;
            ab.setStatus("recheck_failed");
        } else {
            eventType = EV_RECHECK_OTHER;
        }
        eventRepo.save(new RectificationEvent(abnormalityId, null, eventType,
                recheckBy == null ? "" : recheckBy.trim(),
                "复检结果：" + result, null));
        abnormalityRepo.save(ab);
        recomputeClosure(ab);
        return ab;
    }

    // ============================ 工单联动 ============================

    /**
     * 工单状态变化时同步关联异常的闭环状态（既有复检记录保留，不删除）。
     * <ul>
     *   <li>done：工单完成，重新评估闭环（复检、措施等条件仍需各自满足）；</li>
     *   <li>cancelled：工单取消，闭环解除，异常置 wo_cancelled；</li>
     *   <li>open/in_progress：若此前工单已完成，则视为重新打开，
     *       旧复检作废，必须重新复检通过才能再次闭环。</li>
     * </ul>
     */
    /**
     * 工单状态变化时同步关联异常的闭环状态（既有复检记录保留，不删除）。
     *
     * @param previousStatus 工单变更前状态（来自工单实体，可能为 null）
     */
    @Transactional
    public void syncWorkOrderStatus(Long workOrderId, String previousStatus, String newStatus) {
        List<InspectionAbnormality> abs = abnormalityRepo.findByWorkOrderId(workOrderId);
        for (InspectionAbnormality detached : abs) {
            InspectionAbnormality ab = abnormalityRepo.findWithLockingById(detached.getId()).orElseThrow();
            String previous = previousStatus != null ? previousStatus : ab.getWoSyncedStatus();
            applyWorkOrderTransition(ab, previous, newStatus);
        }
    }

    private void applyWorkOrderTransition(InspectionAbnormality ab, String previous, String newStatus) {
        LocalDateTime now = LocalDateTime.now();
        String eventType = null;
        String comment = "";

        switch (newStatus) {
            case "cancelled" -> {
                ab.setWoCancelledAt(now);
                ab.setStatus("wo_cancelled");
                eventType = EV_WO_CANCELLED;
                comment = "关联工单已取消";
            }
            case "done" -> {
                if ("cancelled".equals(previous)) {
                    // 取消后又完成属于新一轮处置，此前的复检不再可信，要求重新复检。
                    ab.setWoCancelledAt(null);
                    ab.setWoReopenedAt(now);
                    eventType = EV_WO_DONE;
                    comment = "关联工单取消后重新完成，需重新复检";
                } else if (!"done".equals(previous)) {
                    ab.setWoCancelledAt(null);
                    eventType = EV_WO_DONE;
                    comment = "关联工单已完成";
                }
            }
            default -> {
                // open / in_progress
                if ("done".equals(previous)) {
                    ab.setWoReopenedAt(now);
                    eventType = EV_WO_REOPENED;
                    comment = "关联工单被重新打开，原复检结果需重新确认";
                } else if ("cancelled".equals(previous)) {
                    ab.setWoCancelledAt(null);
                    ab.setWoReopenedAt(now);
                    eventType = EV_WO_REOPENED;
                    comment = "关联工单取消后重启处置，原复检结果需重新确认";
                }
            }
        }
        ab.setWoSyncedStatus(newStatus);
        abnormalityRepo.save(ab);
        if (eventType != null) {
            eventRepo.save(new RectificationEvent(ab.getId(), null, eventType, "system", comment, null));
        }
        recomputeClosure(ab, newStatus);
    }

    // ============================ 闭环判定 ============================

    private void recomputeClosure(InspectionAbnormality ab) {
        WorkOrder wo = ab.getWorkOrderId() != null
                ? workOrderRepo.findById(ab.getWorkOrderId()).orElse(null) : null;
        String woStatus = wo != null ? wo.getStatus() : ab.getWoSyncedStatus();
        recomputeClosure(ab, woStatus);
    }

    /**
     * 唯一闭环判定入口。调用方必须持有异常行锁。
     */
    private void recomputeClosure(InspectionAbnormality ab, String workOrderStatus) {
        boolean closed = evaluateClosure(ab,
                measureRepo.findByAbnormalityIdOrderBySeqAsc(ab.getId()), workOrderStatus);
        boolean wasClosed = Boolean.TRUE.equals(ab.getClosedLoop());
        LocalDateTime now = LocalDateTime.now();

        if (closed && !wasClosed) {
            ab.setClosedLoop(true);
            ab.setStatus("resolved");
            ab.setResolvedAt(now);
            eventRepo.save(new RectificationEvent(ab.getId(), null, EV_CLOSURE_CLOSED,
                    "system", "全部闭环条件满足：复检通过、工单完成、所有措施验证通过、原因分析（如需）获批", null));
            // 闭环达成：自动解除未解除的风险接受（记录保留）。
            riskRepo.findFirstByAbnormalityIdAndReleasedFalseOrderByIdDesc(ab.getId()).ifPresent(ra -> {
                ra.setReleased(true);
                ra.setReleasedBy("system");
                ra.setReleasedAt(now);
                riskRepo.save(ra);
                eventRepo.save(new RectificationEvent(ab.getId(), null, EV_RISK_RELEASED,
                        "system", "全部整改完成并复检通过，风险接受自动解除", null));
            });
        } else if (!closed && wasClosed) {
            ab.setClosedLoop(false);
            ab.setResolvedAt(null);
            if (!"wo_cancelled".equals(ab.getStatus()) && !"recheck_failed".equals(ab.getStatus())) {
                ab.setStatus("rectifying");
            }
            eventRepo.save(new RectificationEvent(ab.getId(), null, EV_CLOSURE_OPENED,
                    "system", "闭环条件不再满足，状态回退为整改中", null));
        }
        abnormalityRepo.save(ab);
    }

    private boolean evaluateClosure(InspectionAbnormality ab, List<RectificationMeasure> measures,
                                    String workOrderStatus) {
        // 1) 复检通过且未被工单重新打开架空
        boolean freshPassed = "passed".equals(ab.getRecheckResult())
                && ab.getRecheckAt() != null
                && (ab.getWoReopenedAt() == null || ab.getRecheckAt().isAfter(ab.getWoReopenedAt()));
        if (!freshPassed) return false;

        // 2) 关联工单必须完成、未取消（无工单的人工异常不做此约束）
        if (ab.getWorkOrderId() != null) {
            if (!"done".equals(workOrderStatus)) return false;
        }

        // 3) 所有措施验证通过（至少一项）——部分完成不闭环
        if (measures.isEmpty()) return false;
        for (RectificationMeasure m : measures) {
            if (!"verified".equals(m.getStatus())) return false;
        }

        // 4) 严重异常原因分析必须获批
        if (isSevere(ab.getSeverity()) && !Boolean.TRUE.equals(ab.getCauseApproved())) return false;

        return true;
    }

    /** 闭环条件的可读化描述，供质量主管查看卡在哪一步。 */
    public List<String> closureBlockers(InspectionAbnormality ab, List<RectificationMeasure> measures,
                                        String workOrderStatus) {
        List<String> blockers = new ArrayList<>();
        boolean freshPassed = "passed".equals(ab.getRecheckResult())
                && ab.getRecheckAt() != null
                && (ab.getWoReopenedAt() == null || ab.getRecheckAt().isAfter(ab.getWoReopenedAt()));
        if (!freshPassed) blockers.add("缺少有效的复检通过记录（或工单重开后尚未复检）");
        if (ab.getWorkOrderId() != null && !"done".equals(workOrderStatus)) {
            blockers.add("关联工单未完成（当前：" + workOrderStatus + "）");
        }
        if (measures.isEmpty()) {
            blockers.add("尚未建立整改措施");
        } else {
            for (RectificationMeasure m : measures) {
                if (!"verified".equals(m.getStatus())) {
                    blockers.add("措施#" + m.getSeq() + "[" + m.getTitle() + "]状态为" + m.getStatus());
                }
            }
        }
        if (isSevere(ab.getSeverity()) && !Boolean.TRUE.equals(ab.getCauseApproved())) {
            blockers.add("严重异常原因分析尚未获批");
        }
        return blockers;
    }

    // ============================ 查询（质量主管 / 巡检员视图） ============================

    public record MeasureView(Long id, int seq, String category, String title, String description,
                              String owner, LocalDate dueDate, boolean overdue,
                              List<Long> predecessorIds, List<Integer> predecessorSeqs,
                              String status, int verifyRound, String evidence,
                              String submittedBy, LocalDateTime submittedAt,
                              String verifiedBy, LocalDateTime verifiedAt, String verifyComment) {}

    public record RectificationDetail(InspectionAbnormality abnormality, String workOrderStatus,
                                      List<MeasureView> measures, List<RectificationEvent> events,
                                      RiskAcceptance activeRiskAcceptance,
                                      List<RiskAcceptance> riskAcceptances,
                                      boolean closedLoop, List<String> closureBlockers) {}

    @Transactional(readOnly = true)
    public RectificationDetail getDetail(Long abnormalityId) {
        InspectionAbnormality ab = abnormalityRepo.findById(abnormalityId)
                .orElseThrow(() -> new IllegalArgumentException("异常不存在"));
        List<RectificationMeasure> measures = measureRepo.findByAbnormalityIdOrderBySeqAsc(abnormalityId);
        Map<Long, Integer> seqMap = measures.stream()
                .collect(Collectors.toMap(RectificationMeasure::getId, RectificationMeasure::getSeq));
        LocalDate today = LocalDate.now();
        List<MeasureView> views = new ArrayList<>();
        for (RectificationMeasure m : measures) {
            List<Long> predIds = parseIds(m.getPredecessorIds());
            List<Integer> predSeqs = predIds.stream().map(seqMap::get).filter(Objects::nonNull).toList();
            boolean overdue = m.getDueDate() != null && m.getDueDate().isBefore(today)
                    && !"verified".equals(m.getStatus());
            views.add(new MeasureView(m.getId(), m.getSeq(), m.getCategory(), m.getTitle(),
                    m.getDescription(), m.getOwner(), m.getDueDate(), overdue,
                    predIds, predSeqs, m.getStatus(),
                    m.getVerifyRound() == null ? 0 : m.getVerifyRound(),
                    m.getEvidence(), m.getSubmittedBy(), m.getSubmittedAt(),
                    m.getVerifiedBy(), m.getVerifiedAt(), m.getVerifyComment()));
        }
        WorkOrder wo = ab.getWorkOrderId() != null
                ? workOrderRepo.findById(ab.getWorkOrderId()).orElse(null) : null;
        String woStatus = wo != null ? wo.getStatus() : ab.getWoSyncedStatus();
        List<RiskAcceptance> allRisk = riskRepo.findByAbnormalityIdOrderByIdDesc(abnormalityId);
        RiskAcceptance active = allRisk.stream().filter(r -> !Boolean.TRUE.equals(r.getReleased())).findFirst().orElse(null);
        List<RectificationEvent> events = eventRepo.findByAbnormalityIdOrderByIdAsc(abnormalityId);
        List<String> blockers = Boolean.TRUE.equals(ab.getClosedLoop())
                ? List.of() : closureBlockers(ab, measures, woStatus);
        return new RectificationDetail(ab, woStatus, views, events, active, allRisk,
                Boolean.TRUE.equals(ab.getClosedLoop()), blockers);
    }

    public record OverdueMeasure(Long abnormalityId, String abnormalityTitle, String severity,
                                 Long measureId, int seq, String category, String title,
                                 String owner, LocalDate dueDate, long daysOverdue, String status) {}

    public record RiskAcceptanceRow(Long abnormalityId, String abnormalityTitle, String severity,
                                    Long riskAcceptanceId, String reason, String acceptedBy,
                                    LocalDate expireDate, long daysToExpire, boolean expired) {}

    public record AbnormalityProgress(Long abnormalityId, int measureCount, int verifiedCount,
                                      int submittedCount, int pendingCount, int overdueCount,
                                      boolean hasActiveRisk, LocalDate riskExpireDate,
                                      boolean closedLoop) {}

    /** 质量主管看板：逾期措施 + 生效中风险接受（含到期日），按异常聚合。 */
    @Transactional(readOnly = true)
    public Map<String, Object> supervisorOverview() {
        LocalDate today = LocalDate.now();
        List<InspectionAbnormality> all = abnormalityRepo.findAllByOrderByReportedAtDesc();

        List<OverdueMeasure> overdue = new ArrayList<>();
        List<RiskAcceptanceRow> risks = new ArrayList<>();
        List<Map<String, Object>> severeBlocking = new ArrayList<>();

        for (InspectionAbnormality ab : all) {
            List<RectificationMeasure> measures = measureRepo.findByAbnormalityIdOrderBySeqAsc(ab.getId());
            for (RectificationMeasure m : measures) {
                if (m.getDueDate() != null && m.getDueDate().isBefore(today)
                        && !"verified".equals(m.getStatus())) {
                    overdue.add(new OverdueMeasure(ab.getId(), ab.getTitle(), ab.getSeverity(),
                            m.getId(), m.getSeq(), m.getCategory(), m.getTitle(), m.getOwner(),
                            m.getDueDate(), java.time.temporal.ChronoUnit.DAYS.between(m.getDueDate(), today),
                            m.getStatus()));
                }
            }
            if (isSevere(ab.getSeverity()) && !Boolean.TRUE.equals(ab.getClosedLoop())) {
                boolean allVerified = !measures.isEmpty()
                        && measures.stream().allMatch(m -> "verified".equals(m.getStatus()));
                if (allVerified && !Boolean.TRUE.equals(ab.getCauseApproved())) {
                    severeBlocking.add(Map.of(
                            "abnormalityId", ab.getId(),
                            "title", ab.getTitle(),
                            "severity", ab.getSeverity(),
                            "blocker", "措施已全部验证，等待原因分析审批"));
                }
            }
            for (RiskAcceptance ra : riskRepo.findByAbnormalityIdOrderByIdDesc(ab.getId())) {
                if (Boolean.TRUE.equals(ra.getReleased())) continue;
                long days = java.time.temporal.ChronoUnit.DAYS.between(today, ra.getExpireDate());
                risks.add(new RiskAcceptanceRow(ab.getId(), ab.getTitle(), ab.getSeverity(),
                        ra.getId(), ra.getReason(), ra.getAcceptedBy(), ra.getExpireDate(),
                        days, ra.getExpireDate().isBefore(today)));
            }
        }

        risks.sort(Comparator.comparing(RiskAcceptanceRow::expireDate));
        return Map.of(
                "overdueMeasures", overdue,
                "riskAcceptances", risks,
                "severeAwaitingCauseApproval", severeBlocking,
                "abnormalityCount", all.size()
        );
    }

    /** 巡检员从原任务查看每条异常的整改进展摘要。 */
    @Transactional(readOnly = true)
    public List<AbnormalityProgress> getTaskProgress(Long taskId) {
        List<InspectionAbnormality> abs = abnormalityRepo.findByTaskIdOrderByReportedAtDesc(taskId);
        LocalDate today = LocalDate.now();
        List<AbnormalityProgress> result = new ArrayList<>();
        for (InspectionAbnormality ab : abs) {
            List<RectificationMeasure> measures = measureRepo.findByAbnormalityIdOrderBySeqAsc(ab.getId());
            int verified = 0, submitted = 0, pending = 0, over = 0;
            for (RectificationMeasure m : measures) {
                switch (m.getStatus()) {
                    case "verified" -> verified++;
                    case "submitted" -> submitted++;
                    default -> pending++;
                }
                if (m.getDueDate() != null && m.getDueDate().isBefore(today)
                        && !"verified".equals(m.getStatus())) over++;
            }
            RiskAcceptance active = riskRepo
                    .findFirstByAbnormalityIdAndReleasedFalseOrderByIdDesc(ab.getId()).orElse(null);
            result.add(new AbnormalityProgress(ab.getId(), measures.size(), verified, submitted,
                    pending, over, active != null, active != null ? active.getExpireDate() : null,
                    Boolean.TRUE.equals(ab.getClosedLoop())));
        }
        return result;
    }

    // ============================ 辅助 ============================

    private InspectionAbnormality lockAbnormality(Long id) {
        return abnormalityRepo.findWithLockingById(id)
                .orElseThrow(() -> new IllegalArgumentException("异常不存在"));
    }

    private RectificationMeasure getMeasure(Long abnormalityId, Long measureId) {
        return measureRepo.findByIdAndAbnormalityId(measureId, abnormalityId)
                .orElseThrow(() -> new IllegalArgumentException("整改措施不存在"));
    }

    static boolean isSevere(String severity) {
        return "high".equals(severity) || "urgent".equals(severity);
    }

    private String validCategory(String s) {
        if (s == null) return "temporary";
        return switch (s) {
            case "temporary", "long_term" -> s;
            default -> throw new IllegalArgumentException("措施类别只能是 temporary 或 long_term");
        };
    }

    private static List<Long> normalizeIds(List<Long> ids) {
        if (ids == null) return new ArrayList<>();
        List<Long> result = new ArrayList<>();
        for (Long id : ids) {
            if (id != null && id > 0 && !result.contains(id)) result.add(id);
        }
        return result;
    }

    private static List<Long> parseIds(String str) {
        List<Long> result = new ArrayList<>();
        if (str == null || str.isBlank()) return result;
        for (String p : str.split(",")) {
            try {
                long id = Long.parseLong(p.trim());
                if (id > 0) result.add(id);
            } catch (NumberFormatException ignored) {
            }
        }
        return result;
    }

    private static String idsToString(List<Long> ids) {
        return ids.stream().map(String::valueOf).collect(Collectors.joining(","));
    }
}
