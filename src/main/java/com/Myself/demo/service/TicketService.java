package com.Myself.demo.service;

import com.Myself.demo.entity.Ticket;
import com.Myself.demo.mapper.TicketMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.text.SimpleDateFormat;
import java.util.Date;

@Slf4j
@Service
public class TicketService {

    private final TicketMapper ticketMapper;

    public TicketService(TicketMapper ticketMapper) {
        this.ticketMapper = ticketMapper;
    }

    public Ticket issueTicket(String orderNo, String userId, String attractionName, String visitDate,
                              int quantity, BigDecimal unitPrice) {
        Ticket ticket = new Ticket();
        ticket.setTicketNo("TK" + new SimpleDateFormat("yyyyMMddHHmmssSSS").format(new Date()));
        ticket.setOrderNo(orderNo);
        ticket.setUserId(userId);
        ticket.setAttractionName(attractionName);
        ticket.setVisitDate(visitDate);
        ticket.setQuantity(quantity);
        ticket.setUnitPrice(unitPrice);
        ticket.setTotalPrice(unitPrice.multiply(BigDecimal.valueOf(quantity)));
        ticket.setStatus("VALID");
        ticket.setQrData(ticket.getTicketNo() + "|" + attractionName + "|" + visitDate + "|" + quantity);
        ticketMapper.insert(ticket);
        log.info("票券已生成: {}, {}", ticket.getTicketNo(), attractionName);
        return ticket;
    }
}
