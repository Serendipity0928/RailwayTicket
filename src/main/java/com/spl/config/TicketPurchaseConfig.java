package com.spl.config;

import lombok.Data;

@Data
public class TicketPurchaseConfig {
    private int allThreadNum;
    private String queryDate;
    private String openSaleDate;  // 格式：yyyy-MM-dd HH:mm

}
