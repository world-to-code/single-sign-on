package com.example.sso.saml.inbound;

/**
 * Everything a posted assertion is checked AGAINST. A verifier with no expectations can only answer "this is
 * well-formed XML somebody signed", which is not an authentication — so these are required, not optional, and
 * each one closes a distinct attack:
 *
 * <ul>
 *   <li>{@code idpEntityId} + {@code signingCertificate} — who may speak, and with which key (pinned).</li>
 *   <li>{@code spEntityId} — the audience, so an assertion minted for another SP cannot be replayed here.</li>
 *   <li>{@code acsUrl} — the destination/recipient, so one minted for another endpoint cannot be either.</li>
 *   <li>{@code inResponseTo} — the request WE issued, so an unsolicited assertion is refused.</li>
 *   <li>{@code nameIdFormat} — the format the connection requires, so an upstream cannot downgrade the join key
 *       to a reassignable address after the connection was configured for a stable one.</li>
 * </ul>
 */
public record AssertionExpectations(String idpEntityId, String signingCertificate, String spEntityId,
                                    String acsUrl, String inResponseTo, String nameIdFormat) {
}
