package com.poker.hardening;

import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.scheduling.annotation.Scheduled;

@Service
public final class CommandAdmissionService {
    private final BoundedTokenBucketRegistry<UUID> commands;
    private final BoundedTokenBucketRegistry<UUID> controls;

    public CommandAdmissionService(HardeningProperties properties) {
        commands = new BoundedTokenBucketRegistry<>(
                properties.getRegistryCap(), properties.getRegistryTtl(),
                properties.getCommandRate(), properties.getCommandBurst(), System::nanoTime);
        controls = new BoundedTokenBucketRegistry<>(
                properties.getRegistryCap(), properties.getRegistryTtl(),
                properties.getControlRate(), properties.getControlBurst(), System::nanoTime);
    }

    public TokenBucket.Admission admit(UUID userId, boolean control) {
        return (control ? controls : commands).admit(userId);
    }

    public int entries() {
        return commands.size() + controls.size();
    }

    @Scheduled(fixedDelayString = "${poker.abuse.cleanup-interval-ms:60000}")
    public void sweepExpiredEntries() {
        commands.sweep(512);
        controls.sweep(512);
    }
}
