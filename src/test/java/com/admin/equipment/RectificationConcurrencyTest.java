package com.admin.equipment;

import com.admin.equipment.model.Equipment;
import com.admin.equipment.model.WorkOrder;
import com.admin.equipment.model.inspection.AbnormalityCorrectiveAction;
import com.admin.equipment.model.inspection.AbnormalityEvent;
import com.admin.equipment.model.inspection.CorrectiveActionEvent;
import com.admin.equipment.model.inspection.InspectionAbnormality;
import com.admin.equipment.model.inspection.InspectionTask;
import com.admin.equipment.repo.EquipmentRepository;
import com.admin.equipment.repo.WorkOrderRepository;
import com.admin.equipment.repo.inspection.AbnormalityCorrectiveActionRepository;
import com.admin.equipment.repo.inspection.CorrectiveActionEventRepository;
import com.admin.equipment.repo.inspection.AbnormalityEventRepository;
import com.admin.equipment.repo.inspection.InspectionAbnormalityRepository;
import com.admin.equipment.repo.inspection.InspectionTaskRepository;
import com.admin.equipment.service.inspection.AbnormalityRectificationService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 并发场景集成测试：并发验证、重复回调、部分措施完成均不得提前置 closedLoop。
 * 每个服务调用通过 Spring 代理在独立事务中执行，异常行上的悲观写锁负责串行化。
 */
@SpringBootTest
@ActiveProfiles("test")
class RectificationConcurrencyTest {

    @Autowired
    private AbnormalityRectificationService service;
    @Autowired
    private EquipmentRepository equipmentRepo;
    @Autowired
    private WorkOrderRepository workOrderRepo;
    @Autowired
    private InspectionTaskRepository taskRepo;
    @Autowired
    private InspectionAbnormalityRepository abnormalityRepo;
    @Autowired
    private AbnormalityCorrectiveActionRepository actionRepo;
    @Autowired
    private CorrectiveActionEventRepository actionEventRepo;
    @Autowired
    private AbnormalityEventRepository abEventRepo;

    @BeforeEach
    void clean() {
        actionEventRepo.deleteAll();
        abEventRepo.deleteAll();
        actionRepo.deleteAll();
        abnormalityRepo.deleteAll();
        workOrderRepo.deleteAll();
        taskRepo.deleteAll();
        equipmentRepo.deleteAll();
    }

    private Fixture setup(String severity, String woStatus) {
        Equipment e = new Equipment();
        e.setCode("EQ-C-" + System.nanoTime());
        e.setName("泵");
        e.setType("pump");
        e.setStatus("warning");
        equipmentRepo.save(e);

        InspectionTask t = new InspectionTask();
        t.setPlanId(1L);
        t.setTemplateId(1L);
        t.setCode("TK-C-" + System.nanoTime());
        t.setStatus("in_progress");
        taskRepo.save(t);

        WorkOrder wo = new WorkOrder();
        wo.setEquipmentId(e.getId());
        wo.setTitle("[巡检转工单]");
        wo.setType("repair");
        wo.setPriority("high");
        wo.setStatus(woStatus);
        workOrderRepo.save(wo);

        InspectionAbnormality ab = new InspectionAbnormality();
        ab.setTaskId(t.getId());
        ab.setTaskPointId(0L);
        ab.setPointId(0L);
        ab.setEquipmentId(e.getId());
        ab.setEquipmentCode(e.getCode());
        ab.setEquipmentName(e.getName());
        ab.setItemName("振动情况");
        ab.setTitle("泵振动异常");
        ab.setDescription("振动超标");
        ab.setSeverity(severity);
        ab.setStatus("wo_created");
        ab.setWorkOrderId(wo.getId());
        ab.setWorkOrderCreated(true);
        abnormalityRepo.save(ab);
        return new Fixture(e, t, wo, ab);
    }

    private record Fixture(Equipment equipment, InspectionTask task, WorkOrder wo, InspectionAbnormality ab) {}

    private AbnormalityCorrectiveAction addAction(Long abId, String category, String title, String owner) {
        return service.createAction(abId,
                new AbnormalityRectificationService.CreateActionRequest(
                        category, title, title + "内容", owner,
                        LocalDateTime.now().plusDays(3), List.of()),
                "质量主管");
    }

    private void runConcurrent(int n, Runnable task) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(n);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(n);
        AtomicInteger errors = new AtomicInteger();
        for (int i = 0; i < n; i++) {
            pool.submit(() -> {
                try {
                    start.await();
                    task.run();
                } catch (Exception ex) {
                    errors.incrementAndGet();
                } finally {
                    done.countDown();
                }
            });
        }
        start.countDown();
        assertThat(done.await(30, TimeUnit.SECONDS)).isTrue();
        pool.shutdownNow();
        assertThat(errors.get()).as("并发任务不应抛出异常").isZero();
    }

    @Test
    void concurrentVerifyAndDuplicateCallbacks_produceSingleEventAndNoEarlyClose() throws Exception {
        Fixture f = setup("medium", "open");
        AbnormalityCorrectiveAction a1 = addAction(f.ab().getId(), "temporary", "临时减振", "王巡检");
        service.submitAction(a1.getId(), "证据1", "王巡检");

        // 复检通过，但工单仍 open：即使措施并发验证完成也不得闭环
        service.recheck(f.ab().getId(), "passed", "陈质量");

        // 5 个线程同时回调“验证通过”（重复回调）
        runConcurrent(5, () -> service.verifyAction(a1.getId(), true, "并发回调", "陈质量"));

        AbnormalityCorrectiveAction reloaded = actionRepo.findById(a1.getId()).orElseThrow();
        assertThat(reloaded.getStatus()).isEqualTo("verified");
        List<CorrectiveActionEvent> events = actionEventRepo.findByActionIdOrderByIdAsc(a1.getId());
        long verifiedEvents = events.stream().filter(e -> "verified".equals(e.getType())).count();
        assertThat(verifiedEvents).as("重复验证回调只能落一条 verified 事件").isEqualTo(1);

        InspectionAbnormality ab = abnormalityRepo.findById(f.ab().getId()).orElseThrow();
        assertThat(ab.getClosedLoop()).as("工单未完成，并发验证不得提前闭环").isFalse();
        List<AbnormalityEvent> abEvents = abEventRepo.findByAbnormalityIdOrderByIdAsc(f.ab().getId());
        assertThat(abEvents.stream().filter(e -> "closed_loop".equals(e.getType())).count()).isZero();

        // 工单完成后才闭环
        service.applyWorkOrderStatusChange(f.wo().getId(), "done");
        assertThat(abnormalityRepo.findById(f.ab().getId()).orElseThrow().getClosedLoop()).isTrue();
    }

    @Test
    void concurrentVerifyOnTwoActions_partialCompletionNeverCloses() throws Exception {
        Fixture f = setup("medium", "done");
        AbnormalityCorrectiveAction a1 = addAction(f.ab().getId(), "temporary", "措施一", "王巡检");
        AbnormalityCorrectiveAction a2 = addAction(f.ab().getId(), "long_term", "措施二", "赵工程师");
        service.submitAction(a1.getId(), "证据1", "王巡检");
        service.submitAction(a2.getId(), "证据2", "赵工程师");
        service.recheck(f.ab().getId(), "passed", "陈质量");

        // 两条措施同时由验证人回调；过程中任一时刻闭环都不应被提前置位
        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger errors = new AtomicInteger();
        Runnable v1 = () -> {
            try { start.await(); service.verifyAction(a1.getId(), true, "ok", "陈质量"); }
            catch (Exception e) { errors.incrementAndGet(); }
        };
        Runnable v2 = () -> {
            try { start.await(); service.verifyAction(a2.getId(), true, "ok", "陈质量"); }
            catch (Exception e) { errors.incrementAndGet(); }
        };
        pool.submit(v1);
        pool.submit(v2);
        start.countDown();
        pool.shutdown();
        assertThat(pool.awaitTermination(30, TimeUnit.SECONDS)).isTrue();
        assertThat(errors.get()).isZero();

        // 两个措施全部 verified、复检 passed、工单 done → 恰好闭环一次
        InspectionAbnormality ab = abnormalityRepo.findById(f.ab().getId()).orElseThrow();
        assertThat(ab.getClosedLoop()).isTrue();
        long closedEvents = abEventRepo.findByAbnormalityIdOrderByIdAsc(f.ab().getId())
                .stream().filter(e -> "closed_loop".equals(e.getType())).count();
        assertThat(closedEvents).isEqualTo(1);
    }

    @Test
    void concurrentSubmitCallbacks_keepSingleRound() throws Exception {
        Fixture f = setup("low", "open");
        AbnormalityCorrectiveAction a1 = addAction(f.ab().getId(), "temporary", "紧固", "王巡检");

        runConcurrent(4, () -> service.submitAction(a1.getId(), "证据", "王巡检"));

        AbnormalityCorrectiveAction reloaded = actionRepo.findById(a1.getId()).orElseThrow();
        assertThat(reloaded.getStatus()).isEqualTo("submitted");
        assertThat(reloaded.getCurrentRound()).isEqualTo(1);
        long submittedEvents = actionEventRepo.findByActionIdOrderByIdAsc(a1.getId())
                .stream().filter(e -> "submitted".equals(e.getType())).count();
        assertThat(submittedEvents).as("重复提交回调只能产生一轮").isEqualTo(1);
        assertThat(abnormalityRepo.findById(f.ab().getId()).orElseThrow().getClosedLoop()).isFalse();
    }

    @Test
    void workOrderCancelRacingWithVerify_leavesConsistentState() throws Exception {
        Fixture f = setup("low", "open");
        AbnormalityCorrectiveAction a1 = addAction(f.ab().getId(), "temporary", "措施", "王巡检");
        service.submitAction(a1.getId(), "证据", "王巡检");
        service.recheck(f.ab().getId(), "passed", "陈质量");

        // 一个线程验证措施（同时工单恰好 open），另一个线程把工单置 done；
        // 行锁串行化后最终状态必须一致：措施 verified + 工单 done → 闭环
        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger errors = new AtomicInteger();
        pool.submit(() -> {
            try { start.await(); service.verifyAction(a1.getId(), true, "ok", "陈质量"); }
            catch (Exception e) { errors.incrementAndGet(); }
        });
        pool.submit(() -> {
            try { start.await(); service.applyWorkOrderStatusChange(f.wo().getId(), "done"); }
            catch (Exception e) { errors.incrementAndGet(); }
        });
        start.countDown();
        pool.shutdown();
        assertThat(pool.awaitTermination(30, TimeUnit.SECONDS)).isTrue();
        assertThat(errors.get()).isZero();

        assertThat(actionRepo.findById(a1.getId()).orElseThrow().getStatus()).isEqualTo("verified");
        assertThat(abnormalityRepo.findById(f.ab().getId()).orElseThrow().getClosedLoop()).isTrue();
    }
}
