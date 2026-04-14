package com.medical.report.mapper;

import com.medical.report.entity.MedicalReportHistory;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.time.LocalDateTime;
import java.util.List;

@Mapper
public interface MedicalReportHistoryMapper {

    void insert(MedicalReportHistory history);

    MedicalReportHistory findById(@Param("id") Long id);

    List<MedicalReportHistory> findByPatientName(@Param("patientName") String patientName);

    List<MedicalReportHistory> findByPatientNameContaining(@Param("keyword") String keyword);

    List<MedicalReportHistory> findByCreatedAtBetween(
            @Param("startDate") LocalDateTime startDate,
            @Param("endDate") LocalDateTime endDate
    );

    List<MedicalReportHistory> findTop10ByOrderByCreatedAtDesc();

    Long countByPatientName(@Param("patientName") String patientName);

    void deleteById(@Param("id") Long id);
}
