package com.Myself.demo.mapper;

import com.Myself.demo.entity.Ticket;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface TicketMapper {
    void insert(Ticket ticket);
}
