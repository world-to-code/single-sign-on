package com.example.sso.federation;

/**
 * Proves the ACS post came from the browser that STARTED this login. The handle itself never leaves the
 * federation module's caller — this asks the caller "does the request carry this handle?" rather than handing
 * the handle out, so the secret cannot be logged or compared with the wrong operator by accident.
 */
@FunctionalInterface
public interface BrowserBinding {

    boolean carries(String expectedHandle);
}
