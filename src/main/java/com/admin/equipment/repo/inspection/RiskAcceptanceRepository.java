package com.admin.equipment.repo.inspection;

import com.admin.equipment.model.inspection.RiskAcceptance;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

public interface RiskAcceptanceRepository extends JpaRepository<RiskAcceptance, Long> {

    List<RiskAcceptance> findByAbnormalityIdOrderByIdDesc(Long abnormalityId);

    Optional<RiskAcceptance> findFirstByAbnormalityIdAndReleasedFalseOrderByIdDesc(Long abnormalityId);

    List<RiskAcceptance> findByExpireDateBeforeAndReleasedFalse(LocalDate date);
}
