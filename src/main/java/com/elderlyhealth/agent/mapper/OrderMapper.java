package com.elderlyhealth.agent.mapper;

import com.elderlyhealth.agent.entity.Order;
import org.apache.ibatis.annotations.Mapper;
import java.util.List;

@Mapper
public interface OrderMapper {
    void insert(Order order);
    Order findByOrderNo(String orderNo);
    List<Order> findByUserId(String userId);
    Order findByIdempotentKey(String key);
    void updateStatus(Order order);
    void update(Order order);
}
