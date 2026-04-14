package com.medical.report.model;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.io.Serializable;
import java.util.List;

@Data
@EqualsAndHashCode
public class QuestionnaireRequest implements Serializable {
    @NotBlank(message = "姓名不能为空")
    private String name;

    @NotBlank(message = "年龄不能为空")
    @Pattern(regexp = "^[0-9]{1,3}$", message = "年龄格式不正确")
    private String age;

    @NotBlank(message = "性别不能为空")
    @Pattern(regexp = "^(男|女)$", message = "性别只能是男或女")
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
