package com.admin.equipment.service.inspection;

import com.admin.equipment.model.WorkOrder;
import com.admin.equipment.model.inspection.*;
import com.admin.equipment.repo.WorkOrderRepository;
import com.admin.equipment.repo.inspection.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.*;

/**
 * 异常整改闭环领域服务。
 *
 * 闭环（closedLoop=true）的唯一判定入口为 {@link #recalcLocked}，须同时满足：
 * 1) 最近一次复检结果为 passed（复检历史只追加，永不删除）；
 * 2) 关联工单已 done（无工单视为本条件满足；工单 cancelled / 重新打开则不满足）；
 * 3) 严重异常（high/urgent）原因分析已获批准；
 * 4) 所有整改措施均 verified（cancelled 不阻塞；部分完成绝不闭环）。
 * 风险接受只是显式登记（含到期日），任何情况下都不等同于闭环。
 *
 * 所有写操作均先对异常行加 PESSIMISTIC_WRITE 行锁后进行，
 * 因此并发验证、重复回调在同一异常上被串行化，配合状态机判定实现幂等。
 */
@Service
public class AbnormalityRectificationService {

    private static final Set<String> CATEGORIES = Set.of(
            AbnormalityCorrectiveAction.CATEGORY_TEMPORARY,
            AbnormalityCorrectiveAction.CATEGORY_CAUSE,
            AbnormalityCorrectiveAction.CATEGORY_LONG_TERM);

    private final InspectionAbnormalityRepository abnormalityRepo;
    private final AbnormalityCorrectiveActionRepository actionRepo;
    private final CorrectiveActionEventRepository actionEventRepo;
    private final AbnormalityEventRepository abEventRepo;
    private final WorkOrderRepository workOrderRepo;
    private final InspectionTaskRepository taskRepo;

    public AbnormalityRectificationService(InspectionAbnormalityRepository abnormalityRepo,
                                           AbnormalityCorrectiveActionRepository actionRepo,
                                           CorrectiveActionEventRepository actionEventRepo,
                                           AbnormalityEventRepository abEventRepo,
                                           WorkOrderRepository workOrderRepo,
                                           InspectionTaskRepository taskRepo) {
        this.abnormalityRepo = abnormalityRepo;
        this.actionRepo = actionRepo;
        this.actionEventRepo = actionEventRepo;
        this.abEventRepo = abEventRepo;
        this.workOrderRepo = workOrderRepo;
        this.taskRepo = taskRepo;
    }

    // ---------------- DTO ----------------

    public record CreateActionRequest(String category, String title, String content, String ownerName,
                                      LocalDateTime dueDate, List<Long> prerequisiteIds) {}

    public record PrerequisiteView(Long id, String title, String status, boolean satisfied) {}

    public record ActionView(Long id, Long abnormalityId, String category, String title, String content,
                             String ownerName, LocalDateTime dueDate, boolean overdue,
                             String status, int currentRound,
                             LocalDateTime submittedAt, String submittedBy, String completionEvidence,
                             LocalDateTime verifiedAt, String verifiedBy, String verifyComment,
                             String cancelReason,
                             List<PrerequisiteView> prerequisites) {}

    public record AbnormalityRectificationView(Long id, Long taskId, String taskCode, Long taskPointId,
                                                Long equipmentId, String equipmentCode, String equipmentName,
                                                String itemName, String title, String description,
                                                String severity, String status, LocalDateTime reportedAt,
                                                Long workOrderId, String workOrderStatus,
                                                String recheckResult, LocalDateTime recheckAt, String recheckBy,
                                                String causeAnalysis, String causeSubmittedBy,
                                                Boolean causeApproved, String causeApprovedBy,
                                                LocalDateTime causeApprovedAt, String causeRejectReason,
                                                boolean riskAccepted, String riskAcceptedBy,
                                                LocalDateTime riskAcceptedAt, LocalDateTime riskExpiryDate,
                                                boolean riskExpired, String riskReason,
                                                boolean closedLoop, LocalDateTime closedLoopAt,
                                                LocalDateTime resolvedAt,
                                                List<ActionView> actions,
                                                List<String> pendingReasons) {}

    // ---------------- 查询 ----------------

    @Transactional(readOnly = true)
    public Optional<AbnormalityRectificationView> getRectificationView(Long abnormalityId) {
        return abnormalityRepo.findById(abnormalityId).map(ab -> buildView(ab, new ArrayList<>()));
    }

    /** 质量主管：按异常聚合的整改进度，可按状态/逾期/设备过滤 */
    @Transactional(readOnly = true)
    public List<AbnormalityRectificationView> listRectificationOverview(String statusFilter,
                                                                         boolean overdueOnly,
                                                                         Long equipmentId) {
        List<InspectionAbnormality> abs;
        if (equipmentId != null) {
            abs = abnormalityRepo.findByEquipmentIdOrderByReportedAtDesc(equipmentId);
        } else if (statusFilter != null && !statusFilter.isBlank() && !"all".equals(statusFilter)) {
            abs = abnormalityRepo.findByStatusOrderByReportedAtDesc(statusFilter);
        } else {
            abs = abnormalityRepo.findAllByOrderByReportedAtDesc();
        }
        List<AbnormalityRectificationView> result = new ArrayList<>();
        for (InspectionAbnormality ab : abs) {
            List<AbnormalityCorrectiveAction> actions = actionRepo.findByAbnormalityIdOrderByIdAsc(ab.getId());
            AbnormalityRectificationView v = buildView(ab, actions);
            if (overdueOnly && actions.stream().noneMatch(a -> isOverdue(a))) continue;
            result.add(v);
        }
        return result;
    }

    /** 巡检员：从原巡检任务追溯每条异常的当前整改进展 */
    @Transactional(readOnly = true)
    public List<AbnormalityRectificationView> listRectificationByTask(Long taskId) {
        List<AbnormalityRectificationView> result = new ArrayList<>();
        for (InspectionAbnormality ab : abnormalityRepo.findByTaskIdOrderByReportedAtDesc(taskId)) {
            result.add(buildView(ab, actionRepo.findByAbnormalityIdOrderByIdAsc(ab.getId())));
        }
        return result;
    }

    @Transactional(readOnly = true)
    public List<CorrectiveActionEvent> getActionEvents(Long actionId) {
        return actionEventRepo.findByActionIdOrderByIdAsc(actionId);
    }

    @Transactional(readOnly = true)
    public List<AbnormalityEvent> getAbnormalityEvents(Long abnormalityId) {
        return abEventRepo.findByAbnormalityIdOrderByIdAsc(abnormalityId);
    }

    // ---------------- 整改措施 ----------------

    @Transactional
    public AbnormalityCorrectiveAction createAction(Long abnormalityId, CreateActionRequest req, String actor) {
        if (req == null || req.title() == null || req.title().isBlank()) {
            throw new IllegalArgumentException("措施标题必填");
        }
        if (req.ownerName() == null || req.ownerName().isBlank()) {
            throw new IllegalArgumentException("负责人必填");
        }
        String category = req.category() == null || !CATEGORIES.contains(req.category())
                ? AbnormalityCorrectiveAction.CATEGORY_TEMPORARY : req.category();

        InspectionAbnormality ab = lockAbnormality(abnormalityId);
        if (Boolean.TRUE.equals(ab.getClosedLoop())) {
            throw new IllegalStateException("异常已闭环，不能再新增整改措施");
        }

        List<Long> prereqIds = new ArrayList<>();
        if (req.prerequisiteIds() != null) {
            List<AbnormalityCorrectiveAction> existing = actionRepo.findByAbnormalityIdOrderByIdAsc(abnormalityId);
            Map<Long, AbnormalityCorrectiveAction> map = new HashMap<>();
            for (AbnormalityCorrectiveAction a : existing) map.put(a.getId(), a);
            for (Long pid : req.prerequisiteIds()) {
                if (pid == null) continue;
                AbnormalityCorrectiveAction pre = map.get(pid);
                if (pre == null) {
                    throw new IllegalArgumentException("前置措施不存在：" + pid);
                }
                if (AbnormalityCorrectiveAction.STATUS_CANCELLED.equals(pre.getStatus())) {
                    throw new IllegalArgumentException("前置措施已取消，不能作为依赖：" + pid);
                }
                if (!prereqIds.contains(pid)) prereqIds.add(pid);
            }
        }

        AbnormalityCorrectiveAction action = new AbnormalityCorrectiveAction();
        action.setAbnormalityId(abnormalityId);
        action.setCategory(category);
        action.setTitle(req.title().trim());
        action.setContent(req.content() == null ? "" : req.content());
        action.setOwnerName(req.ownerName().trim());
        action.setDueDate(req.dueDate());
        action.setPrerequisiteIds(joinIds(prereqIds));
        action.setStatus(AbnormalityCorrectiveAction.STATUS_OPEN);
        action.setCurrentRound(0);
        AbnormalityCorrectiveAction saved = actionRepo.save(action);

        actionEventRepo.save(new CorrectiveActionEvent(saved.getId(), abnormalityId, "created", 0,
                "", AbnormalityCorrectiveAction.STATUS_OPEN, actor, "", saved.getTitle()));
        return saved;
    }

    /** 负责人提交完成证据；前置措施必须全部 verified，且提交人必须是负责人本人 */
    @Transactional
    public AbnormalityCorrectiveAction submitAction(Long actionId, String evidence, String actor) {
        if (evidence == null || evidence.isBlank()) {
            throw new IllegalArgumentException("完成证据必填");
        }
        if (actor == null || actor.isBlank()) {
            throw new IllegalArgumentException("缺少提交人信息");
        }
        AbnormalityCorrectiveAction action = lockActionViaAbnormality(actionId);
        InspectionAbnormality ab = abnormalityRepo.findById(action.getAbnormalityId()).orElseThrow();
        if (Boolean.TRUE.equals(ab.getClosedLoop())) {
            throw new IllegalStateException("异常已闭环，措施状态不能再变更");
        }
        if (AbnormalityCorrectiveAction.STATUS_VERIFIED.equals(action.getStatus())) {
            throw new IllegalStateException("措施已验证通过，无需重复提交");
        }
        if (AbnormalityCorrectiveAction.STATUS_CANCELLED.equals(action.getStatus())) {
            throw new IllegalStateException("措施已取消，不能提交");
        }
        if (AbnormalityCorrectiveAction.STATUS_SUBMITTED.equals(action.getStatus())) {
            return action; // 重复提交回调：保持待验证状态，幂等返回，不新增事件/轮次
        }
        if (!actor.trim().equals(action.getOwnerName())) {
            throw new IllegalArgumentException("只有负责人本人可以提交完成，负责人：" + action.getOwnerName());
        }
        List<AbnormalityCorrectiveAction> all = actionRepo.findByAbnormalityIdOrderByIdAsc(action.getAbnormalityId());
        Map<Long, AbnormalityCorrectiveAction> map = new HashMap<>();
        for (AbnormalityCorrectiveAction a : all) map.put(a.getId(), a);
        List<String> blocked = new ArrayList<>();
        for (Long pid : parseIds(action.getPrerequisiteIds())) {
            AbnormalityCorrectiveAction pre = map.get(pid);
            if (pre == null || !AbnormalityCorrectiveAction.STATUS_VERIFIED.equals(pre.getStatus())) {
                blocked.add(String.valueOf(pid));
            }
        }
        if (!blocked.isEmpty()) {
            throw new IllegalStateException("前置措施尚未全部验证通过，未完成：" + String.join(",", blocked));
        }

        String from = action.getStatus();
        int round = (action.getCurrentRound() == null ? 0 : action.getCurrentRound()) + 1;
        action.setStatus(AbnormalityCorrectiveAction.STATUS_SUBMITTED);
        action.setCurrentRound(round);
        action.setSubmittedAt(LocalDateTime.now());
        action.setSubmittedBy(actor.trim());
        action.setCompletionEvidence(evidence.trim());
        AbnormalityCorrectiveAction saved = actionRepo.save(action);
        actionEventRepo.save(new CorrectiveActionEvent(saved.getId(), saved.getAbnormalityId(),
                "submitted", round, from, AbnormalityCorrectiveAction.STATUS_SUBMITTED,
                actor.trim(), evidence.trim(), ""));
        recalcLocked(ab, all, null);
        abnormalityRepo.save(ab);
        return saved;
    }

    /**
     * 由非提交人验证。pass=true → verified；pass=false → 退回 open，轮次保留（currentRound 不清零）。
     * 重复回调：已 verified 后再次收到相同的通过回调，幂等返回当前状态，不重复记事件。
     */
    @Transactional
    public AbnormalityCorrectiveAction verifyAction(Long actionId, boolean pass, String comment, String actor) {
        if (actor == null || actor.isBlank()) {
            throw new IllegalArgumentException("缺少验证人信息");
        }
        AbnormalityCorrectiveAction action = lockActionViaAbnormality(actionId);
        InspectionAbnormality ab = abnormalityRepo.findById(action.getAbnormalityId()).orElseThrow();
        String actorName = actor.trim();

        if (AbnormalityCorrectiveAction.STATUS_VERIFIED.equals(action.getStatus())) {
            if (pass) {
                return action; // 重复通过回调：幂等
            }
            throw new IllegalStateException("措施已验证通过，不能再驳回");
        }
        if (AbnormalityCorrectiveAction.STATUS_CANCELLED.equals(action.getStatus())) {
            throw new IllegalStateException("措施已取消，不能验证");
        }
        if (!AbnormalityCorrectiveAction.STATUS_SUBMITTED.equals(action.getStatus())) {
            throw new IllegalStateException("措施未处于待验证状态（可能尚未提交或已被退回），当前：" + action.getStatus());
        }
        if (actorName.equals(action.getSubmittedBy())) {
            throw new IllegalArgumentException("验证人必须不同于提交完成的负责人：" + action.getSubmittedBy());
        }

        LocalDateTime now = LocalDateTime.now();
        int round = action.getCurrentRound() == null ? 0 : action.getCurrentRound();
        List<AbnormalityCorrectiveAction> all = actionRepo.findByAbnormalityIdOrderByIdAsc(action.getAbnormalityId());
        if (pass) {
            action.setStatus(AbnormalityCorrectiveAction.STATUS_VERIFIED);
            action.setVerifiedAt(now);
            action.setVerifiedBy(actorName);
            action.setVerifyComment(comment == null ? "" : comment.trim());
            action = actionRepo.save(action);
            actionEventRepo.save(new CorrectiveActionEvent(action.getId(), action.getAbnormalityId(),
                    "verified", round, AbnormalityCorrectiveAction.STATUS_SUBMITTED,
                    AbnormalityCorrectiveAction.STATUS_VERIFIED, actorName,
                    action.getCompletionEvidence(), comment == null ? "" : comment.trim()));
        } else {
            action.setStatus(AbnormalityCorrectiveAction.STATUS_OPEN);
            // 轮次保留：currentRound 不变，历史提交与驳回均以事件留存
            action.setVerifyComment(comment == null ? "" : comment.trim());
            action.setVerifiedBy(actorName);
            action.setVerifiedAt(now);
            action = actionRepo.save(action);
            actionEventRepo.save(new CorrectiveActionEvent(action.getId(), action.getAbnormalityId(),
                    "rejected", round, AbnormalityCorrectiveAction.STATUS_SUBMITTED,
                    AbnormalityCorrectiveAction.STATUS_OPEN, actorName,
                    action.getCompletionEvidence(), comment == null ? "" : comment.trim()));
        }
        recalcLocked(ab, all, null);
        abnormalityRepo.save(ab);
        return action;
    }

    @Transactional
    public AbnormalityCorrectiveAction cancelAction(Long actionId, String reason, String actor) {
        if (reason == null || reason.isBlank()) {
            throw new IllegalArgumentException("取消原因必填");
        }
        AbnormalityCorrectiveAction action = lockActionViaAbnormality(actionId);
        InspectionAbnormality ab = abnormalityRepo.findById(action.getAbnormalityId()).orElseThrow();
        if (AbnormalityCorrectiveAction.STATUS_VERIFIED.equals(action.getStatus())) {
            throw new IllegalStateException("措施已验证通过，不能取消");
        }
        if (AbnormalityCorrectiveAction.STATUS_CANCELLED.equals(action.getStatus())) {
            return action;
        }
        // 被其他未取消措施依赖时禁止取消，避免依赖链悬空
        List<AbnormalityCorrectiveAction> all = actionRepo.findByAbnormalityIdOrderByIdAsc(action.getAbnormalityId());
        for (AbnormalityCorrectiveAction other : all) {
            if (other.getId().equals(actionId)) continue;
            if (parseIds(other.getPrerequisiteIds()).contains(actionId)
                    && !AbnormalityCorrectiveAction.STATUS_CANCELLED.equals(other.getStatus())) {
                throw new IllegalStateException("该措施被措施[" + other.getTitle() + "]依赖，不能取消");
            }
        }
        String from = action.getStatus();
        int round = action.getCurrentRound() == null ? 0 : action.getCurrentRound();
        action.setStatus(AbnormalityCorrectiveAction.STATUS_CANCELLED);
        action.setCancelledAt(LocalDateTime.now());
        action.setCancelledBy(actor == null ? "" : actor.trim());
        action.setCancelReason(reason.trim());
        action = actionRepo.save(action);
        actionEventRepo.save(new CorrectiveActionEvent(action.getId(), action.getAbnormalityId(),
                "cancelled", round, from, AbnormalityCorrectiveAction.STATUS_CANCELLED,
                actor == null ? "" : actor.trim(), "", reason.trim()));
        recalcLocked(ab, all, null);
        abnormalityRepo.save(ab);
        return action;
    }

    // ---------------- 原因分析 ----------------

    @Transactional
    public InspectionAbnormality submitCauseAnalysis(Long abnormalityId, String causeAnalysis, String actor) {
        if (causeAnalysis == null || causeAnalysis.isBlank()) {
            throw new IllegalArgumentException("原因分析内容必填");
        }
        InspectionAbnormality ab = lockAbnormality(abnormalityId);
        if (Boolean.TRUE.equals(ab.getClosedLoop())) {
            throw new IllegalStateException("异常已闭环，不能再修改原因分析");
        }
        ab.setCauseAnalysis(causeAnalysis.trim());
        ab.setCauseSubmittedBy(actor == null ? "" : actor.trim());
        ab.setCauseSubmittedAt(LocalDateTime.now());
        ab.setCauseApproved(null);
        ab.setCauseRejectReason("");
        ab = abnormalityRepo.save(ab);
        abEventRepo.save(new AbnormalityEvent(abnormalityId, "cause_analysis_submitted",
                actor == null ? "" : actor.trim(), "原因分析已提交，待质量主管审批"));
        List<AbnormalityCorrectiveAction> all = actionRepo.findByAbnormalityIdOrderByIdAsc(abnormalityId);
        recalcLocked(ab, all, null);
        return abnormalityRepo.save(ab);
    }

    @Transactional
    public InspectionAbnormality approveCauseAnalysis(Long abnormalityId, boolean approved, String reason, String actor) {
        InspectionAbnormality ab = lockAbnormality(abnormalityId);
        if (ab.getCauseSubmittedAt() == null) {
            throw new IllegalStateException("尚未提交原因分析，无法审批");
        }
        String actorName = actor == null ? "" : actor.trim();
        if (actorName.isBlank()) {
            throw new IllegalArgumentException("缺少审批人信息");
        }
        if (actorName.equals(ab.getCauseSubmittedBy())) {
            throw new IllegalArgumentException("原因分析审批人必须不同于提交人：" + ab.getCauseSubmittedBy());
        }
        if (!approved && (reason == null || reason.isBlank())) {
            throw new IllegalArgumentException("驳回原因必填");
        }
        LocalDateTime now = LocalDateTime.now();
        if (approved) {
            ab.setCauseApproved(true);
            ab.setCauseApprovedBy(actorName);
            ab.setCauseApprovedAt(now);
            ab.setCauseRejectReason("");
            abEventRepo.save(new AbnormalityEvent(abnormalityId, "cause_analysis_approved", actorName, "原因分析已批准"));
        } else {
            ab.setCauseApproved(false);
            ab.setCauseApprovedBy(actorName);
            ab.setCauseApprovedAt(now);
            ab.setCauseRejectReason(reason.trim());
            AbnormalityEvent ev = new AbnormalityEvent(abnormalityId, "cause_analysis_rejected", actorName, reason.trim());
            abEventRepo.save(ev);
        }
        ab = abnormalityRepo.save(ab);
        List<AbnormalityCorrectiveAction> all = actionRepo.findByAbnormalityIdOrderByIdAsc(abnormalityId);
        recalcLocked(ab, all, null);
        return abnormalityRepo.save(ab);
    }

    // ---------------- 风险接受 ----------------

    /**
     * 长期措施无法按期完成时，只能显式登记风险接受（含原因与到期日）。
     * 风险接受不会使 closedLoop 变为 true；到期后视图中 riskExpired=true 提醒重新评估。
     */
    @Transactional
    public InspectionAbnormality acceptRisk(Long abnormalityId, String reason,
                                            LocalDateTime expiryDate, String actor) {
        if (reason == null || reason.isBlank()) {
            throw new IllegalArgumentException("风险接受原因必填");
        }
        if (expiryDate == null) {
            throw new IllegalArgumentException("风险接受到期日必填");
        }
        if (!expiryDate.isAfter(LocalDateTime.now())) {
            throw new IllegalArgumentException("风险接受到期日必须晚于当前时间");
        }
        InspectionAbnormality ab = lockAbnormality(abnormalityId);
        List<AbnormalityCorrectiveAction> all = actionRepo.findByAbnormalityIdOrderByIdAsc(abnormalityId);
        boolean hasPendingLongTerm = all.stream().anyMatch(a ->
                AbnormalityCorrectiveAction.CATEGORY_LONG_TERM.equals(a.getCategory())
                        && !AbnormalityCorrectiveAction.STATUS_VERIFIED.equals(a.getStatus())
                        && !AbnormalityCorrectiveAction.STATUS_CANCELLED.equals(a.getStatus()));
        if (!hasPendingLongTerm) {
            throw new IllegalStateException("不存在未完成的长期整改措施，无需登记风险接受");
        }
        String actorName = actor == null ? "" : actor.trim();
        ab.setRiskAccepted(true);
        ab.setRiskAcceptedBy(actorName);
        ab.setRiskAcceptedAt(LocalDateTime.now());
        ab.setRiskExpiryDate(expiryDate);
        ab.setRiskReason(reason.trim());
        ab = abnormalityRepo.save(ab);
        AbnormalityEvent ev = new AbnormalityEvent(abnormalityId, "risk_accepted", actorName,
                "风险接受：" + reason.trim() + "；注意：风险接受不构成闭环");
        ev.setRiskExpiryDate(expiryDate);
        abEventRepo.save(ev);
        recalcLocked(ab, all, null);
        return abnormalityRepo.save(ab);
    }

    // ---------------- 复检 ----------------

    /**
     * 登记复检结果（append-only：复检字段与事件均不删除、不覆盖历史条目语义——
     * 字段保留最近一次结果，全部历史在 abnormality_events 中）。
     * 复检通过只满足闭环条件之一，是否真正闭环由 recalcLocked 统一裁决。
     */
    @Transactional
    public InspectionAbnormality recheck(Long abnormalityId, String result, String actor) {
        String r;
        if ("passed".equals(result) || "failed".equals(result)) {
            r = result;
        } else {
            r = "recorded";
        }
        InspectionAbnormality ab = lockAbnormality(abnormalityId);
        LocalDateTime now = LocalDateTime.now();
        String actorName = actor == null ? "" : actor.trim();
        ab.setRecheckResult(r);
        ab.setRecheckAt(now);
        ab.setRecheckBy(actorName);
        String eventType = switch (r) {
            case "passed" -> "recheck_passed";
            case "failed" -> "recheck_failed";
            default -> "recheck_recorded";
        };
        abEventRepo.save(new AbnormalityEvent(abnormalityId, eventType, actorName,
                "复检结果：" + r + (result == null ? "" : "（原始值：" + result + "）")));
        List<AbnormalityCorrectiveAction> all = actionRepo.findByAbnormalityIdOrderByIdAsc(abnormalityId);
        recalcLocked(ab, all, null);
        return abnormalityRepo.save(ab);
    }

    // ---------------- 工单状态同步 ----------------

    /**
     * 工单状态变更的统一入口：先更新工单，再按明确规则同步关联异常闭环状态，全程同一事务。
     * 规则见 {@link #syncWorkOrderStatus}。
     */
    @Transactional
    public WorkOrder applyWorkOrderStatusChange(Long workOrderId, String newStatus) {
        WorkOrder wo = workOrderRepo.findById(workOrderId)
                .orElseThrow(() -> new IllegalArgumentException("工单不存在：" + workOrderId));
        String oldStatus = wo.getStatus();
        wo.setStatus(newStatus);
        if ("done".equals(newStatus)) {
            wo.setClosedAt(LocalDateTime.now());
        } else if ("cancelled".equals(newStatus)) {
            wo.setClosedAt(LocalDateTime.now());
        } else {
            wo.setClosedAt(null);
        }
        workOrderRepo.save(wo);
        if (!java.util.Objects.equals(oldStatus, newStatus)) {
            syncWorkOrderStatus(workOrderId, oldStatus, newStatus);
        }
        return wo;
    }

    /**
     * 工单状态变化时同步异常闭环状态，明确规则：
     * done：满足其他闭环条件时方可闭环，否则保持打开（绝不因工单完成直接闭环）；
     * cancelled / 重新打开（done|cancelled → open|in_progress）：
     * 已闭环的异常重新打开（closedLoop=false），但既有复检结果与全部事件保留。
     * 重复回调（状态未变化）只做一次幂等重算，不重复记录事件。
     */
    @Transactional
    public void syncWorkOrderStatus(Long workOrderId, String oldStatus, String newStatus) {
        for (Long abId : abnormalityRepo.findIdsByWorkOrderId(workOrderId)) {
            // 首次加载即加锁，保证锁后读到最新闭环状态（见 lockActionViaAbnormality 的说明）
            InspectionAbnormality ab = abnormalityRepo.findByIdForUpdate(abId).orElseThrow();
            boolean changed = oldStatus != null && !oldStatus.equals(newStatus);
            if (changed) {
                AbnormalityEvent ev = new AbnormalityEvent(ab.getId(), "work_order_synced",
                        "system", "工单状态由 " + oldStatus + " 变为 " + newStatus);
                ev.setWorkOrderId(workOrderId);
                ev.setWorkOrderStatus(newStatus == null ? "" : newStatus);
                abEventRepo.save(ev);
            }
            List<AbnormalityCorrectiveAction> all = actionRepo.findByAbnormalityIdOrderByIdAsc(ab.getId());
            recalcLocked(ab, all, newStatus);
            abnormalityRepo.save(ab);
        }
    }

    // ---------------- 闭环裁决（唯一入口）----------------

    /**
     * 在已持有异常行锁的前提下重算闭环状态。
     * @param woStatusOverride 工单同步场景下传入工单最新状态；其余场景传 null 由库中读取
     */
    private void recalcLocked(InspectionAbnormality ab, List<AbnormalityCorrectiveAction> actions,
                              String woStatusOverride) {
        List<String> pending = new ArrayList<>();
        boolean wasClosed = Boolean.TRUE.equals(ab.getClosedLoop());

        if (!"passed".equals(ab.getRecheckResult())) {
            pending.add("复检尚未通过");
        }

        boolean woOk = true;
        if (ab.getWorkOrderId() != null && Boolean.TRUE.equals(ab.getWorkOrderCreated())) {
            String woStatus = woStatusOverride;
            WorkOrder wo = null;
            if (woStatus == null) {
                wo = workOrderRepo.findById(ab.getWorkOrderId()).orElse(null);
                woStatus = wo != null ? wo.getStatus() : null;
            }
            if (!"done".equals(woStatus)) {
                woOk = false;
                pending.add("关联工单未完成（当前状态：" + (woStatus == null ? "不存在" : woStatus) + "）");
            }
        }

        if (ab.isCriticalSeverity() && !Boolean.TRUE.equals(ab.getCauseApproved())) {
            pending.add("严重异常的原因分析尚未获批");
        }

        for (AbnormalityCorrectiveAction a : actions) {
            String st = a.getStatus();
            if (AbnormalityCorrectiveAction.STATUS_CANCELLED.equals(st)) continue;
            if (!AbnormalityCorrectiveAction.STATUS_VERIFIED.equals(st)) {
                pending.add("整改措施未完成验证：" + a.getTitle() + "（" + st + "）");
            }
        }

        boolean closed = pending.isEmpty();
        LocalDateTime now = LocalDateTime.now();
        if (closed && !wasClosed) {
            ab.setClosedLoop(true);
            ab.setClosedLoopAt(now);
            ab.setResolvedAt(now);
            ab.setStatus("resolved");
            AbnormalityEvent ev = new AbnormalityEvent(ab.getId(), "closed_loop",
                    "system", "全部闭环条件满足，异常闭环");
            ev.setClosedLoop(true);
            abEventRepo.save(ev);
        } else if (!closed && wasClosed) {
            // 重新打开：工单取消/重开、复检变化等。复检数据与历史事件一律保留。
            ab.setClosedLoop(false);
            ab.setClosedLoopAt(null);
            ab.setResolvedAt(null);
            abEventRepo.save(new AbnormalityEvent(ab.getId(), "closed_loop_reopened",
                    "system", "闭环条件不再满足，异常重新打开：" + String.join("；", pending)));
        }
        if (!closed) {
            ab.setStatus(deriveOpenStatus(ab));
        }
    }

    private String deriveOpenStatus(InspectionAbnormality ab) {
        if (Boolean.TRUE.equals(ab.getRiskAccepted())) return "risk_accepted";
        if ("failed".equals(ab.getRecheckResult())) return "recheck_failed";
        if ("passed".equals(ab.getRecheckResult())) {
            return "recheck_pending";
        }
        if (Boolean.TRUE.equals(ab.getWorkOrderCreated())) return "wo_created";
        return ab.getStatus() == null ? "reported" : ab.getStatus();
    }

    // ---------------- 视图组装 ----------------

    private AbnormalityRectificationView buildView(InspectionAbnormality ab,
                                                    List<AbnormalityCorrectiveAction> actions) {
        if (actions == null || (actions.isEmpty() && ab.getId() != null)) {
            actions = actionRepo.findByAbnormalityIdOrderByIdAsc(ab.getId());
        }
        Map<Long, AbnormalityCorrectiveAction> map = new HashMap<>();
        for (AbnormalityCorrectiveAction a : actions) map.put(a.getId(), a);
        LocalDateTime now = LocalDateTime.now();

        List<ActionView> actionViews = new ArrayList<>();
        for (AbnormalityCorrectiveAction a : actions) {
            boolean cancelled = AbnormalityCorrectiveAction.STATUS_CANCELLED.equals(a.getStatus());
            boolean verified = AbnormalityCorrectiveAction.STATUS_VERIFIED.equals(a.getStatus());
            boolean overdue = isOverdue(a, now);
            List<PrerequisiteView> pre = new ArrayList<>();
            for (Long pid : parseIds(a.getPrerequisiteIds())) {
                AbnormalityCorrectiveAction p = map.get(pid);
                pre.add(new PrerequisiteView(pid,
                        p != null ? p.getTitle() : "(已删除)",
                        p != null ? p.getStatus() : "missing",
                        p != null && AbnormalityCorrectiveAction.STATUS_VERIFIED.equals(p.getStatus())));
            }
            actionViews.add(new ActionView(a.getId(), a.getAbnormalityId(), a.getCategory(),
                    a.getTitle(), a.getContent(), a.getOwnerName(), a.getDueDate(), overdue,
                    a.getStatus(), a.getCurrentRound() == null ? 0 : a.getCurrentRound(),
                    a.getSubmittedAt(), a.getSubmittedBy(), a.getCompletionEvidence(),
                    a.getVerifiedAt(), a.getVerifiedBy(), a.getVerifyComment(),
                    a.getCancelReason(), pre));
        }

        String woStatus = "";
        if (ab.getWorkOrderId() != null) {
            woStatus = workOrderRepo.findById(ab.getWorkOrderId())
                    .map(WorkOrder::getStatus).orElse("missing");
        }
        String taskCode = taskRepo.findById(ab.getTaskId() == null ? -1L : ab.getTaskId())
                .map(InspectionTask::getCode).orElse("");

        List<String> pending = new ArrayList<>();
        if (!"passed".equals(ab.getRecheckResult())) pending.add("复检尚未通过");
        if (ab.getWorkOrderId() != null && Boolean.TRUE.equals(ab.getWorkOrderCreated())
                && !"done".equals(woStatus)) {
            pending.add("关联工单未完成（当前状态：" + ("missing".equals(woStatus) ? "不存在" : woStatus) + "）");
        }
        if (ab.isCriticalSeverity() && !Boolean.TRUE.equals(ab.getCauseApproved())) {
            pending.add("严重异常的原因分析尚未获批");
        }
        for (AbnormalityCorrectiveAction a : actions) {
            if (AbnormalityCorrectiveAction.STATUS_CANCELLED.equals(a.getStatus())) continue;
            if (!AbnormalityCorrectiveAction.STATUS_VERIFIED.equals(a.getStatus())) {
                pending.add("整改措施未完成验证：" + a.getTitle() + "（" + a.getStatus() + "）");
            }
        }

        boolean riskExpired = Boolean.TRUE.equals(ab.getRiskAccepted())
                && ab.getRiskExpiryDate() != null && ab.getRiskExpiryDate().isBefore(now);

        return new AbnormalityRectificationView(ab.getId(), ab.getTaskId(), taskCode, ab.getTaskPointId(),
                ab.getEquipmentId(), ab.getEquipmentCode(), ab.getEquipmentName(),
                ab.getItemName(), ab.getTitle(), ab.getDescription(),
                ab.getSeverity(), ab.getStatus(), ab.getReportedAt(),
                ab.getWorkOrderId(), woStatus,
                ab.getRecheckResult(), ab.getRecheckAt(), ab.getRecheckBy(),
                ab.getCauseAnalysis(), ab.getCauseSubmittedBy(),
                ab.getCauseApproved(), ab.getCauseApprovedBy(), ab.getCauseApprovedAt(),
                ab.getCauseRejectReason(),
                Boolean.TRUE.equals(ab.getRiskAccepted()), ab.getRiskAcceptedBy(),
                ab.getRiskAcceptedAt(), ab.getRiskExpiryDate(), riskExpired, ab.getRiskReason(),
                Boolean.TRUE.equals(ab.getClosedLoop()), ab.getClosedLoopAt(), ab.getResolvedAt(),
                actionViews, pending);
    }

    // ---------------- 基础设施 ----------------

    private InspectionAbnormality lockAbnormality(Long abnormalityId) {
        return abnormalityRepo.findByIdForUpdate(abnormalityId)
                .orElseThrow(() -> new IllegalArgumentException("异常不存在：" + abnormalityId));
    }

    /**
     * 措施写操作的加锁顺序（固定：异常行 → 措施行，避免死锁）。
     * 先用标量查询拿到 abnormalityId（不水合实体），再对异常行加悲观写锁，
     * 措施实体的首次加载也必须是加锁读取——否则一级缓存中的锁前旧状态会让
     * 串行化后的线程仍读到 open，造成重复回调丢失更新。
     */
    private AbnormalityCorrectiveAction lockActionViaAbnormality(Long actionId) {
        Long abId = actionRepo.findAbnormalityIdById(actionId)
                .orElseThrow(() -> new IllegalArgumentException("整改措施不存在：" + actionId));
        abnormalityRepo.findByIdForUpdate(abId).orElseThrow();
        return actionRepo.findByIdForUpdate(actionId).orElseThrow();
    }

    static boolean isOverdue(AbnormalityCorrectiveAction a) {
        return isOverdue(a, LocalDateTime.now());
    }

    static boolean isOverdue(AbnormalityCorrectiveAction a, LocalDateTime now) {
        if (a.getDueDate() == null) return false;
        if (AbnormalityCorrectiveAction.STATUS_VERIFIED.equals(a.getStatus())
                || AbnormalityCorrectiveAction.STATUS_CANCELLED.equals(a.getStatus())) {
            return false;
        }
        return a.getDueDate().isBefore(now);
    }

    private static List<Long> parseIds(String str) {
        List<Long> result = new ArrayList<>();
        if (str == null || str.isBlank()) return result;
        for (String p : str.split(",")) {
            try {
                long v = Long.parseLong(p.trim());
                if (v > 0 && !result.contains(v)) result.add(v);
            } catch (NumberFormatException ignored) {
            }
        }
        return result;
    }

    private static String joinIds(List<Long> ids) {
        StringBuilder sb = new StringBuilder();
        for (Long id : ids) {
            if (sb.length() > 0) sb.append(",");
            sb.append(id);
        }
        return sb.toString();
    }
}
