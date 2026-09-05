package com.elderlyhealth.agent.controller;

import com.elderlyhealth.agent.entity.Order;
import com.elderlyhealth.agent.mapper.OrderMapper;
import com.elderlyhealth.agent.service.AuditLogService;
import com.elderlyhealth.agent.service.TicketService;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;

import java.io.PrintWriter;
import java.math.BigDecimal;

@Controller
public class PayController {

    private final OrderMapper orderMapper;
    private final TicketService ticketService;
    private final AuditLogService auditLogService;

    public PayController(OrderMapper orderMapper, TicketService ticketService, AuditLogService auditLogService) {
        this.orderMapper = orderMapper;
        this.ticketService = ticketService;
        this.auditLogService = auditLogService;
    }

    @GetMapping("/pay")
    public void pay(@RequestParam String orderNo, HttpServletResponse response) throws Exception {
        Order order = orderMapper.findByOrderNo(orderNo);
        response.setContentType("text/html;charset=UTF-8");
        PrintWriter out = response.getWriter();

        if (order == null) {
            out.println("<h2>订单不存在</h2>");
            return;
        }

        out.println("<!DOCTYPE html><html><head><meta charset='UTF-8'>");
        out.println("<meta name='viewport' content='width=device-width,initial-scale=1'>");
        out.println("<title>支付宝 - 模拟支付</title>");
        out.println("<style>");
        out.println("*{margin:0;padding:0;box-sizing:border-box}");
        out.println("body{font-family:-apple-system,Helvetica,sans-serif;background:#f5f5f5}");
        out.println(".header{background:#1677ff;color:#fff;padding:20px;text-align:center;font-size:20px}");
        out.println(".card{background:#fff;margin:20px;padding:20px;border-radius:12px;box-shadow:0 2px 8px rgba(0,0,0,.1)}");
        out.println(".amount{font-size:36px;color:#333;text-align:center;padding:20px 0}");
        out.println(".order-info{color:#999;font-size:14px;text-align:center;padding:10px 0}");
        out.println(".btn{display:block;width:100%;padding:14px;background:#1677ff;color:#fff;border:none;border-radius:8px;font-size:18px;cursor:pointer;text-align:center;text-decoration:none}");
        out.println(".btn:hover{background:#4096ff}");
        out.println(".logo{text-align:center;padding:20px 0 10px}");
        out.println(".logo img{width:60px}");
        out.println("</style></head><body>");
        out.println("<div class='logo'>支付宝 | 沙箱环境</div>");
        out.println("<div class='header'>确认付款</div>");
        out.println("<div class='card'>");
        out.println("<div class='order-info'>" + order.getPlanSummary() + "</div>");
        out.println("<div class='amount'>¥" + order.getTotalAmount() + "</div>");
        out.println("<div class='order-info'>订单号: " + orderNo + "</div>");
        out.println("</div>");
        out.println("<div style='padding:0 20px'>");
        out.println("<a class='btn' href='/pay/success?orderNo=" + orderNo + "'>确认支付 ¥" + order.getTotalAmount() + "</a>");
        out.println("</div>");
        out.println("<div style='text-align:center;padding:20px;color:#999;font-size:12px'>");
        out.println("演示环境，不扣费</div>");
        out.println("</body></html>");
    }

    @GetMapping("/pay/success")
    public void paySuccess(@RequestParam String orderNo, HttpServletResponse response) throws Exception {
        Order order = orderMapper.findByOrderNo(orderNo);
        boolean isNewPay = false;
        if (order != null && "PENDING".equals(order.getStatus())) {
            order.setStatus("PAID");
            orderMapper.updateStatus(order);
            isNewPay = true;

            ticketService.issueTicket(orderNo, order.getUserId(),
                    order.getPlanSummary(),
                    order.getTravelDate() != null ? order.getTravelDate() : "待定",
                    order.getTravelers() != null ? order.getTravelers() : 1,
                    order.getTotalAmount().divide(BigDecimal.valueOf(
                            order.getTravelers() != null && order.getTravelers() > 0 ? order.getTravelers() : 1)));

            auditLogService.logSuccess(order.getUserId(), "pay_success", "order", orderNo,
                    "支付成功 ¥" + order.getTotalAmount());
        }

        response.setContentType("text/html;charset=UTF-8");
        PrintWriter out = response.getWriter();
        out.println("<!DOCTYPE html><html><head><meta charset='UTF-8'>");
        out.println("<meta name='viewport' content='width=device-width,initial-scale=1'>");
        out.println("<title>支付成功</title>");
        out.println("<style>");
        out.println("*{margin:0;padding:0;box-sizing:border-box}");
        out.println("body{font-family:-apple-system,Helvetica,sans-serif;background:#f5f5f5;text-align:center;padding-top:80px}");
        out.println(".icon{font-size:80px;color:#00b578}");
        out.println(".title{font-size:24px;color:#333;padding:20px 0 10px}");
        out.println(".info{color:#999;font-size:14px}");
        out.println("</style></head><body>");
        out.println("<div class='icon'>✅</div>");
        out.println("<div class='title'>支付成功</div>");
        out.println("<div class='info'>订单号: " + orderNo + "</div>");
        if (isNewPay && order != null) {
            out.println("<div class='info'>🎫 电子票券已生成，可在微信中回复「查票券」查看</div>");
        }
        out.println("</body></html>");
    }
}
