package com.example.sso.branding.internal.application;

import com.example.sso.branding.AuthScreen;
import com.example.sso.branding.Branding;
import com.example.sso.branding.BrandingCorner;
import com.example.sso.branding.BrandingFont;
import com.example.sso.branding.BrandingIdentity;
import com.example.sso.branding.BrandingTheme;
import com.example.sso.branding.ScreenCopy;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.HashOperations;
import org.springframework.data.redis.core.StringRedisTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link BrandingCache}, written before it existed.
 *
 * <p>Two properties decide whether this cache is safe rather than merely fast. First, a PLATFORM write has to
 * evict every tenant, because every tenant inherits the platform row field by field — evicting only the
 * written tier would leave the whole fleet serving values the platform no longer has. Second, Redis being
 * unreachable must degrade to a miss, never to an error: this cache sits in front of the sign-in screen, and
 * failing closed there would take login down to save four queries.
 */
@ExtendWith(MockitoExtension.class)
class BrandingCacheTest {

    private static final UUID ORG = UUID.randomUUID();
    /** The TTL is only a backstop for a missed eviction; these tests do not depend on its value. */
    private static final long TTL_MINUTES = 30;

    @Mock
    StringRedisTemplate redis;
    @Mock
    HashOperations<String, Object, Object> hash;

    private BrandingCache cache() {
        lenient().when(redis.opsForHash()).thenReturn(hash);
        return new BrandingCache(redis, TTL_MINUTES);
    }

    private Branding branding(String productName) {
        return new Branding(
                new BrandingIdentity(null, null, null, productName),
                new BrandingTheme("#123abc", null, null, BrandingFont.SERIF, BrandingCorner.ROUND, null),
                Map.of(AuthScreen.LOGIN, new ScreenCopy("Sign in to Acme", null, null, null)));
    }

    @Test
    void aMissReturnsEmpty() {
        BrandingCache cache = cache();
        when(hash.get(anyString(), any())).thenReturn(null);

        assertThat(cache.find(ORG)).isEmpty();
    }

    /** Round-trips through the serialized form, so a field that does not survive JSON is caught here. */
    @Test
    void aStoredBrandingComesBackWithEveryField() {
        BrandingCache cache = cache();
        Branding stored = branding("Acme");
        cache.put(ORG, stored);

        ArgumentCaptor<Object> written = ArgumentCaptor.captor();
        verify(hash).put(anyString(), eq(ORG.toString()), written.capture());
        when(hash.get(anyString(), eq(ORG.toString()))).thenReturn(written.getValue());

        Branding read = cache.find(ORG).orElseThrow();
        assertThat(read.productName()).isEqualTo("Acme");
        assertThat(read.theme().accentColor()).isEqualTo("#123abc");
        assertThat(read.theme().font()).isEqualTo(BrandingFont.SERIF);
        assertThat(read.theme().corner()).isEqualTo(BrandingCorner.ROUND);
        assertThat(read.copy().get(AuthScreen.LOGIN).headline()).isEqualTo("Sign in to Acme");
    }

    /** The platform tier has no org id of its own, so it needs a field name that no UUID can collide with. */
    @Test
    void thePlatformTierIsCachedUnderItsOwnField() {
        BrandingCache cache = cache();
        cache.put(null, branding("Svalinn"));

        verify(hash).put(anyString(), eq("platform"), any());
    }

    @Test
    void evictingATenantDropsOnlyThatTenantsEntry() {
        BrandingCache cache = cache();

        cache.evictWrittenBy(ORG);

        verify(hash).delete(anyString(), eq(ORG.toString()));
        verify(redis, never()).delete(anyString());
    }

    /**
     * The case a naive cache gets wrong. Every tenant inherits the platform row per field, so a platform edit
     * changes what EVERY tenant resolves — dropping only the platform's own entry would leave every tenant
     * serving the old inherited values until the TTL.
     */
    @Test
    void aPlatformWriteEvictsEveryTenant() {
        BrandingCache cache = cache();

        cache.evictWrittenBy(null);

        verify(redis).delete(anyString());
        verify(hash, never()).delete(anyString(), any());
    }

    @Test
    void anUnreachableRedisReadsAsAMissRatherThanAnError() {
        BrandingCache cache = cache();
        when(hash.get(anyString(), any())).thenThrow(new IllegalStateException("redis down"));

        assertThat(cache.find(ORG)).isEmpty();
    }

    /** A failed WRITE must not fail the request either: the value is already correct in the database. */
    @Test
    void anUnreachableRedisDoesNotFailAPut() {
        BrandingCache cache = cache();
        doThrow(new IllegalStateException("redis down")).when(hash).put(anyString(), any(), any());

        cache.put(ORG, branding("Acme"));
    }

    /** An eviction that cannot reach Redis must not roll back the write that triggered it. */
    @Test
    void anUnreachableRedisDoesNotFailAnEviction() {
        BrandingCache cache = cache();
        doThrow(new IllegalStateException("redis down")).when(hash).delete(anyString(), any());

        cache.evictWrittenBy(ORG);
    }

    /** Corrupt or stale-format JSON must read as a miss, not throw at the sign-in screen. */
    @Test
    void anUnreadableEntryReadsAsAMiss() {
        BrandingCache cache = cache();
        when(hash.get(anyString(), any())).thenReturn("{not json");

        assertThat(cache.find(ORG)).isEmpty();
    }
}
