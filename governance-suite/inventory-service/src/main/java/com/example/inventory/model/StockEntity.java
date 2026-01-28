package com.example.inventory.model;

public class StockEntity {
    private Long id;
    private String productId;
    private Integer available;
    private Integer version;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public String getProductId() { return productId; }
    public void setProductId(String productId) { this.productId = productId; }
    public Integer getAvailable() { return available; }
    public void setAvailable(Integer available) { this.available = available; }
    public Integer getVersion() { return version; }
    public void setVersion(Integer version) { this.version = version; }
}
