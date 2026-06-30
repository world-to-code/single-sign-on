package com.example.sso.auth.factor;

import com.example.sso.authpolicy.AuthFactor;
import com.example.sso.shared.error.BadRequestException;
import com.example.sso.user.AppUser;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/** Registry that dispatches to the {@link FactorHandler} for a given {@link AuthFactor}. */
@Component
public class FactorHandlers {

    private final Map<AuthFactor, FactorHandler> byFactor;

    public FactorHandlers(List<FactorHandler> handlers) {
        this.byFactor = handlers.stream().collect(Collectors.toMap(FactorHandler::factor, Function.identity()));
    }

    public FactorHandler get(AuthFactor factor) {
        FactorHandler handler = byFactor.get(factor);
        if (handler == null) {
            throw new BadRequestException("unsupported factor: " + factor);
        }
        return handler;
    }

    public boolean isEnrolled(AuthFactor factor, AppUser user) {
        return get(factor).isEnrolled(user);
    }
}
