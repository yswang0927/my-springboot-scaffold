package com.myweb.notification;

/**
 * Provider 抽象与多渠道实现 (NotificationProvider.java)
 * 定义统一的第三方通道接口，并通过策略模式或容器注入进行路由。
 */
public interface NotificationProvider {
    // 返回通道名称：PUSH, EMAIL, SMS, IN_APP
    String getChannel();
    void send(Notification notification) throws Exception;
}