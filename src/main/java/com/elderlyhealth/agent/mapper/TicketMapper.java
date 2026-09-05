package com.elderlyhealth.agent.mapper;

import com.elderlyhealth.agent.entity.Ticket;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface TicketMapper {
    void insert(Ticket ticket);
}
