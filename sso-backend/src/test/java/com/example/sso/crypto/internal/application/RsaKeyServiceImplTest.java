package com.example.sso.crypto.internal.application;

import com.example.sso.crypto.SecretCipher;
import com.example.sso.crypto.internal.domain.SigningKey;
import com.example.sso.crypto.internal.domain.SigningKeyRepository;
import com.example.sso.tenancy.OrgContext;
import com.nimbusds.jose.jwk.JWKSet;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.util.Base64;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link RsaKeyServiceImpl}. The security-critical interaction is that the private key
 * is ENCRYPTED via {@link SecretCipher} before it is persisted, and DECRYPTED when materialized into a
 * signing {@code RSAKey} — asserted with {@code verify} on the cipher and the repository.
 */
@ExtendWith(MockitoExtension.class)
class RsaKeyServiceImplTest {

    @Mock
    private SigningKeyRepository repository;
    @Mock
    private SecretCipher secretCipher;
    @Mock
    private OrgContext orgContext;

    private RsaKeyServiceImpl newService() {
        return new RsaKeyServiceImpl(repository, secretCipher, orgContext, 2048);
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
        KeyPair pair = KeyPairGenerator.getInstance("RSA").generateKeyPair();
        String publicKey = Base64.getEncoder().encodeToString(pair.getPublic().getEncoded());
        String privateKey = Base64.getEncoder().encodeToString(pair.getPrivate().getEncoded());
        SigningKey stored = new SigningKey("kid-1", "RS256", publicKey, "encg:cipher-private");
        when(repository.findFirstByActiveTrueAndOrgIdIsNullOrderByCreatedAtDesc()).thenReturn(Optional.of(stored));
        when(secretCipher.decrypt("encg:cipher-private")).thenReturn(privateKey);

        JWKSet jwkSet = newService().buildJwkSet();

        verify(secretCipher).decrypt("encg:cipher-private");
        assertThat(jwkSet.getKeys()).hasSize(1);
        assertThat(jwkSet.getKeyByKeyId("kid-1")).isNotNull();
    }

    @Test
    void buildJwkSetPrefersTheBoundOrgsOwnKeyOverTheGlobalKey() throws Exception {
        UUID org = UUID.randomUUID();
        KeyPair pair = KeyPairGenerator.getInstance("RSA").generateKeyPair();
        String publicKey = Base64.getEncoder().encodeToString(pair.getPublic().getEncoded());
        String privateKey = Base64.getEncoder().encodeToString(pair.getPrivate().getEncoded());
        SigningKey orgKey = new SigningKey("org-kid", "RS256", publicKey, "encg:org-cipher", org);
        when(orgContext.currentOrg()).thenReturn(Optional.of(org));
        when(repository.findFirstByActiveTrueAndOrgIdOrderByCreatedAtDesc(org)).thenReturn(Optional.of(orgKey));
        when(secretCipher.decrypt("encg:org-cipher")).thenReturn(privateKey);

        JWKSet jwkSet = newService().buildJwkSet();

        // Signs with the tenant's own key, and never falls back to the global key when the org has one.
        assertThat(jwkSet.getKeyByKeyId("org-kid")).isNotNull();
        verify(repository, never()).findFirstByActiveTrueAndOrgIdIsNullOrderByCreatedAtDesc();
    }
}
