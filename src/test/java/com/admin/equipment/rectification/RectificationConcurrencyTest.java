package com.admin.equipment.rectification;

import com.admin.equipment.model.inspection.InspectionAbnormality;
import com.admin.equipment.model.inspection.RectificationMeasure;
import com.admin.equipment.service.inspection.RectificationService;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 并发场景：所有状态流转在异常行级悲观锁内串行化，
 * 并发验证、重复回调、部分措施完成都不能提前或重复设置 closedLoop。
 */
class RectificationConcurrencyTest extends AbstractRectificationTest {

    private RectificationMeasure newSubmittedMeasure(Long aid, String owner, String title) {
        RectificationMeasure m = service.createMeasure(aid,
                new RectificationService.MeasureSpec("temporary", title, "",
                        owner, LocalDate.now().plusDays(5), List.of()));
        service.submitMeasure(aid, m.getId(), owner, "证据-" + m.getId());
        return m;
    }

    @Test
    void concurrentVerificationOfTwoMeasures_closesExactlyOnce() throws Exception {
        InspectionAbnormality ab = newAbnormalityWithWorkOrder("medium");
        Long aid = ab.getId();
        RectificationMeasure m1 = newSubmittedMeasure(aid, "张维保", "措施一");
        RectificationMeasure m2 = newSubmittedMeasure(aid, "赵工程师", "措施二");
        service.recheck(aid, "passed", "王巡检");
        moveWorkOrder(ab.getWorkOrderId(), "done");
        // 两项措施均 submitted：任一单项验证后都不应闭环
        assertFalse(reload(aid).getClosedLoop());

        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch start = new CountDownLatch(1);
        Future<?> f1 = pool.submit(() -> {
            start.await();
            service.verifyMeasure(aid, m1.getId(), "李质检", true, "ok");
            return null;
        });
        Future<?> f2 = pool.submit(() -> {
            start.await();
            service.verifyMeasure(aid, m2.getId(), "李质检", true, "ok");
            return null;
        });
        start.countDown();
        f1.get(30, TimeUnit.SECONDS);
        f2.get(30, TimeUnit.SECONDS);
        pool.shutdown();

        assertTrue(reload(aid).getClosedLoop());
        assertEquals(1, eventRepo.countByAbnormalityIdAndType(aid, "closure_closed"),
                "闭环事件只能产生一次");
    }

    @Test
    void concurrentDuplicateVerifyCallbacks_onlyOneTakesEffect() throws Exception {
        InspectionAbnormality ab = newAbnormalityWithWorkOrder("medium");
        Long aid = ab.getId();
        RectificationMeasure m1 = newSubmittedMeasure(aid, "张维保", "措施一");
        RectificationMeasure m2 = newSubmittedMeasure(aid, "赵工程师", "措施二");
        service.recheck(aid, "passed", "王巡检");
        moveWorkOrder(ab.getWorkOrderId(), "done");

        // 同一措施被两个相同的成功验证回调并发调用（重复回调）
        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch start = new CountDownLatch(1);
        Runnable verifyM1 = () -> {
            try {
                start.await();
                service.verifyMeasure(aid, m1.getId(), "李质检", true, "ok");
            } catch (Exception ignored) {
            }
        };
        Future<?> f1 = pool.submit(verifyM1);
        Future<?> f2 = pool.submit(verifyM1);
        start.countDown();
        f1.get(30, TimeUnit.SECONDS);
        f2.get(30, TimeUnit.SECONDS);
        pool.shutdown();

        assertEquals(1, eventRepo.countByMeasureIdAndType(m1.getId(), "measure_verified"),
                "重复验证回调只能产生一次验证事件");
        assertEquals("verified", measureRepo.findById(m1.getId()).orElseThrow().getStatus());
        // m2 未完成，绝不提前闭环
        assertFalse(reload(aid).getClosedLoop());
    }

    @Test
    void concurrentVerifyOnePassOneFail_doesNotClose() throws Exception {
        InspectionAbnormality ab = newAbnormalityWithWorkOrder("medium");
        Long aid = ab.getId();
        RectificationMeasure m1 = newSubmittedMeasure(aid, "张维保", "措施一");
        RectificationMeasure m2 = newSubmittedMeasure(aid, "赵工程师", "措施二");
        service.recheck(aid, "passed", "王巡检");
        moveWorkOrder(ab.getWorkOrderId(), "done");

        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch start = new CountDownLatch(1);
        Future<?> f1 = pool.submit(() -> {
            start.await();
            service.verifyMeasure(aid, m1.getId(), "李质检", true, "ok");
            return null;
        });
        Future<?> f2 = pool.submit(() -> {
            start.await();
            service.verifyMeasure(aid, m2.getId(), "李质检", false, "证据不足");
            return null;
        });
        start.countDown();
        f1.get(30, TimeUnit.SECONDS);
        f2.get(30, TimeUnit.SECONDS);
        pool.shutdown();

        assertFalse(reload(aid).getClosedLoop(), "存在被退回的措施时不得闭环");
        assertEquals("returned", measureRepo.findById(m2.getId()).orElseThrow().getStatus());
        assertEquals(1, measureRepo.findById(m2.getId()).orElseThrow().getVerifyRound(), "退回保留轮次");

        // 退回后重新提交并验证通过，闭环只产生一次
        service.submitMeasure(aid, m2.getId(), "赵工程师", "补充证据");
        service.verifyMeasure(aid, m2.getId(), "李质检", true, "复验合格");
        assertTrue(reload(aid).getClosedLoop());
        assertEquals(1, eventRepo.countByAbnormalityIdAndType(aid, "closure_closed"));
    }
}
