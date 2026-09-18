package com.admin.equipment.repo.inspection;

import com.admin.equipment.model.inspection.InspectionAbnormality;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface InspectionAbnormalityRepository extends JpaRepository<InspectionAbnormality, Long> {
    List<InspectionAbnormality> findAllByOrderByReportedAtDesc();
    List<InspectionAbnormality> findByTaskIdOrderByReportedAtDesc(Long taskId);
    List<InspectionAbnormality> findByTaskPointIdOrderByReportedAtDesc(Long taskPointId);
    List<InspectionAbnormality> findByEquipmentIdOrderByReportedAtDesc(Long equipmentId);
    List<InspectionAbnormality> findByStatusOrderByReportedAtDesc(String status);
    List<InspectionAbnormality> findByWorkOrderId(Long workOrderId);
    Optional<InspectionAbnormality> findByTaskPointIdAndRecordIdAndEquipmentId(Long taskPointId, Long recordId, Long equipmentId);

    /**
     * 悲观行锁：闭环判定、复检、措施提交/验证、工单状态同步等所有会改写 closedLoop 的操作，
     * 必须先拿到该锁，确保“部分措施完成 / 并发验证 / 重复回调”不会提前闭环。
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select a from InspectionAbnormality a where a.id = :id")
    Optional<InspectionAbnormality> findWithLockingById(@Param("id") Long id);
}
