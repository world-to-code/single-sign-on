package com.example.sso.branding.internal.application;

import com.example.sso.branding.AuthScreen;
import com.example.sso.branding.ScreenCopy;
import com.example.sso.branding.internal.domain.AuthScreenCopy;
import com.example.sso.branding.internal.domain.AuthScreenCopyRepository;
import com.example.sso.shared.error.BadRequestException;
import java.util.EnumMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

/**
 * Per-tenant wording for the sign-in screens: {@link #resolve} answers what to RENDER for an org (own over
 * platform, per screen and per field); {@code get}/{@code update}/{@code delete} are the admin surface.
 *
 * <p>Separate from {@link BrandingService} because it is a different question with a different table and a
 * different shape — one row per screen rather than one per tier — and folding it in would have made that class
 * the place every branding change lands. The tier decision they share lives in {@link ActingTier}, so the
 * fail-closed guard is written once.
 *
 * <p>Values are length-capped and the help URL is https; nothing here is ever parsed as markup, which is why
 * there is no escaping step to forget.
 */
@Service
@RequiredArgsConstructor
public class ScreenCopyService {

    private static final String HTTPS = "https://";
    private static final int MAX_HEADLINE = 120;
    private static final int MAX_SUBTEXT = 300;
    private static final int MAX_FOOTER = 200;
    private static final int MAX_URL = 2048;

    private final AuthScreenCopyRepository repository;
    private final ActingTier tier;
    private final ApplicationEventPublisher events;

    /**
     * The wording to render for {@code orgId}: the tenant's own over the platform's, screen by screen and
     * field by field. A screen nobody has written is absent from the map rather than present and empty, so a
     * client can treat "has an entry" as "has something to say".
     */
    @Transactional(readOnly = true)
    public Map<AuthScreen, ScreenCopy> resolve(UUID orgId) {
        Map<AuthScreen, ScreenCopy> resolved = byScreen(repository.findByOrgIdIsNull());
        if (orgId != null) {
            byScreen(repository.findByOrgId(orgId)).forEach((screen, own) ->
                    resolved.merge(screen, own, (platform, tenant) -> tenant.inheriting(platform)));
        }
        resolved.values().removeIf(ScreenCopy::saysNothing);
        return resolved;
    }

    /** The acting tier's OWN wording, for the editor. Empty for a tier that owns no rows. */
    @Transactional(readOnly = true)
    public Map<AuthScreen, ScreenCopy> get() {
        return byScreen(ownRows());
    }

    /** Writes the acting tier's wording for one screen. A blank field clears it, back to inheriting. */
    @Transactional
    public void update(AuthScreen screen, ScreenCopy copy) {
        UUID org = tier.writableOrg();
        ScreenCopy validated = validated(copy);
        ownRow(screen).ifPresentOrElse(
                row -> row.reconfigure(validated),
                () -> repository.save(AuthScreenCopy.create(org, screen, validated)));
        events.publishEvent(new BrandingChanged(org));
    }

    /** Drops the acting tier's wording for one screen — it reverts to the platform/built-in text. */
    @Transactional
    public void delete(AuthScreen screen) {
        UUID org = tier.writableOrg();
        ownRow(screen).ifPresent(repository::delete);
        events.publishEvent(new BrandingChanged(org));
    }

    /** Trimmed and capped; a blank or whitespace-only field becomes null, which is how a piece re-inherits. */
    private ScreenCopy validated(ScreenCopy copy) {
        return new ScreenCopy(
                capped(copy.headline(), MAX_HEADLINE, "branding.screenCopy.headline.tooLong"),
                capped(copy.subtext(), MAX_SUBTEXT, "branding.screenCopy.subtext.tooLong"),
                capped(copy.footer(), MAX_FOOTER, "branding.screenCopy.footer.tooLong"),
                helpUrl(copy.helpUrl()));
    }

    private String capped(String value, int max, String messageKey) {
        String trimmed = trimToNull(value);
        if (trimmed != null && trimmed.length() > max) {
            throw BadRequestException.of(messageKey);
        }
        return trimmed;
    }

    private String helpUrl(String value) {
        String trimmed = trimToNull(value);
        if (trimmed == null) {
            return null;
        }
        if (!trimmed.toLowerCase(Locale.ROOT).startsWith(HTTPS)) {
            throw BadRequestException.of("branding.screenCopy.helpUrl.notHttps");
        }
        if (trimmed.length() > MAX_URL) {
            throw BadRequestException.of("branding.screenCopy.helpUrl.tooLong");
        }
        return trimmed;
    }

    /** A mutable EnumMap, because {@link #resolve} merges the tenant's rows into the platform's in place. */
    private Map<AuthScreen, ScreenCopy> byScreen(List<AuthScreenCopy> rows) {
        Map<AuthScreen, ScreenCopy> map = new EnumMap<>(AuthScreen.class);
        for (AuthScreenCopy row : rows) {
            map.put(row.getScreen(), row.copy());
        }
        return map;
    }

    /** The acting tier's rows — the platform tier owns the global ones, a bound-orgless tenant owns none. */
    private List<AuthScreenCopy> ownRows() {
        UUID org = tier.org().orElse(null);
        if (org != null) {
            return repository.findByOrgId(org);
        }
        return tier.ownsGlobalRow() ? repository.findByOrgIdIsNull() : List.of();
    }

    private Optional<AuthScreenCopy> ownRow(AuthScreen screen) {
        UUID org = tier.org().orElse(null);
        if (org != null) {
            return repository.findByOrgIdAndScreen(org, screen);
        }
        return tier.ownsGlobalRow() ? repository.findByOrgIdIsNullAndScreen(screen) : Optional.empty();
    }

    private String trimToNull(String value) {
        return StringUtils.hasText(value) ? value.trim() : null;
    }
}
