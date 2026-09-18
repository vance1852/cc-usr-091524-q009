package com.admin.equipment.repo.inspection;

import com.admin.equipment.model.inspection.AbnormalityEvent;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface AbnormalityEventRepository extends JpaRepository<AbnormalityEvent, Long> {
    List<AbnormalityEvent> findByAbnormalityIdOrderByIdAsc(Long abnormalityId);
}
