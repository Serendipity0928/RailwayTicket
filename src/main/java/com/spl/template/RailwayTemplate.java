package com.spl.template;

public interface RailwayTemplate {

    // 目前先仅支持济南至北京，日期自定
    String LEFT_TICKET_URL_TEMPLATE = "https://kyfw.12306.cn/otn/leftTicket/init?linktypeid=dc&fs=%E6%B5%8E%E5%8D%97,JNK&ts=%E5%8C%97%E4%BA%AC,BJP&date={$DATE}&flag=N,N,Y";

}
