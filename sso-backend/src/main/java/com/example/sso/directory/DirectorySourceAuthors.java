package com.example.sso.directory;

import java.util.Set;
import java.util.UUID;

/**
 * Who vouched for the directories that can fill a set of profile attributes.
 *
 * <p>Controlling a directory connector is a way to decide who satisfies someone else's mapping rule, so before
 * a directory-sourced attribute is allowed to drive a role or group grant, the people who aimed those
 * connectors have to be people who could have made that grant by hand.
 *
 * @param configurators the administrators who last saved each connector filling those attributes
 * @param complete      false when any such connector has no recorded configurator — the caller must then treat
 *                      the source as unauthorized rather than guess
 */
public record DirectorySourceAuthors(Set<UUID> configurators, boolean complete) {

    public static DirectorySourceAuthors none() {
        return new DirectorySourceAuthors(Set.of(), true);
    }
}
