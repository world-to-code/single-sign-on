package com.example.sso.crypto.internal.application;

import com.example.sso.crypto.SecretCipher;
import com.example.sso.crypto.internal.domain.SigningKey;
import com.example.sso.crypto.internal.domain.SigningKeyRepository;
import com.example.sso.crypto.internal.domain.SigningKeyRetention;
import com.example.sso.crypto.internal.domain.SigningKeyRetentionRepository;
import com.example.sso.shared.error.BadRequestException;
import com.example.sso.shared.error.ConflictException;
import com.example.sso.shared.error.ForbiddenException;
import com.example.sso.tenancy.OrgContext;
import com.nimbusds.jose.jwk.JWKSet;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.util.Base64;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Limit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link RsaKeyServiceImpl}. The security-critical interactions: the private key is
 * ENCRYPTED via {@link SecretCipher} before it is persisted and DECRYPTED when materialized into a
 * signing {@code RSAKey}; the JWKS keeps rotated-away keys published (ACTIVE key first) bounded by the
 * tier's retention setting; and a tier only ever publishes its own keys (global as fallback).
 */
@ExtendWith(MockitoExtension.class)
class RsaKeyServiceImplTest {

    private static final int DEFAULT_RETAINED = 1;
    private static final int MAX_RETAINED = 10;

    @Mock
    private SigningKeyRepository repository;
    @Mock
    private SigningKeyRetentionRepository retentionRepository;
    @Mock
    private SecretCipher secretCipher;
    @Mock
    private OrgContext orgContext;

    private RsaKeyServiceImpl newService() {
        return new RsaKeyServiceImpl(repository, retentionRepository, secretCipher, orgContext, 2048,
                DEFAULT_RETAINED, MAX_RETAINED);
    }

    /** A stored key whose private part decrypts to a real RSA private key (kid-scoped cipher text). */
    private SigningKey storedKey(String kid, UUID orgId, boolean active) throws Exception {
        KeyPair pair = KeyPairGenerator.getInstance("RSA").generateKeyPair();
        String publicKey = Base64.getEncoder().encodeToString(pair.getPublic().getEncoded());
        String privateKey = Base64.getEncoder().encodeToString(pair.getPrivate().getEncoded());
        SigningKey key = new SigningKey(kid, "RS256", publicKey, "encg:" + kid, orgId);
        if (!active) {
            key.deactivate();
        }
        // lenient: a key beyond the retention bound is (deliberately) never materialized/decrypted.
        lenient().when(secretCipher.decrypt("encg:" + kid)).thenReturn(privateKey);
        return key;
    }

    @Test
    void rotateGeneratesAndStoresAKeyWithAnEncryptedPrivateKey() {
        when(repository.findFirstByActiveTrueAndOrgIdIsNullOrderByCreatedAtDesc()).thenReturn(Optional.empty());
        when(secretCipher.encrypt(anyString())).thenReturn("encg:encrypted-private");
        when(repository.save(any(SigningKey.class))).thenAnswer(invocation -> invocation.getArgument(0));

        String kid = newService().rotate();

        verify(secretCipher).encrypt(anyString());
        ArgumentCaptor<SigningKey> saved = ArgumentCaptor.forClass(SigningKey.class);
        verify(repository).save(saved.capture());
        assertThat(saved.getValue().getPrivateKey()).isEqualTo("encg:encrypted-private");
        assertThat(saved.getValue().getAlgorithm()).isEqualTo("RS256");
        assertThat(saved.getValue().isActive()).isTrue();
        assertThat(kid).isEqualTo(saved.getValue().getKid());
    }

    @Test
    void buildJwkSetDecryptsTheStoredPrivateKeyToMaterializeTheSigningKey() throws Exception {
        SigningKey stored = storedKey("kid-1", null, true);
        when(repository.findByOrgIdIsNullOrderByActiveDescCreatedAtDesc(any(Limit.class)))
                .thenReturn(List.of(stored));

        JWKSet jwkSet = newService().buildJwkSet();

        verify(secretCipher).decrypt("encg:kid-1");
        assertThat(jwkSet.getKeys()).hasSize(1);
        assertThat(jwkSet.getKeyByKeyId("kid-1")).isNotNull();
    }

    @Test
    void buildJwkSetKeepsRotatedAwayKeysPublishedActiveFirst() throws Exception {
        SigningKey rotatedAway = storedKey("kid-old", null, false);
        SigningKey active = storedKey("kid-new", null, true);
        when(repository.findByOrgIdIsNullOrderByActiveDescCreatedAtDesc(any(Limit.class)))
                .thenReturn(List.of(active, rotatedAway));

        JWKSet jwkSet = newService().buildJwkSet();

        // Both survive rotation (tokens signed by the old key stay verifiable), the ACTIVE key first
        // (signing selects the first match).
        assertThat(jwkSet.getKeys()).hasSize(2);
        assertThat(jwkSet.getKeys().get(0).getKeyID()).isEqualTo("kid-new");
        assertThat(jwkSet.getKeyByKeyId("kid-old")).isNotNull();
    }

    @Test
    void buildJwkSetBoundsTheQueryToTheTiersOwnRetentionSetting() throws Exception {
        UUID org = UUID.randomUUID();
        SigningKey active = storedKey("kid-a", org, true);
        when(orgContext.currentOrg()).thenReturn(Optional.of(org));
        when(retentionRepository.findByOrgId(org))
                .thenReturn(Optional.of(new SigningKeyRetention(org, 0)));
        when(repository.findByOrgIdOrderByActiveDescCreatedAtDesc(eq(org), any(Limit.class)))
                .thenReturn(List.of(active));

        JWKSet jwkSet = newService().buildJwkSet();

        // The org's own N=0 wins over the global/config default, bounded IN THE QUERY (limit = 1 + N).
        ArgumentCaptor<Limit> limit = ArgumentCaptor.forClass(Limit.class);
        verify(repository).findByOrgIdOrderByActiveDescCreatedAtDesc(eq(org), limit.capture());
        assertThat(limit.getValue().max()).isEqualTo(1);
        assertThat(jwkSet.getKeys()).hasSize(1);
        assertThat(jwkSet.getKeys().get(0).getKeyID()).isEqualTo("kid-a");
    }

    @Test
    void buildJwkSetFallsBackToTheGlobalKeysAndTheGlobalRetentionWhenTheOrgHasNoActiveKey() throws Exception {
        UUID org = UUID.randomUUID();
        SigningKey global = storedKey("kid-global", null, true);
        when(orgContext.currentOrg()).thenReturn(Optional.of(org));
        // The org customized a large retention; the GLOBAL tier it falls back to has none → config default.
        when(retentionRepository.findByOrgId(org))
                .thenReturn(Optional.of(new SigningKeyRetention(org, 5)));
        when(retentionRepository.findByOrgIdIsNull()).thenReturn(Optional.empty());
        when(repository.findByOrgIdOrderByActiveDescCreatedAtDesc(eq(org), any(Limit.class)))
                .thenReturn(List.of());
        when(repository.findByOrgIdIsNullOrderByActiveDescCreatedAtDesc(any(Limit.class)))
                .thenReturn(List.of(global));

        JWKSet jwkSet = newService().buildJwkSet();

        // A tenant without its own key signs (verifiably) under the global key, and the fallback set is
        // bounded by the GLOBAL tier's retention — not the org's own setting.
        assertThat(jwkSet.getKeyByKeyId("kid-global")).isNotNull();
        ArgumentCaptor<Limit> limit = ArgumentCaptor.forClass(Limit.class);
        verify(repository).findByOrgIdIsNullOrderByActiveDescCreatedAtDesc(limit.capture());
        assertThat(limit.getValue().max()).isEqualTo(1 + DEFAULT_RETAINED);
    }

    @Test
    void buildJwkSetFailsLoudlyWhenNoTierHasAnActiveKey() {
        when(repository.findByOrgIdIsNullOrderByActiveDescCreatedAtDesc(any(Limit.class)))
                .thenReturn(List.of());

        assertThatThrownBy(() -> newService().buildJwkSet())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("No active signing key");
    }

    @Test
    void rotateCreatesAKeyOwnedByTheBoundOrg() {
        UUID org = UUID.randomUUID();
        when(orgContext.currentOrg()).thenReturn(Optional.of(org));
        when(repository.findFirstByActiveTrueAndOrgIdOrderByCreatedAtDesc(org)).thenReturn(Optional.empty());
        when(secretCipher.encrypt(anyString())).thenReturn("encg:enc");
        when(repository.save(any(SigningKey.class))).thenAnswer(invocation -> invocation.getArgument(0));

        newService().rotate();

        // A rotation in an org context creates that org's OWN key (never a global one).
        ArgumentCaptor<SigningKey> saved = ArgumentCaptor.forClass(SigningKey.class);
        verify(repository).save(saved.capture());
        assertThat(saved.getValue().getOrgId()).isEqualTo(org);
    }

    @Test
    void buildJwkSetPrefersTheBoundOrgsOwnKeysOverTheGlobalKeys() throws Exception {
        UUID org = UUID.randomUUID();
        SigningKey orgKey = storedKey("org-kid", org, true);
        when(orgContext.currentOrg()).thenReturn(Optional.of(org));
        when(repository.findByOrgIdOrderByActiveDescCreatedAtDesc(eq(org), any(Limit.class)))
                .thenReturn(List.of(orgKey));

        JWKSet jwkSet = newService().buildJwkSet();

        // Signs with the tenant's own key, and never mixes in global (or another tenant's) keys.
        assertThat(jwkSet.getKeyByKeyId("org-kid")).isNotNull();
        verify(repository, never()).findByOrgIdIsNullOrderByActiveDescCreatedAtDesc(any(Limit.class));
    }

    @Test
    void retentionResolvesTheOrgsOwnRowFirst() {
        UUID org = UUID.randomUUID();
        when(orgContext.currentOrg()).thenReturn(Optional.of(org));
        when(retentionRepository.findByOrgId(org)).thenReturn(Optional.of(new SigningKeyRetention(org, 3)));

        assertThat(newService().retainedInactiveKeys()).isEqualTo(3);
    }

    @Test
    void retentionFallsBackToTheGlobalRowThenTheConfigDefault() {
        UUID org = UUID.randomUUID();
        when(orgContext.currentOrg()).thenReturn(Optional.of(org));
        when(retentionRepository.findByOrgId(org)).thenReturn(Optional.empty());
        when(retentionRepository.findByOrgIdIsNull())
                .thenReturn(Optional.of(new SigningKeyRetention(null, 2)), Optional.empty());

        assertThat(newService().retainedInactiveKeys()).isEqualTo(2);
        // Second call: no global row either — the application.yml default applies.
        assertThat(newService().retainedInactiveKeys()).isEqualTo(DEFAULT_RETAINED);
    }

    @Test
    void updateRetentionRejectsAValueOutsideTheAllowedRange() {
        assertThatThrownBy(() -> newService().updateRetainedInactiveKeys(-1))
                .isInstanceOf(BadRequestException.class);
        assertThatThrownBy(() -> newService().updateRetainedInactiveKeys(MAX_RETAINED + 1))
                .isInstanceOf(BadRequestException.class);
    }

    @Test
    void updateRetentionWritesTheBoundOrgsOwnRowCopyOnWrite() {
        UUID org = UUID.randomUUID();
        when(orgContext.currentOrg()).thenReturn(Optional.of(org));
        when(retentionRepository.findByOrgId(org)).thenReturn(Optional.empty());
        when(retentionRepository.saveAndFlush(any(SigningKeyRetention.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        int result = newService().updateRetainedInactiveKeys(2);

        ArgumentCaptor<SigningKeyRetention> saved = ArgumentCaptor.forClass(SigningKeyRetention.class);
        verify(retentionRepository).saveAndFlush(saved.capture());
        assertThat(saved.getValue().getOrgId()).isEqualTo(org);
        assertThat(saved.getValue().getRetainedInactiveKeys()).isEqualTo(2);
        assertThat(result).isEqualTo(2);
    }

    @Test
    void updateRetentionGlobalWriteIsPlatformOnly() {
        when(orgContext.currentOrg()).thenReturn(Optional.empty());
        when(orgContext.isPlatform()).thenReturn(false);

        // Fail closed: an unbound non-platform principal must never edit the default every tenant inherits.
        assertThatThrownBy(() -> newService().updateRetainedInactiveKeys(2))
                .isInstanceOf(ForbiddenException.class);
    }

    @Test
    void updateRetentionAsThePlatformWritesTheGlobalRow() {
        when(orgContext.currentOrg()).thenReturn(Optional.empty());
        when(orgContext.isPlatform()).thenReturn(true);
        when(retentionRepository.findByOrgIdIsNull()).thenReturn(Optional.empty());
        when(retentionRepository.saveAndFlush(any(SigningKeyRetention.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        newService().updateRetainedInactiveKeys(4);

        ArgumentCaptor<SigningKeyRetention> saved = ArgumentCaptor.forClass(SigningKeyRetention.class);
        verify(retentionRepository).saveAndFlush(saved.capture());
        assertThat(saved.getValue().getOrgId()).isNull();
        assertThat(saved.getValue().getRetainedInactiveKeys()).isEqualTo(4);
    }

    @Test
    void updateRetentionMapsAConcurrentFirstSaveToAConflict() {
        UUID org = UUID.randomUUID();
        when(orgContext.currentOrg()).thenReturn(Optional.of(org));
        when(retentionRepository.findByOrgId(org)).thenReturn(Optional.empty());
        when(retentionRepository.saveAndFlush(any(SigningKeyRetention.class)))
                .thenThrow(new DataIntegrityViolationException("ux_signing_key_retention_org"));

        // Two concurrent first-saves race the partial unique index — the loser gets a 409, not a 500.
        assertThatThrownBy(() -> newService().updateRetainedInactiveKeys(2))
                .isInstanceOf(ConflictException.class);
    }
}
