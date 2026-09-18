package com.admin.equipment.rectification;

import com.admin.equipment.model.Equipment;
import com.admin.equipment.model.WorkOrder;
import com.admin.equipment.model.inspection.InspectionAbnormality;
import com.admin.equipment.repo.EquipmentRepository;
import com.admin.equipment.repo.WorkOrderRepository;
import com.admin.equipment.repo.inspection.InspectionAbnormalityRepository;
import com.admin.equipment.repo.inspection.RectificationEventRepository;
import com.admin.equipment.repo.inspection.RectificationMeasureRepository;
import com.admin.equipment.repo.inspection.RiskAcceptanceRepository;
import com.admin.equipment.service.inspection.RectificationService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.UUID;

/**
 * 共享测试上下文（H2 内存库 + 随机端口），子类直接复用夹具构造方法。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
public abstract class AbstractRectificationTest {

    @Autowired
    protected RectificationService service;
    @Autowired
    protected InspectionAbnormalityRepository abnormalityRepo;
    @Autowired
    protected RectificationMeasureRepository measureRepo;
    @Autowired
    protected RectificationEventRepository eventRepo;
    @Autowired
    protected RiskAcceptanceRepository riskRepo;
    @Autowired
    protected WorkOrderRepository workOrderRepo;
    @Autowired
    protected EquipmentRepository equipmentRepo;

    /** 构造一条已自动建工单（open）的异常。 */
    protected InspectionAbnormality newAbnormalityWithWorkOrder(String severity) {
        Equipment eq = new Equipment();
        eq.setCode("PUMP-" + UUID.randomUUID().toString().substring(0, 8));
        eq.setName("1号循环泵");
        equipmentRepo.save(eq);

        InspectionAbnormality ab = new InspectionAbnormality();
        ab.setTaskId(900000L + System.nanoTime() % 100000L);
        ab.setTaskPointId(1L);
        ab.setPointId(1L);
        ab.setEquipmentId(eq.getId());
        ab.setEquipmentCode(eq.getCode());
        ab.setEquipmentName(eq.getName());
        ab.setItemName("振动值");
        ab.setTitle("反复出现的泵振动异常");
        ab.setDescription("振动速度超标");
        ab.setSeverity(severity);
        ab.setStatus("wo_created");
        abnormalityRepo.save(ab);

        WorkOrder wo = new WorkOrder();
        wo.setEquipmentId(eq.getId());
        wo.setTitle("[巡检转工单] 泵振动维修");
        wo.setType("repair");
        wo.setStatus("open");
        workOrderRepo.save(wo);

        ab.setWorkOrderId(wo.getId());
        ab.setWorkOrderCreated(true);
        ab.setWoSyncedStatus("open");
        return abnormalityRepo.save(ab);
    }

    protected void moveWorkOrder(Long woId, String newStatus) {
        WorkOrder wo = workOrderRepo.findById(woId).orElseThrow();
        String prev = wo.getStatus();
        wo.setStatus(newStatus);
        workOrderRepo.save(wo);
        service.syncWorkOrderStatus(woId, prev, newStatus);
    }

    protected InspectionAbnormality reload(Long abnormalityId) {
        return abnormalityRepo.findById(abnormalityId).orElseThrow();
    }
}
