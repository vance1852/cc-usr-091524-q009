package com.admin.equipment.rectification;

import com.admin.equipment.model.inspection.InspectionAbnormality;
import com.admin.equipment.model.inspection.RectificationMeasure;
import com.admin.equipment.service.inspection.RectificationService;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 工单取消 / 重新打开时闭环同步规则：
 * 闭环解除但不删除既有复检；重开后旧复检作废，需要重新复检通过。
 */
class WorkOrderSyncTest extends AbstractRectificationTest {

    private void closeFully(InspectionAbnormality ab, String verifier) {
        Long aid = ab.getId();
        RectificationMeasure m1 = service.createMeasure(aid,
                new RectificationService.MeasureSpec("temporary", "临时加固", "",
                        "张维保", LocalDate.now().plusDays(3), List.of()));
        service.submitMeasure(aid, m1.getId(), "张维保", "力矩记录");
        service.verifyMeasure(aid, m1.getId(), verifier, true, "合格");
        service.recheck(aid, "passed", "王巡检");
        moveWorkOrder(ab.getWorkOrderId(), "done");
        assertTrue(reload(aid).getClosedLoop(), "前置条件：异常已闭环");
    }

    @Test
    void cancelWorkOrder_opensClosureButKeepsRecheckHistory() {
        InspectionAbnormality ab = newAbnormalityWithWorkOrder("medium");
        closeFully(ab, "李质检");
        Long aid = ab.getId();

        moveWorkOrder(ab.getWorkOrderId(), "cancelled");
        InspectionAbnormality after = reload(aid);
        assertFalse(after.getClosedLoop(), "工单取消后必须解除闭环");
        assertEquals("wo_cancelled", after.getStatus());
        assertNotNull(after.getWoCancelledAt());
        // 既有复检记录保留
        assertEquals("passed", after.getRecheckResult());
        assertNotNull(after.getRecheckAt());
        assertEquals("王巡检", after.getRecheckBy());
    }

    @Test
    void reopenWorkOrder_invalidatesOldRecheckAndRequiresFreshOne() {
        InspectionAbnormality ab = newAbnormalityWithWorkOrder("medium");
        closeFully(ab, "李质检");
        Long aid = ab.getId();

        moveWorkOrder(ab.getWorkOrderId(), "open");
        assertFalse(reload(aid).getClosedLoop(), "工单重新打开后闭环解除");
        assertNotNull(reload(aid).getWoReopenedAt());
        assertEquals("passed", reload(aid).getRecheckResult(), "旧复检记录仍保留");

        // 工单再次完成，但复检早于重开时间 -> 仍不能闭环
        moveWorkOrder(ab.getWorkOrderId(), "done");
        assertFalse(reload(aid).getClosedLoop(), "旧复检不能支撑再次闭环");

        // 重新复检通过后恢复闭环（措施仍为 verified）
        service.recheck(aid, "passed", "王巡检");
        assertTrue(reload(aid).getClosedLoop(), "重新复检通过后可再次闭环");
    }

    @Test
    void cancelThenRestartAndComplete_requiresFreshRecheck() {
        InspectionAbnormality ab = newAbnormalityWithWorkOrder("medium");
        closeFully(ab, "李质检");
        Long aid = ab.getId();

        moveWorkOrder(ab.getWorkOrderId(), "cancelled");
        moveWorkOrder(ab.getWorkOrderId(), "open");
        assertNull(reload(aid).getWoCancelledAt());
        assertNotNull(reload(aid).getWoReopenedAt());
        moveWorkOrder(ab.getWorkOrderId(), "done");
        assertFalse(reload(aid).getClosedLoop(), "取消后重启处置，旧复检不生效");

        service.recheck(aid, "passed", "王巡检");
        assertTrue(reload(aid).getClosedLoop());
    }

    @Test
    void duplicateDoneCallbacks_doNotDuplicateEvents() {
        InspectionAbnormality ab = newAbnormalityWithWorkOrder("medium");
        Long woId = ab.getWorkOrderId();
        long beforeOpen = eventRepo.countByAbnormalityIdAndType(ab.getId(), "wo_done");

        // 模拟重复回调：工单已处于 done，又收到 done
        service.syncWorkOrderStatus(woId, "open", "done");
        service.syncWorkOrderStatus(woId, "done", "done");
        service.syncWorkOrderStatus(woId, "done", "done");

        long doneEvents = eventRepo.countByAbnormalityIdAndType(ab.getId(), "wo_done");
        assertEquals(beforeOpen + 1, doneEvents, "重复回调只能产生一次 wo_done 事件");
    }

    @Test
    void duplicateMeasureSubmitCallbacks_areIdempotent() {
        InspectionAbnormality ab = newAbnormalityWithWorkOrder("medium");
        Long aid = ab.getId();
        RectificationMeasure m = service.createMeasure(aid,
                new RectificationService.MeasureSpec("temporary", "加固", "",
                        "张维保", LocalDate.now().plusDays(2), List.of()));
        service.submitMeasure(aid, m.getId(), "张维保", "同一份证据");
        // 同提交人同证据重复回调：幂等，不增加轮次、不新增事件
        service.submitMeasure(aid, m.getId(), "张维保", "同一份证据");
        RectificationMeasure reloaded = measureRepo.findById(m.getId()).orElseThrow();
        assertEquals(1, reloaded.getVerifyRound());
        assertEquals(1, eventRepo.countByMeasureIdAndType(m.getId(), "measure_submitted"));

        // 不同证据的重复提交被拒绝
        assertThrows(IllegalStateException.class,
                () -> service.submitMeasure(aid, m.getId(), "张维保", "另一份证据"));
    }
}
