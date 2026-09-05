package com.Myself.demo.tool;

import com.Myself.demo.entity.Order;
import com.Myself.demo.mapper.OrderMapper;
import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;

@Slf4j
@Component
public class OrderTool {

    private final OrderMapper orderMapper;

    public OrderTool(OrderMapper orderMapper) {
        this.orderMapper = orderMapper;
    }

    @Tool(name = "create_order", value = "创建旅游订单")
    public String createOrder(
            @P("用户ID") String userId,
            @P("方案摘要") String planSummary,
            @P("总金额") String totalAmount,
            @P("幂等键") String idempotentKey) {
        try {
            Order existing = orderMapper.findByIdempotentKey(idempotentKey);
            if (existing != null) {
                return "订单已存在（幂等保护）\n订单号：" + existing.getOrderNo()
                        + "\n金额：¥" + existing.getTotalAmount()
                        + "\n状态：待支付"
                        + "\n\n支付链接：http://localhost:8080/pay?orderNo=" + existing.getOrderNo();
            }

            Order order = new Order();
            order.setOrderNo("ORD" + new SimpleDateFormat("yyyyMMddHHmmss").format(new Date()));
            order.setIdempotentKey(idempotentKey);
            order.setUserId(userId);
            order.setPlanSummary(planSummary);
            order.setTotalAmount(new BigDecimal(totalAmount));
            order.setStatus("PENDING");
            orderMapper.insert(order);

            log.info("订单已创建: {}, 金额={}", order.getOrderNo(), totalAmount);
            return "订单已创建！\n订单号：" + order.getOrderNo()
                    + "\n方案：" + planSummary
                    + "\n金额：¥" + totalAmount
                    + "\n状态：待支付"
                    + "\n\n点击下方链接支付（演示环境，不扣费）："
                    + "\nhttp://localhost:8080/pay?orderNo=" + order.getOrderNo();
        } catch (Exception e) {
            log.error("创建订单失败", e);
            return "创建订单失败: " + e.getMessage();
        }
    }

    @Tool(name = "query_order", value = "查询订单信息")
    public String queryOrder(
            @P("用户ID") String userId) {
        List<Order> orders = orderMapper.findByUserId(userId);
        if (orders.isEmpty()) {
            return "你还没有订单。";
        }
        StringBuilder sb = new StringBuilder("你的订单：\n");
        for (Order o : orders) {
            sb.append("\n").append(o.getOrderNo())
              .append(" ").append(o.getPlanSummary())
              .append(" ¥").append(o.getTotalAmount())
              .append(" ").append(statusText(o.getStatus()));
        }
        return sb.toString();
    }

    @Tool(name = "cancel_order", value = "取消订单并退款")
    public String cancelOrder(
            @P("订单号") String orderNo,
            @P("用户ID") String userId) {
        Order order = orderMapper.findByOrderNo(orderNo);
        if (order == null) {
            return "未找到订单: " + orderNo;
        }
        if (!order.getUserId().equals(userId)) {
            return "无权操作该订单";
        }
        order.setStatus("CANCELLED");
        orderMapper.updateStatus(order);
        log.info("订单已取消: {}, 退款金额={}", orderNo, order.getTotalAmount());
        return "订单 " + orderNo + " 已取消，退款 ¥" + order.getTotalAmount() + " 将在 1-3 个工作日原路返回。";
    }

    private String statusText(String status) {
        return switch (status) {
            case "PENDING" -> "⏳ 待支付";
            case "PAID" -> "✅ 已支付";
            case "CANCELLED" -> "❌ 已取消";
            default -> status;
        };
    }
}
