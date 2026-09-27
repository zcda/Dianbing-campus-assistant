package com.dianbing.infrastructure.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "campus.agent")
public class CampusAgentProperties {
    private boolean enabled = true;
    private int maxToolCalls = 4;
    private int maxLlmRounds = 5;
    private long timeoutMs = 15000;
    private int maxToolResultChars = 10000;
    private long plannerTimeoutMs = 5000;

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }
    public int getMaxToolCalls() { return maxToolCalls; }
    public void setMaxToolCalls(int maxToolCalls) { this.maxToolCalls = Math.max(1, Math.min(maxToolCalls, 8)); }
    public int getMaxLlmRounds() { return maxLlmRounds; }
    public void setMaxLlmRounds(int maxLlmRounds) { this.maxLlmRounds = Math.max(2, Math.min(maxLlmRounds, 8)); }
    public long getTimeoutMs() { return timeoutMs; }
    public void setTimeoutMs(long timeoutMs) { this.timeoutMs = Math.max(1000, Math.min(timeoutMs, 60000)); }
    public int getMaxToolResultChars() { return maxToolResultChars; }
    public void setMaxToolResultChars(int maxToolResultChars) {
        this.maxToolResultChars = Math.max(1000, Math.min(maxToolResultChars, 20000));
    }
    public long getPlannerTimeoutMs() { return plannerTimeoutMs; }
    public void setPlannerTimeoutMs(long plannerTimeoutMs) {
        this.plannerTimeoutMs = Math.max(500, Math.min(plannerTimeoutMs, 15000));
    }
}
