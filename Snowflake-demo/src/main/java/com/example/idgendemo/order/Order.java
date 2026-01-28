package com.example.idgendemo.order;

import java.math.BigDecimal;
import java.time.LocalDateTime;

public class Order {
    private long id;
    private long userId;
    private BigDecimal amount;
    private String status;
    private LocalDateTime createTime;

    public long getId() { return id; }
    public void setId(long id) { this.id = id; }

    public long getUserId() { return userId; }
    public void setUserId(long userId) { this.userId = userId; }

    public BigDecimal getAmount() { return amount; }
    public void setAmount(BigDecimal amount) { this.amount = amount; }

    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }

    public LocalDateTime getCreateTime() { return createTime; }
    public void setCreateTime(LocalDateTime createTime) { this.createTime = createTime; }
}
