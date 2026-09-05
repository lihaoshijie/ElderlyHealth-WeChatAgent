package com.Myself.demo.entity;

import lombok.Data;
import java.math.BigDecimal;

@Data
public class Order {
    private Long id;
    private String orderNo;
    private String idempotentKey;
    private String userId;
    private String planSummary;
    private String travelDate;
    private Integer travelers;
    private String contactName;
    private String contactPhone;
    private String remark;
    private BigDecimal totalAmount;
    private String status;
    private String createdAt;
    private String updatedAt;
}
