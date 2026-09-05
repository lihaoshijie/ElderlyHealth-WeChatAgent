package com.Myself.demo.entity;

import lombok.Data;
import java.math.BigDecimal;

@Data
public class Ticket {
    private Long id;
    private String ticketNo;
    private String orderNo;
    private String userId;
    private String attractionName;
    private String visitDate;
    private Integer quantity;
    private BigDecimal unitPrice;
    private BigDecimal totalPrice;
    private String status;
    private String qrData;
    private String issuedAt;
    private String usedAt;
}
