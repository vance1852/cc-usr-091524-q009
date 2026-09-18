package com.admin.equipment.rectification;

import com.admin.equipment.model.inspection.InspectionAbnormality;
import com.admin.equipment.model.inspection.RectificationEvent;
import com.admin.equipment.model.inspection.RectificationMeasure;
import com.admin.equipment.model.inspection.RiskAcceptance;
import com.admin.equipment.service.inspection.RectificationService;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 整改措施生命周期、严重异常审批闸门、风险接受与完整闭环主流程。
 */
class RectificationLifecycleTest extends AbstractRectificationTest {

    private RectificationService.MeasureSpec tempSpec(String owner, LocalDate due, List<Long> preds) {
        return new RectificationService.MeasureSpec("temporary", "加固地脚螺栓",
                "临时加固，降低振动", owner, due, preds);
    }

    private RectificationService.MeasureSpec longTermSpec(String owner, LocalDate due, List<Long> preds) {
        return new RectificationService.MeasureSpec("long_term", "更换联轴器并重新对中",
                "长期整改：消除不对中根因", owner, due, preds);
    }

    @Test
    void fullFlow_nonSevere_closesOnlyAfterRecheckAndWoDoneAndAllMeasuresVerified() {
        InspectionAbnormality ab = newAbnormalityWithWorkOrder("medium");
        Long aid = ab.getId();

        // 两项措施，第二项依赖第一项
        RectificationMeasure m1 = service.createMeasure(aid,
                tempSpec("张维保", LocalDate.now().plusDays(3), List.of()));
        assertThrows(IllegalArgumentException.class, () -> service.createMeasure(aid,
                longTermSpec("赵工程师", LocalDate.now().plusDays(14), List.of(999999L))));
        RectificationMeasure m2 = service.createMeasure(aid,
                longTermSpec("赵工程师", LocalDate.now().plusDays(14), List.of(m1.getId())));

        // 仅复检通过 + 工单完成，措施未完成 -> 不闭环
        service.recheck(aid, "passed", "王巡检");
        moveWorkOrder(ab.getWorkOrderId(), "done");
        assertFalse(reload(aid).getClosedLoop(), "措施未完成不得闭环");

        // 前置未验证，m2 不能提交
        assertThrows(IllegalStateException.class,
                () -> service.submitMeasure(aid, m2.getId(), "赵工程师", "对中报告 PDF"));

        // 提交必须带证据
        assertThrows(IllegalArgumentException.class,
                () -> service.submitMeasure(aid, m1.getId(), "张维保", "  "));

        service.submitMeasure(aid, m1.getId(), "张维保", "紧固力矩记录+照片");
        // 验证人不能是提交人
        assertThrows(IllegalArgumentException.class,
                () -> service.verifyMeasure(aid, m1.getId(), "张维保", true, "ok"));
        // 验证失败退回，轮次保留为 1
        RectificationMeasure returned = service.verifyMeasure(aid, m1.getId(), "李质检", false, "力矩不足");
        assertEquals("returned", returned.getStatus());
        assertEquals(1, returned.getVerifyRound());

        // 退回后重新提交：轮次 +1
        RectificationMeasure resubmitted = service.submitMeasure(aid, m1.getId(), "张维保", "重新紧固记录+照片");
        assertEquals("submitted", resubmitted.getStatus());
        assertEquals(2, resubmitted.getVerifyRound());

        // 此时只有 m1 待验证、m2 未完成（部分完成）-> 不闭环
        assertFalse(reload(aid).getClosedLoop());

        service.verifyMeasure(aid, m1.getId(), "李质检", true, "复验合格");
        // m1 verified 但 m2 仍 pending -> 仍不闭环
        assertFalse(reload(aid).getClosedLoop());

        service.submitMeasure(aid, m2.getId(), "赵工程师", "对中报告+振动趋势");
        service.verifyMeasure(aid, m2.getId(), "李质检", true, "振动达标");

        InspectionAbnormality closed = reload(aid);
        assertTrue(closed.getClosedLoop(), "全部条件满足后应闭环");
        assertEquals("resolved", closed.getStatus());
        assertNotNull(closed.getResolvedAt());

        // 事件只追加
        List<RectificationEvent> events = eventRepo.findByAbnormalityIdOrderByIdAsc(aid);
        assertTrue(events.stream().anyMatch(e -> "closure_closed".equals(e.getType())));
        assertTrue(events.stream().anyMatch(e -> "measure_returned".equals(e.getType()) && e.getVerifyRound() == 1));
    }

    @Test
    void severeAbnormality_cannotCloseUntilCauseAnalysisApproved() {
        InspectionAbnormality ab = newAbnormalityWithWorkOrder("urgent");
        Long aid = ab.getId();

        RectificationMeasure m1 = service.createMeasure(aid,
                tempSpec("张维保", LocalDate.now().plusDays(2), List.of()));
        service.submitMeasure(aid, m1.getId(), "张维保", "照片");
        service.verifyMeasure(aid, m1.getId(), "李质检", true, "ok");
        service.recheck(aid, "passed", "王巡检");
        moveWorkOrder(ab.getWorkOrderId(), "done");

        // 措施/复检/工单均满足，但原因分析未获批 -> 不闭环
        assertFalse(reload(aid).getClosedLoop());
        RectificationService.RectificationDetail detail = service.getDetail(aid);
        assertTrue(detail.closureBlockers().stream().anyMatch(b -> b.contains("原因分析")));

        // 未提交先审批 -> 冲突
        assertThrows(IllegalStateException.class,
                () -> service.approveCauseAnalysis(aid, "质量主管", true, ""));

        service.submitCauseAnalysis(aid, "轴承不对中导致振动随温升加剧", "赵工程师");
        // 驳回不闭环
        service.approveCauseAnalysis(aid, "质量主管", false, "缺少频谱证据");
        assertFalse(reload(aid).getClosedLoop());
        assertEquals(false, reload(aid).getCauseApproved());

        service.submitCauseAnalysis(aid, "补充频谱后确认：联轴器不对中", "赵工程师");
        service.approveCauseAnalysis(aid, "质量主管", true, "同意");
        assertTrue(reload(aid).getClosedLoop(), "严重异常原因分析获批后才可闭环");
    }

    @Test
    void riskAcceptanceNeverCloses_andHasExpiry() {
        InspectionAbnormality ab = newAbnormalityWithWorkOrder("high");
        Long aid = ab.getId();
        RectificationMeasure lt = service.createMeasure(aid,
                longTermSpec("赵工程师", LocalDate.now().plusDays(30), List.of()));
        service.recheck(aid, "passed", "王巡检");
        moveWorkOrder(ab.getWorkOrderId(), "done");

        // 到期日必须晚于今天
        assertThrows(IllegalArgumentException.class,
                () -> service.acceptRisk(aid, "带风险运行", "质量主管", LocalDate.now()));

        RiskAcceptance ra = service.acceptRisk(aid, "备件采购周期内降负荷运行",
                "质量主管", LocalDate.now().plusDays(7));
        assertNotNull(ra.getId());
        assertFalse(reload(aid).getClosedLoop(), "风险接受不能伪装成闭环");

        RectificationService.RectificationDetail detail = service.getDetail(aid);
        assertNotNull(detail.activeRiskAcceptance());
        assertEquals(ra.getId(), detail.activeRiskAcceptance().getId());

        // 长期措施完成并验证后真正闭环，风险接受自动解除（记录保留）
        service.submitMeasure(aid, lt.getId(), "赵工程师", "对中报告");
        service.verifyMeasure(aid, lt.getId(), "李质检", true, "合格");
        // 严重异常仍需原因分析
        service.submitCauseAnalysis(aid, "不对中", "赵工程师");
        service.approveCauseAnalysis(aid, "质量主管", true, "同意");
        assertTrue(reload(aid).getClosedLoop());
        assertTrue(riskRepo.findById(ra.getId()).orElseThrow().getReleased());
    }

    @Test
    void overdueAndDependencyAggregatedInViews() {
        InspectionAbnormality ab = newAbnormalityWithWorkOrder("medium");
        Long aid = ab.getId();
        RectificationMeasure m1 = service.createMeasure(aid,
                new RectificationService.MeasureSpec("temporary", "临时措施", "", "张维保",
                        LocalDate.now().minusDays(2), List.of()));
        service.createMeasure(aid,
                new RectificationService.MeasureSpec("long_term", "长期措施", "", "赵工程师",
                        LocalDate.now().plusDays(5), List.of(m1.getId())));

        RectificationService.RectificationDetail detail = service.getDetail(aid);
        assertEquals(2, detail.measures().size());
        assertTrue(detail.measures().get(0).overdue());
        assertEquals(List.of(1), detail.measures().get(1).predecessorSeqs(),
                "主管视图按异常聚合措施依赖（展示前置措施序号）");

        List<RectificationService.AbnormalityProgress> progress = service.getTaskProgress(ab.getTaskId());
        assertEquals(1, progress.size());
        assertEquals(2, progress.get(0).measureCount());
        assertEquals(1, progress.get(0).overdueCount());
        assertFalse(progress.get(0).closedLoop());
    }
}
