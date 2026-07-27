package com.example.sso.federation.internal.application;

import com.example.sso.federation.FederationProtocol;
import com.example.sso.federation.IdentityProviderService;
import com.example.sso.federation.IdentityProviderView;
import com.example.sso.saml.inbound.SamlSpIdentity;
import com.example.sso.saml.inbound.SamlSpMetadata;
import com.example.sso.shared.error.BadRequestException;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/**
 * The SP metadata an administrator hands to the upstream IdP's operators. Joins what this module knows (which
 * connection, and the NameID format it requires) to what the {@code saml} module knows (how to say it in SAML).
 *
 * <p>The provider is read through the ordinary tier-scoped {@link IdentityProviderService#get}, so another
 * tenant's alias is a 404 here for exactly the same reason it is everywhere else — no separate scoping rule to
 * drift out of step with the registry's.
 */
@Service
@RequiredArgsConstructor
public class SamlSpMetadataService {

    private final IdentityProviderService providers;
    private final SamlSpIdentity spIdentity;
    private final SamlSpMetadata spMetadata;

    public String forProvider(String alias, HttpServletRequest request) {
        IdentityProviderView provider = providers.get(alias);
        if (provider.protocol() != FederationProtocol.SAML) {
            throw BadRequestException.of("federation.provider.notSaml");
        }
        return spMetadata.document(spIdentity.entityId(request, alias), spIdentity.acsUrl(request, alias),
                provider.nameIdFormat());
    }
}
