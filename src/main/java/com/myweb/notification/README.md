# 支持千万用户的通知发送架构设计

## 架构概述

本系统基于事件驱动架构设计，支持高并发、高可用的通知发送系统。

## 核心组件

1. **NotificationService** - 生产者服务，负责接收通知请求并持久化到 MySQL，异步写入 Kafka
2. **NotificationConsumer** - 消费者服务，从 Kafka 消费消息，实现幂等性、限流、重试和 DLQ
3. **NotificationDispatcher** - 分发器，根据渠道路由到不同的 Provider
4. **NotificationProvider** - 渠道提供者接口（FCM、SMS、Email、In-App）
5. **UserPreferenceService** - 用户偏好服务，管理用户的接收设置
6. **NotificationScheduler** - 调度服务，处理定时通知

## 支持的渠道

- **PUSH** - FCM 推送通知
- **SMS** - Twilio 短信
- **EMAIL** - 邮件通知
- **IN_APP** - 应用内通知（WebSocket）

## 关键特性

### 1. 幂等性保证
- 使用 Redis SETNX 实现 effectively-once 语义
- 24小时过期时间防止重复处理

### 2. 限流保护
- 用户级频率限制（默认 10条/分钟）
- 营销通知每日限制（默认 5条/天）
- OTP/安全通知不受限

### 3. 重试机制
- 指数退避策略：2秒、4秒、8秒
- 最大重试次数：3次
- 失败后进入死信队列（DLQ）

### 4. 用户偏好
- 支持渠道级别的开关控制
- 支持营销通知的订阅/退订
- 可自定义限流参数

### 5. 定时通知
- 支持未来时间发送
- 每分钟检查一次到期通知
- 自动投递到 Kafka 队列

### 6. 监控指标
- 通知接收量（notification.received）
- 发送成功量（notification.sent.success）
- 发送失败量（notification.sent.failure）
- 死信队列量（notification.dlq）
- 发送延迟（notification.send.duration）

## Kafka 主题设计

- `notification.events` - 主通知队列
- `notification.retry.2s` - 2秒延迟重试
- `notification.retry.4s` - 4秒延迟重试
- `notification.retry.8s` - 8秒延迟重试
- `notification.dlq` - 死信队列

## 数据库设计

### notifications 表
```sql
CREATE TABLE notifications (
    id VARCHAR(36) PRIMARY KEY,
    userId VARCHAR(100) NOT NULL,
    channel VARCHAR(20) NOT NULL,
    type VARCHAR(20) NOT NULL,
    status VARCHAR(20) NOT NULL,
    templateId VARCHAR(100),
    idempotencyKey VARCHAR(100) NOT NULL,
    retryCount INT DEFAULT 0,
    errorMessage VARCHAR(1000),
    createdAt TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    sentAt TIMESTAMP,
    updatedAt TIMESTAMP,
    INDEX idx_user_id (userId),
    INDEX idx_created_at (createdAt),
    INDEX idx_status (status)
);
```

### user_preferences 表
```sql
CREATE TABLE user_preferences (
    userId VARCHAR(100) PRIMARY KEY,
    emailEnabled BOOLEAN DEFAULT TRUE,
    smsEnabled BOOLEAN DEFAULT TRUE,
    pushEnabled BOOLEAN DEFAULT TRUE,
    inAppEnabled BOOLEAN DEFAULT TRUE,
    marketingOptIn BOOLEAN DEFAULT FALSE,
    dailyMarketingLimit INT DEFAULT 5,
    minuteRateLimit INT DEFAULT 10
);
```

### scheduled_notifications 表

```sql
CREATE TABLE scheduled_notifications (
    id VARCHAR(36) PRIMARY KEY,
    scheduledTime TIMESTAMP NOT NULL,
    notificationJson TEXT NOT NULL,
    processed BOOLEAN DEFAULT FALSE,
    createdAt TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    INDEX idx_scheduled_time (scheduledTime),
    INDEX idx_processed (processed)
);
```