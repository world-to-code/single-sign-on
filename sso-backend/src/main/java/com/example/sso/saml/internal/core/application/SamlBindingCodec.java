package com.example.sso.saml.internal.core.application;

import com.example.sso.shared.error.BadRequestException;
import lombok.RequiredArgsConstructor;
import net.shibboleth.shared.xml.ParserPool;
import net.shibboleth.shared.xml.SerializeSupport;
import org.opensaml.core.xml.XMLObject;
import org.opensaml.core.xml.config.XMLObjectProviderRegistrySupport;
import org.opensaml.core.xml.io.MarshallingException;
import org.opensaml.core.xml.io.Unmarshaller;
import org.opensaml.saml.saml2.core.AuthnRequest;
import org.opensaml.saml.saml2.core.LogoutRequest;
import org.opensaml.saml.saml2.core.Response;
import org.opensaml.saml.common.SignableSAMLObject;
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
        return (AuthnRequest) parseRedirect(samlRequest);
    }

    /** Decodes a {@code SAMLRequest} carried over the HTTP-POST binding. */
    public AuthnRequest decodePost(String samlRequest) {
        return (AuthnRequest) parsePost(samlRequest);
    }

    /** Decodes an inbound {@code LogoutRequest} over the HTTP-Redirect binding. */
    public LogoutRequest decodeLogoutRedirect(String samlRequest) {
        return (LogoutRequest) parseRedirect(samlRequest);
    }

    /** Decodes an inbound {@code LogoutRequest} over the HTTP-POST binding. */
    public LogoutRequest decodeLogoutPost(String samlRequest) {
        return (LogoutRequest) parsePost(samlRequest);
    }

    private XMLObject parseRedirect(String message) {
        byte[] deflated = Base64.getDecoder().decode(message);
        try (InflaterInputStream inflater =
                     new InflaterInputStream(new ByteArrayInputStream(deflated), new Inflater(true))) {
            return parse(inflater);
        } catch (Exception e) {
            throw BadRequestException.of("saml.binding.invalidRedirect");
        }
    }

    private XMLObject parsePost(String message) {
        byte[] xml = Base64.getDecoder().decode(message);
        try {
            return parse(new ByteArrayInputStream(xml));
        } catch (Exception e) {
            throw BadRequestException.of("saml.binding.invalidPost");
        }
    }

    private XMLObject parse(InputStream in) throws Exception {
        Document document = parserPool.parse(in);
        Element root = document.getDocumentElement();
        Unmarshaller unmarshaller = XMLObjectProviderRegistrySupport
                .getUnmarshallerFactory().getUnmarshaller(root);

        return unmarshaller.unmarshall(root);
    }

    /** Base64-encodes a marshalled SAML {@link Response} for the HTTP-POST binding. */
    public String encode(Response response) {
        return encodeObject(response);
    }

    /** Base64-encodes any marshalled/signed SAML object (Response, LogoutResponse) for the POST binding. */
    public String encodeObject(SignableSAMLObject object) {
        Element element = object.getDOM();
        if (element == null) {
            try {
                element = XMLObjectProviderRegistrySupport.getMarshallerFactory()
                        .getMarshaller(object).marshall(object);
            } catch (MarshallingException e) {
                throw new IllegalStateException("Failed to marshall SAML object", e);
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
        return formHtml(acsUrl, "SAMLResponse", base64Response, relayState, scriptNonce);
    }

    /** Auto-submit POST form carrying a {@code SAMLRequest} — for a front-channel LogoutRequest over POST. */
    public String postRequestHtml(String destination, String base64Request, String relayState, String scriptNonce) {
        return formHtml(destination, "SAMLRequest", base64Request, relayState, scriptNonce);
    }

    private String formHtml(String actionUrl, String fieldName, String base64Value, String relayState,
                            String scriptNonce) {
        String relayStateField = relayState == null ? "" :
                "<input type=\"hidden\" name=\"RelayState\" value=\""
                        + HtmlUtils.htmlEscape(relayState) + "\"/>";

        return """
                <!DOCTYPE html><html><head><meta charset="utf-8"><title>Signing out…</title></head>
                <body>
                  <form method="POST" action="%s">
                    <input type="hidden" name="%s" value="%s"/>
                    %s
                    <input type="submit" value="Continue"/>
                  </form>
                  <script nonce="%s">document.forms[0].submit();</script>
                </body></html>
                """.formatted(HtmlUtils.htmlEscape(actionUrl), fieldName,
                HtmlUtils.htmlEscape(base64Value), relayStateField, HtmlUtils.htmlEscape(scriptNonce));
    }
}
