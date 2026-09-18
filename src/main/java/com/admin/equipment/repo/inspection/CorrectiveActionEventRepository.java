package com.admin.equipment.repo.inspection;

import com.admin.equipment.model.inspection.CorrectiveActionEvent;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface CorrectiveActionEventRepository extends JpaRepository<CorrectiveActionEvent, Long> {
    List<CorrectiveActionEvent> findByActionIdOrderByIdAsc(Long actionId);
    List<CorrectiveActionEvent> findByAbnormalityIdOrderByIdAsc(Long abnormalityId);
}
