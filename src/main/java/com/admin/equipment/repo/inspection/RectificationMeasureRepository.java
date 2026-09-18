package com.admin.equipment.repo.inspection;

import com.admin.equipment.model.inspection.RectificationMeasure;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface RectificationMeasureRepository extends JpaRepository<RectificationMeasure, Long> {

    List<RectificationMeasure> findByAbnormalityIdOrderBySeqAsc(Long abnormalityId);

    Optional<RectificationMeasure> findByIdAndAbnormalityId(Long id, Long abnormalityId);

    long countByAbnormalityId(Long abnormalityId);
}
