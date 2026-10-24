package com.myweb.notification;

import org.springframework.data.jpa.repository.JpaRepository;

/**
 * 数据访问层
 */
public interface NotificationRepository extends JpaRepository<NotificationEntity, String> {
}