package com.admin.equipment.repo.inspection;

import com.admin.equipment.model.inspection.RectificationEvent;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface RectificationEventRepository extends JpaRepository<RectificationEvent, Long> {

    List<RectificationEvent> findByAbnormalityIdOrderByIdAsc(Long abnormalityId);

    List<RectificationEvent> findByMeasureIdOrderByIdAsc(Long measureId);

    long countByAbnormalityIdAndType(Long abnormalityId, String type);

    long countByMeasureIdAndType(Long measureId, String type);
}
