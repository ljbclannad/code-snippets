package com.health.agent.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.health.agent.mapper.PatientProfileMapper;
import com.health.agent.mapper.WeightRecordMapper;
import com.health.agent.mapper.ChatMessageMapper;
import com.health.agent.mapper.PrescriptionRecordMapper;
import com.health.agent.model.PatientProfile;
import com.health.agent.model.WeightRecord;
import com.health.agent.model.ChatMessage;
import com.health.agent.model.PrescriptionRecord;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.util.List;

/**
 * 患者数据服务
 */
@Slf4j
@Service
public class PatientService {

    @Autowired
    private PatientProfileMapper profileMapper;

    @Autowired
    private WeightRecordMapper weightMapper;

    @Autowired
    private ChatMessageMapper chatMapper;

    @Autowired
    private PrescriptionRecordMapper prescriptionMapper;

    /**
     * 查询患者档案
     */
    public PatientProfile getProfile(String customerId) {
        LambdaQueryWrapper<PatientProfile> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(PatientProfile::getCustomerId, customerId);

        PatientProfile profile = profileMapper.selectOne(wrapper);
        if (profile == null) {
            log.warn("患者档案不存在: {}", customerId);
        }

        return profile;
    }

    /**
     * 查询体重历史记录
     */
    public List<WeightRecord> getWeightHistory(String customerId, int days) {
        LocalDate startDate = LocalDate.now().minusDays(days);

        LambdaQueryWrapper<WeightRecord> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(WeightRecord::getCustomerId, customerId)
            .ge(WeightRecord::getRecordDate, startDate)
            .orderByDesc(WeightRecord::getRecordDate);

        return weightMapper.selectList(wrapper);
    }

    /**
     * 查询聊天记录
     */
    public List<ChatMessage> getChatHistory(String customerId, int limit) {
        LambdaQueryWrapper<ChatMessage> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(ChatMessage::getCustomerId, customerId)
            .orderByDesc(ChatMessage::getCreatedAt)
            .last("LIMIT " + limit);

        return chatMapper.selectList(wrapper);
    }

    /**
     * 查询处方历史
     */
    public List<PrescriptionRecord> getPrescriptionHistory(String customerId, int limit) {
        LambdaQueryWrapper<PrescriptionRecord> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(PrescriptionRecord::getCustomerId, customerId)
            .orderByDesc(PrescriptionRecord::getCreatedAt)
            .last("LIMIT " + limit);

        return prescriptionMapper.selectList(wrapper);
    }

    /**
     * 保存患者档案
     */
    public void saveProfile(PatientProfile profile) {
        profileMapper.insert(profile);
        log.info("保存患者档案: customerId={}", profile.getCustomerId());
    }

    /**
     * 保存体重记录
     */
    public void saveWeightRecord(WeightRecord record) {
        weightMapper.insert(record);
        log.info("保存体重记录: customerId={}, weight={}", record.getCustomerId(), record.getWeight());
    }

    /**
     * 保存聊天记录
     */
    public void saveChatMessage(ChatMessage message) {
        chatMapper.insert(message);
        log.debug("保存聊天记录: customerId={}", message.getCustomerId());
    }

    /**
     * 保存处方记录
     */
    public void savePrescription(PrescriptionRecord prescription) {
        prescriptionMapper.insert(prescription);
        log.info("保存处方记录: customerId={}, medication={}",
            prescription.getCustomerId(), prescription.getMedication());
    }
}
