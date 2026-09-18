package com.admin.equipment.repo.inspection;

import com.admin.equipment.model.inspection.AbnormalityCorrectiveAction;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface AbnormalityCorrectiveActionRepository extends JpaRepository<AbnormalityCorrectiveAction, Long> {

    List<AbnormalityCorrectiveAction> findByAbnormalityIdOrderByIdAsc(Long abnormalityId);

    /** 标量查询：不水合实体，避免在加锁前把旧状态放入一级缓存 */
    @Query("select a.abnormalityId from AbnormalityCorrectiveAction a where a.id = :id")
    java.util.Optional<Long> findAbnormalityIdById(@Param("id") Long id);

    long countByAbnormalityId(Long abnormalityId);

    long countByAbnormalityIdAndStatus(Long abnormalityId, String status);

    /** 悲观行锁：所有措施状态变更先锁异常行后调用，保证并发验证/重复回调串行化 */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select a from AbnormalityCorrectiveAction a where a.id = :id")
    Optional<AbnormalityCorrectiveAction> findByIdForUpdate(@Param("id") Long id);
}
