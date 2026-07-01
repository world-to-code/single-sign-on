package com.example.sso.saml.internal.application;

import lombok.RequiredArgsConstructor;
import net.shibboleth.shared.xml.ParserPool;
import net.shibboleth.shared.xml.SerializeSupport;
import org.opensaml.core.xml.config.XMLObjectProviderRegistrySupport;
import org.opensaml.core.xml.io.MarshallingException;
import org.opensaml.core.xml.io.Unmarshaller;
import org.opensaml.saml.saml2.core.AuthnRequest;
import org.opensaml.saml.saml2.core.Response;
import org.springframework.stereotype.Component;
import org.springframework.web.util.HtmlUtils;
import org.w3c.dom.Document;
import org.w3c.dom.Element;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.zip.Inflater;
import java.util.zip.InflaterInputStream;

/**
 * SAML HTTP binding encode/decode. Inbound {@code AuthnRequest}: HTTP-Redirect is
 * base64(raw-deflate(xml)), HTTP-POST is base64(xml). Outbound {@code Response} uses the
 * HTTP-POST binding (base64(xml) in an auto-submitting form) — never deflated.
 */
@Component
@RequiredArgsConstructor
public class SamlBindingCodec {

    private final ParserPool parserPool;

    /** Decodes a {@code SAMLRequest} carried over the HTTP-Redirect binding. */
    public AuthnRequest decodeRedirect(String samlRequest) {
        byte[] deflated = Base64.getDecoder().decode(samlRequest);
        try (InflaterInputStream inflater =
                     new InflaterInputStream(new ByteArrayInputStream(deflated), new Inflater(true))) {
            return parse(inflater);
        } catch (Exception e) {
            throw new IllegalArgumentException("Invalid SAML redirect request", e);
        }
    }

    /** Decodes a {@code SAMLRequest} carried over the HTTP-POST binding. */
    public AuthnRequest decodePost(String samlRequest) {
        byte[] xml = Base64.getDecoder().decode(samlRequest);
        try {
            return parse(new ByteArrayInputStream(xml));
        } catch (Exception e) {
            throw new IllegalArgumentException("Invalid SAML POST request", e);
        }
    }

    private AuthnRequest parse(InputStream in) throws Exception {
        Document document = parserPool.parse(in);
        Element root = document.getDocumentElement();
        Unmarshaller unmarshaller = XMLObjectProviderRegistrySupport
                .getUnmarshallerFactory().getUnmarshaller(root);

        return (AuthnRequest) unmarshaller.unmarshall(root);
    }

    /** Base64-encodes a marshalled SAML {@link Response} for the HTTP-POST binding. */
    public String encode(Response response) {
        Element element = response.getDOM();
        if (element == null) {
            try {
                element = XMLObjectProviderRegistrySupport.getMarshallerFactory()
                        .getMarshaller(response).marshall(response);
            } catch (MarshallingException e) {
                throw new IllegalStateException("Failed to marshall SAML response", e);
            }
        }

        String xml = SerializeSupport.nodeToString(element);
        return Base64.getEncoder().encodeToString(xml.getBytes(StandardCharsets.UTF_8));
    }

    /**
     * Renders an auto-submitting HTML form that POSTs the response to the SP's ACS URL. The auto-submit
     * uses a nonce'd {@code <script>} (NOT an inline {@code onload} attribute), so it works under a strict
     * Content-Security-Policy — the caller must serve this page with {@code script-src 'nonce-<nonce>'}.
     * A visible Continue button is the fallback if the script does not run.
     */
    public String postBindingHtml(String acsUrl, String base64Response, String relayState, String scriptNonce) {
        String relayStateField = relayState == null ? "" :
                "<input type=\"hidden\" name=\"RelayState\" value=\""
                        + HtmlUtils.htmlEscape(relayState) + "\"/>";

        return """
                <!DOCTYPE html><html><head><meta charset="utf-8"><title>Signing in…</title></head>
                <body>
                  <form method="POST" action="%s">
                    <input type="hidden" name="SAMLResponse" value="%s"/>
                    %s
                    <input type="submit" value="Continue"/>
                  </form>
                  <script nonce="%s">document.forms[0].submit();</script>
                </body></html>
                """.formatted(HtmlUtils.htmlEscape(acsUrl),
                HtmlUtils.htmlEscape(base64Response), relayStateField, HtmlUtils.htmlEscape(scriptNonce));
    }
}
