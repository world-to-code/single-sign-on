package com.example.sso.saml.internal.core.application;

import com.example.sso.shared.error.BadRequestException;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.util.Base64;
import java.util.zip.Deflater;
import java.util.zip.DeflaterOutputStream;
import net.shibboleth.shared.xml.ParserPool;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

/**
 * The HTTP-Redirect binding carries base64(raw-deflate(xml)) on an UNAUTHENTICATED endpoint, and the message
 * is inflated before its signature is checked — there is no earlier point at which an attacker is filtered out.
 *
 * <p>Deflate reaches roughly 1000:1 on repetitive input, so a request that fits inside the container's header
 * limit expands to megabytes of heap, and every one of them then goes through the XML parser. Bounding the
 * inflated size is the only thing between a query string and the heap.
 */
@ExtendWith(MockitoExtension.class)
class SamlBindingCodecInflateTest {

    /** Small enough to keep the test fast; the production value comes from configuration. */
    private static final int LIMIT = 4096;

    @Mock private ParserPool parserPool;

    private SamlBindingCodec codec;

    @BeforeEach
    void setUp() {
        codec = new SamlBindingCodec(parserPool, LIMIT);
    }

    /** base64(raw-deflate(payload)) — the wire form of a redirect-binding SAMLRequest. */
    private String redirectMessage(byte[] payload) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (DeflaterOutputStream deflater = new DeflaterOutputStream(out, new Deflater(9, true))) {
            deflater.write(payload);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
        return Base64.getEncoder().encodeToString(out.toByteArray());
    }

    /**
     * The compression bomb: highly repetitive input, so the compressed form is tiny and the inflated form is
     * not. The message is refused and the parser is never handed it.
     *
     * <p>What this CANNOT see, stated plainly: swapping {@code readNBytes(limit + 1)} for
     * {@code readAllBytes()} leaves it green — both reject, and the difference is only in how much was
     * allocated on the way there. Bounded allocation is not observable from a unit test without either an
     * OOM-sized fixture or exposing the inflater's byte count for the assertion, and neither is worth it.
     * The bound lives in one line of {@code parseRedirect}; this test holds the contract around it.
     */
    @Test
    void aMessageThatInflatesPastTheLimitIsRefusedBeforeItIsParsed() {
        byte[] bomb = new byte[LIMIT * 64];

        assertThatThrownBy(() -> codec.decodeRedirect(redirectMessage(bomb)))
                .isInstanceOf(BadRequestException.class);

        verifyNoInteractions(parserPool);
    }

    /** A LogoutRequest travels the same binding, so it needs the same bound — SLO is unauthenticated too. */
    @Test
    void theLogoutBindingIsBoundedToo() {
        byte[] bomb = new byte[LIMIT * 64];

        assertThatThrownBy(() -> codec.decodeLogoutRedirect(redirectMessage(bomb)))
                .isInstanceOf(BadRequestException.class);

        verifyNoInteractions(parserPool);
    }

    /**
     * A message exactly at the limit still parses: the bound must not be so eager that a legitimate
     * AuthnRequest carrying a long RelayState or a large signed extension is refused.
     */
    @Test
    void aMessageUnderTheLimitStillReachesTheParser() throws Exception {
        byte[] payload = new byte[LIMIT];

        // The stubbed pool returns no document, so decoding still fails — the assertion that matters is below.
        assertThatThrownBy(() -> codec.decodeRedirect(redirectMessage(payload)))
                .isInstanceOf(BadRequestException.class);

        verify(parserPool).parse(any(InputStream.class));
    }
}
