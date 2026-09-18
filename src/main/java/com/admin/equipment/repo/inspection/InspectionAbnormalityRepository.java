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

    /** 标量查询：工单同步时先取异常ID，再逐行加锁，避免锁前水合污染一级缓存 */
    @Query("select a.id from InspectionAbnormality a where a.workOrderId = :workOrderId")
    List<Long> findIdsByWorkOrderId(@Param("workOrderId") Long workOrderId);
    Optional<InspectionAbnormality> findByTaskPointIdAndRecordIdAndEquipmentId(Long taskPointId, Long recordId, Long equipmentId);
    boolean existsByWorkOrderId(Long workOrderId);

    /** 悲观行锁：闭环重算、措施提交/验证、工单状态同步均先锁异常行 */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select a from InspectionAbnormality a where a.id = :id")
    Optional<InspectionAbnormality> findByIdForUpdate(@Param("id") Long id);
}
