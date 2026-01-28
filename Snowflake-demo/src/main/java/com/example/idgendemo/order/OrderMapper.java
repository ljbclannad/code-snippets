package com.example.idgendemo.order;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface OrderMapper {

    int insert(Order order);

    Order selectById(@Param("id") long id);
}
