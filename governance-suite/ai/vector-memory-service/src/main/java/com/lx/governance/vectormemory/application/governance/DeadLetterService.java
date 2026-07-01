package com.lx.governance.vectormemory.application.governance;

import com.lx.governance.vectormemory.domain.DeadLetterMessage;
import com.lx.governance.vectormemory.repo.DeadLetterMessageMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 死信消息处理服务
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class DeadLetterService {

    private final DeadLetterMessageMapper deadLetterMessageMapper;
    private final RabbitTemplate rabbitTemplate;

    private static final int MAX_RETRY_COUNT = 3;

    /**
     * 保存死信消息
     */
    @Transactional
    public void saveDeadLetter(Message message, String failureReason) {
        DeadLetterMessage dlm = new DeadLetterMessage();
        dlm.setExchange(message.getMessageProperties().getReceivedExchange());
        dlm.setRoutingKey(message.getMessageProperties().getReceivedRoutingKey());
        dlm.setMessageBody(new String(message.getBody()));
        dlm.setFailureReason(failureReason);
        dlm.setRetryCount(0);
        dlm.setStatus("PENDING");
        dlm.setCreatedAt(LocalDateTime.now());
        dlm.setUpdatedAt(LocalDateTime.now());

        deadLetterMessageMapper.insert(dlm);
        log.warn("死信消息已保存: id={}, exchange={}, routingKey={}",
            dlm.getId(), dlm.getExchange(), dlm.getRoutingKey());
    }

    /**
     * 重试死信消息
     */
    @Transactional
    public boolean retryDeadLetter(Long id) {
        DeadLetterMessage dlm = deadLetterMessageMapper.findById(id);
        if (dlm == null) {
            log.error("死信消息不存在: id={}", id);
            return false;
        }

        if (dlm.getRetryCount() >= MAX_RETRY_COUNT) {
            dlm.setStatus("FAILED");
            dlm.setUpdatedAt(LocalDateTime.now());
            deadLetterMessageMapper.update(dlm);
            log.error("死信消息重试次数已达上限: id={}, retryCount={}", id, dlm.getRetryCount());
            return false;
        }

        try {
            // 重新投递到原始交换机
            rabbitTemplate.convertAndSend(
                dlm.getExchange(),
                dlm.getRoutingKey(),
                dlm.getMessageBody()
            );

            dlm.setRetryCount(dlm.getRetryCount() + 1);
            dlm.setStatus("RETRYING");
            dlm.setLastRetryAt(LocalDateTime.now());
            dlm.setUpdatedAt(LocalDateTime.now());
            deadLetterMessageMapper.update(dlm);

            log.info("死信消息重试成功: id={}, retryCount={}", id, dlm.getRetryCount());
            return true;
        } catch (Exception e) {
            log.error("死信消息重试失败: id={}", id, e);
            return false;
        }
    }

    /**
     * 标记死信消息为已解决
     */
    @Transactional
    public void markAsResolved(Long id) {
        DeadLetterMessage dlm = deadLetterMessageMapper.findById(id);
        if (dlm != null) {
            dlm.setStatus("RESOLVED");
            dlm.setUpdatedAt(LocalDateTime.now());
            deadLetterMessageMapper.update(dlm);
            log.info("死信消息已标记为已解决: id={}", id);
        }
    }

    /**
     * 查询待处理的死信消息
     */
    public List<DeadLetterMessage> findPendingMessages(int limit) {
        return deadLetterMessageMapper.findByStatus("PENDING", limit);
    }

    /**
     * 获取死信消息统计
     */
    public long countByStatus(String status) {
        return deadLetterMessageMapper.countByStatus(status);
    }
}
