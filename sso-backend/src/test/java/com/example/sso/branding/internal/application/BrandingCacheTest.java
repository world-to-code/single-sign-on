package com.example.sso.branding.internal.application;

import com.example.sso.branding.AuthScreen;
import com.example.sso.branding.Branding;
import com.example.sso.branding.BrandingCorner;
import com.example.sso.branding.BrandingFont;
import com.example.sso.branding.BrandingIdentity;
import com.example.sso.branding.BrandingTheme;
import com.example.sso.branding.ScreenCopy;
import java.time.Duration;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link BrandingCache}.
 *
 * <p>Three properties decide whether this cache is safe rather than merely fast, and each was a real defect in
 * the first version of it:
 *
 * <ul>
 *   <li><b>The TTL must be able to fire.</b> One key per tier, each with its own expiry. The first version put
 *       every tier in ONE hash and called {@code EXPIRE} on that hash, so any tenant's fill reset the expiry
 *       for everybody and the "backstop" could never elapse under traffic.</li>
 *   <li><b>A write must beat a read already in flight.</b> A reader that missed, loaded the pre-write rows and
 *       then stored them would resurrect the replaced value with no eviction pending — indefinitely, given the
 *       TTL defect above. The generation captured BEFORE the read is what sends that store somewhere nobody
 *       will look.</li>
 *   <li><b>Redis failure degrades to a miss.</b> This sits in front of the sign-in screen; failing closed
 *       there would take login down to save four queries.</li>
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
class BrandingCacheTest {

    private static final UUID ORG = UUID.randomUUID();
    private static final long TTL_MINUTES = 30;

    @Mock
    StringRedisTemplate redis;
    @Mock
    ValueOperations<String, String> values;

    private BrandingCache cache() {
        lenient().when(redis.opsForValue()).thenReturn(values);
        return new BrandingCache(redis, TTL_MINUTES);
    }

    private Branding branding(String productName) {
        return new Branding(
                new BrandingIdentity(null, null, null, productName),
                new BrandingTheme("#123abc", null, null, BrandingFont.SERIF, BrandingCorner.ROUND, null),
                Map.of(AuthScreen.LOGIN, new ScreenCopy("Sign in to Acme", null, null, null)));
    }

    // ------------------------------------------------------------- generation

    @Test
    void theGenerationStartsAtZeroWhenRedisHoldsNone() {
        BrandingCache cache = cache();
        when(values.get(anyString())).thenReturn(null);

        assertThat(cache.generation()).isZero();
    }

    @Test
    void anUnreadableGenerationReadsAsZeroRatherThanThrowing() {
        BrandingCache cache = cache();
        when(values.get(anyString())).thenReturn("not a number");

        assertThat(cache.generation()).isZero();
    }

    /** One counter for every tier: a write anywhere retires every entry, which is what removes the race. */
    @Test
    void invalidateBumpsTheGeneration() {
        BrandingCache cache = cache();

        cache.invalidate();

        verify(values).increment(anyString());
    }

    // ------------------------------------------------------------ read/write

    @Test
    void aMissReturnsEmpty() {
        BrandingCache cache = cache();
        when(values.get(contains(ORG.toString()))).thenReturn(null);

        assertThat(cache.find(7, ORG)).isEmpty();
    }

    /** Round-trips through the serialized form, so a field that does not survive JSON is caught here. */
    @Test
    void aStoredBrandingComesBackWithEveryField() {
        BrandingCache cache = cache();
        cache.put(7, ORG, branding("Acme"));

        ArgumentCaptor<String> written = ArgumentCaptor.captor();
        verify(values).set(contains(ORG.toString()), written.capture(), eq(Duration.ofMinutes(TTL_MINUTES)));
        when(values.get(contains(ORG.toString()))).thenReturn(written.getValue());

        Branding read = cache.find(7, ORG).orElseThrow();
        assertThat(read.productName()).isEqualTo("Acme");
        assertThat(read.theme().accentColor()).isEqualTo("#123abc");
        assertThat(read.theme().font()).isEqualTo(BrandingFont.SERIF);
        assertThat(read.copy().get(AuthScreen.LOGIN).headline()).isEqualTo("Sign in to Acme");
    }

    /** Every entry carries its OWN expiry, so a busy tenant cannot keep another tier's entry alive. */
    @Test
    void eachEntryIsStoredWithItsOwnTtl() {
        BrandingCache cache = cache();

        cache.put(7, ORG, branding("Acme"));

        verify(values).set(anyString(), anyString(), eq(Duration.ofMinutes(TTL_MINUTES)));
    }

    /**
     * The race the generation exists for. A reader captures generation 7, is descheduled, a write bumps to 8,
     * and the reader finally stores what it read. That store lands under 7, and every subsequent read asks for
     * 8 — so the replaced value is never served again.
     */
    @Test
    void anEntryStoredUnderAnOldGenerationIsNeverRead() {
        BrandingCache cache = cache();
        cache.put(7, ORG, branding("stale"));

        ArgumentCaptor<String> staleKey = ArgumentCaptor.captor();
        verify(values).set(staleKey.capture(), anyString(), any());

        when(values.get(anyString())).thenReturn(null);
        cache.find(8, ORG);

        ArgumentCaptor<String> readKey = ArgumentCaptor.captor();
        verify(values).get(readKey.capture());
        assertThat(readKey.getValue()).isNotEqualTo(staleKey.getValue());
    }

    /** The platform tier has no org id, so it needs a key segment no UUID can collide with. */
    @Test
    void thePlatformTierIsCachedUnderItsOwnKey() {
        BrandingCache cache = cache();

        cache.put(7, null, branding("Svalinn"));

        verify(values).set(contains("platform"), anyString(), any());
    }

    // ---------------------------------------------------- failure degradation

    @Test
    void anUnreachableRedisReadsAsAMissRatherThanAnError() {
        BrandingCache cache = cache();
        when(values.get(anyString())).thenThrow(new IllegalStateException("redis down"));

        assertThat(cache.find(7, ORG)).isEmpty();
    }

    /** A failed WRITE must not fail the request: the value is already correct in the database. */
    @Test
    void anUnreachableRedisDoesNotFailAPut() {
        BrandingCache cache = cache();
        doThrow(new IllegalStateException("redis down")).when(values).set(anyString(), anyString(), any());

        cache.put(7, ORG, branding("Acme"));
    }

    /** An invalidation that cannot reach Redis must not roll back the write that triggered it. */
    @Test
    void anUnreachableRedisDoesNotFailAnInvalidation() {
        BrandingCache cache = cache();
        doThrow(new IllegalStateException("redis down")).when(values).increment(anyString());

        cache.invalidate();
    }

    /** Corrupt or stale-format JSON must read as a miss, not throw at the sign-in screen. */
    @Test
    void anUnreadableEntryReadsAsAMiss() {
        BrandingCache cache = cache();
        when(values.get(anyString())).thenReturn("{not json");

        assertThat(cache.find(7, ORG)).isEmpty();
    }

    /**
     * A cached entry whose enum is no longer in the closed set must not reach a caller. The closed set is the
     * whole safety story for the style choices, and Redis is the one place a value can arrive from outside
     * the database's CHECK constraints.
     */
    @Test
    void anEntryCarryingAnUnknownEnumReadsAsAMiss() {
        BrandingCache cache = cache();
        when(values.get(anyString())).thenReturn(
                "{\"identity\":{},\"theme\":{\"font\":\"COMIC_SANS\"},\"copy\":{}}");

        assertThat(cache.find(7, ORG)).isEmpty();
        verify(values, never()).set(anyString(), anyString(), any());
    }
}
