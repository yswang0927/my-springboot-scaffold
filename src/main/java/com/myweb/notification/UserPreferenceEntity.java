package com.myweb.notification;

import jakarta.persistence.*;

/**
 * 用户偏好实体和服务
 */
@Entity
@Table(name = "user_preferences")
public class UserPreferenceEntity {
    @Id
    private String userId;
    private boolean emailEnabled = true;
    private boolean smsEnabled = true;
    private boolean pushEnabled = true;
    private boolean inAppEnabled = true;
    private boolean marketingOptIn = false;
    private int dailyMarketingLimit = 5;
    private int minuteRateLimit = 10;

    public String getUserId() {
        return userId;
    }

    public void setUserId(String userId) {
        this.userId = userId;
    }

    public boolean isEmailEnabled() {
        return emailEnabled;
    }

    public void setEmailEnabled(boolean emailEnabled) {
        this.emailEnabled = emailEnabled;
    }

    public boolean isSmsEnabled() {
        return smsEnabled;
    }

    public void setSmsEnabled(boolean smsEnabled) {
        this.smsEnabled = smsEnabled;
    }

    public boolean isPushEnabled() {
        return pushEnabled;
    }

    public void setPushEnabled(boolean pushEnabled) {
        this.pushEnabled = pushEnabled;
    }

    public boolean isInAppEnabled() {
        return inAppEnabled;
    }

    public void setInAppEnabled(boolean inAppEnabled) {
        this.inAppEnabled = inAppEnabled;
    }

    public boolean isMarketingOptIn() {
        return marketingOptIn;
    }

    public void setMarketingOptIn(boolean marketingOptIn) {
        this.marketingOptIn = marketingOptIn;
    }

    public int getDailyMarketingLimit() {
        return dailyMarketingLimit;
    }

    public void setDailyMarketingLimit(int dailyMarketingLimit) {
        this.dailyMarketingLimit = dailyMarketingLimit;
    }

    public int getMinuteRateLimit() {
        return minuteRateLimit;
    }

    public void setMinuteRateLimit(int minuteRateLimit) {
        this.minuteRateLimit = minuteRateLimit;
    }
}