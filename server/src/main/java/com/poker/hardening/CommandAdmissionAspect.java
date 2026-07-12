package com.poker.hardening;

import com.poker.auth.CurrentUserPrincipal;
import com.poker.ws.protocol.CommandAck;
import com.poker.ws.protocol.CommandEnvelope;
import java.security.Principal;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Component;

@Aspect
@Component
public final class CommandAdmissionAspect {
    private final CommandAdmissionService admission;
    private final AbuseTelemetry telemetry;

    public CommandAdmissionAspect(
            CommandAdmissionService admission,
            AbuseTelemetry telemetry) {
        this.admission = admission;
        this.telemetry = telemetry;
    }

    @Around("@annotation(command)")
    public Object around(ProceedingJoinPoint point, AdmittedCommand command) throws Throwable {
        CommandEnvelope<?> envelope = null;
        Principal principal = null;
        for (Object argument : point.getArgs()) {
            if (argument instanceof CommandEnvelope<?> candidate) envelope = candidate;
            if (argument instanceof Principal candidate) principal = candidate;
        }
        CurrentUserPrincipal user = currentUser(principal);
        // Authentication and malformed method signatures are handled by the
        // existing controller/security boundary; never fabricate correlation.
        if (user == null || envelope == null) return point.proceed();

        TokenBucket.Admission result = admission.admit(user.userId(), command.control());
        telemetry.command(command.value(), user.userId(), result.accepted(), result.retryAfterMs());
        if (result.accepted()) return point.proceed();
        return CommandAck.rateLimited(
                envelope.requestId(), command.value(), result.retryAfterMs());
    }

    private static CurrentUserPrincipal currentUser(Principal principal) {
        if (principal instanceof Authentication authentication
                && authentication.getPrincipal() instanceof CurrentUserPrincipal current) {
            return current;
        }
        return null;
    }
}
