package com.poker.hardening;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("poker.abuse")
public class HardeningProperties {
    private double commandRate = 10;
    private int commandBurst = 20;
    private double controlRate = 2;
    private int controlBurst = 5;
    private double frameRate = 100;
    private int frameBurst = 200;
    private double oauthInitiationRate = 1;
    private int oauthInitiationBurst = 5;
    private int oauthInitiationRegistryCap = 10_000;
    private Duration oauthInitiationRegistryTtl = Duration.ofMinutes(10);
    private double lifecycleFrameRate = 10;
    private int lifecycleFrameBurst = 20;
    private double heartbeatFrameRate = 2;
    private int heartbeatFrameBurst = 20;
    private int maxSubscriptionsPerSocket = 8;
    private int maxSocketsPerUser = 4;
    private int maxSocketsPerHttpSession = 4;
    private int coarseViolationThreshold = 3;
    private Duration coarseViolationWindow = Duration.ofSeconds(10);
    private int registryCap = 100_000;
    private Duration registryTtl = Duration.ofMinutes(10);

    public double getCommandRate() { return commandRate; }
    public void setCommandRate(double value) { commandRate = positiveRate(value, "commandRate"); }
    public int getCommandBurst() { return commandBurst; }
    public void setCommandBurst(int value) { commandBurst = positive(value, "commandBurst"); }
    public double getControlRate() { return controlRate; }
    public void setControlRate(double value) { controlRate = positiveRate(value, "controlRate"); }
    public int getControlBurst() { return controlBurst; }
    public void setControlBurst(int value) { controlBurst = positive(value, "controlBurst"); }
    public double getFrameRate() { return frameRate; }
    public void setFrameRate(double value) { frameRate = positiveRate(value, "frameRate"); }
    public int getFrameBurst() { return frameBurst; }
    public void setFrameBurst(int value) { frameBurst = positive(value, "frameBurst"); }
    public double getOauthInitiationRate() { return oauthInitiationRate; }
    public void setOauthInitiationRate(double value) {
        oauthInitiationRate = positiveRate(value, "oauthInitiationRate");
    }
    public int getOauthInitiationBurst() { return oauthInitiationBurst; }
    public void setOauthInitiationBurst(int value) {
        oauthInitiationBurst = positive(value, "oauthInitiationBurst");
    }
    public int getOauthInitiationRegistryCap() { return oauthInitiationRegistryCap; }
    public void setOauthInitiationRegistryCap(int value) {
        oauthInitiationRegistryCap = positive(value, "oauthInitiationRegistryCap");
    }
    public Duration getOauthInitiationRegistryTtl() { return oauthInitiationRegistryTtl; }
    public void setOauthInitiationRegistryTtl(Duration value) {
        oauthInitiationRegistryTtl = positiveDuration(value, "oauthInitiationRegistryTtl");
    }
    public double getLifecycleFrameRate() { return lifecycleFrameRate; }
    public void setLifecycleFrameRate(double value) { lifecycleFrameRate = positiveRate(value, "lifecycleFrameRate"); }
    public int getLifecycleFrameBurst() { return lifecycleFrameBurst; }
    public void setLifecycleFrameBurst(int value) { lifecycleFrameBurst = positive(value, "lifecycleFrameBurst"); }
    public double getHeartbeatFrameRate() { return heartbeatFrameRate; }
    public void setHeartbeatFrameRate(double value) { heartbeatFrameRate = positiveRate(value, "heartbeatFrameRate"); }
    public int getHeartbeatFrameBurst() { return heartbeatFrameBurst; }
    public void setHeartbeatFrameBurst(int value) { heartbeatFrameBurst = positive(value, "heartbeatFrameBurst"); }
    public int getMaxSubscriptionsPerSocket() { return maxSubscriptionsPerSocket; }
    public void setMaxSubscriptionsPerSocket(int value) { maxSubscriptionsPerSocket = positive(value, "maxSubscriptionsPerSocket"); }
    public int getMaxSocketsPerUser() { return maxSocketsPerUser; }
    public void setMaxSocketsPerUser(int value) { maxSocketsPerUser = positive(value, "maxSocketsPerUser"); }
    public int getMaxSocketsPerHttpSession() { return maxSocketsPerHttpSession; }
    public void setMaxSocketsPerHttpSession(int value) { maxSocketsPerHttpSession = positive(value, "maxSocketsPerHttpSession"); }
    public int getCoarseViolationThreshold() { return coarseViolationThreshold; }
    public void setCoarseViolationThreshold(int value) {
        coarseViolationThreshold = positive(value, "coarseViolationThreshold");
    }
    public Duration getCoarseViolationWindow() { return coarseViolationWindow; }
    public void setCoarseViolationWindow(Duration value) {
        coarseViolationWindow = positiveDuration(value, "coarseViolationWindow");
    }
    public int getRegistryCap() { return registryCap; }
    public void setRegistryCap(int value) { registryCap = positive(value, "registryCap"); }
    public Duration getRegistryTtl() { return registryTtl; }
    public void setRegistryTtl(Duration value) { registryTtl = positiveDuration(value, "registryTtl"); }

    private static int positive(int value, String name) {
        if (value < 1) throw new IllegalArgumentException(name + " must be positive");
        return value;
    }

    private static double positiveRate(double value, String name) {
        if (!Double.isFinite(value) || value <= 0) {
            throw new IllegalArgumentException(name + " must be finite and positive");
        }
        return value;
    }

    private static Duration positiveDuration(Duration value, String name) {
        if (value == null || value.isZero() || value.isNegative()) {
            throw new IllegalArgumentException(name + " must be positive");
        }
        return value;
    }
}
