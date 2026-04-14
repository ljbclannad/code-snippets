package com.medical.report.model;

import lombok.Data;
import java.util.List;

@Data
public class QuestionnaireRequest {
    private String name;
    private String age;
    private String gender;
    private String weight;
    private String height;
    private String bmi;
    private List<String> familyDiseases;
    private List<String> conditions;
    private String preferDiet;
    private String dietary_fiber_intake;
    private List<String> digestive_tract_symptoms;
    private String sportHabits;
    private String physicalActivity;
    private List<String> lifeHabits;
    private List<String> healthGoals;
    private List<String> femealConditions;
    private String antibiotic_recent;
    private String stress_high;
    private String emotional_eating;
    private MedicalRecord medicalRecord;
    private ReportFile reportFile;

    @Data
    public static class MedicalRecord {
        private Boolean isExist;
        private String idcard;
        private String realName;
    }

    @Data
    public static class ReportFile {
        private Boolean isUpload;
        private List<FileInfo> fileList;
    }

    @Data
    public static class FileInfo {
        private String name;
        private String url;
        private String fileId;
        private String fileType;
    }
}
