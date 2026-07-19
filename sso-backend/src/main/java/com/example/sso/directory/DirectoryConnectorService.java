package com.example.sso.directory;

import java.util.List;
import java.util.UUID;

/**
 * The acting tenant's directory connections and what they map. Scoped strictly per tier — a connector holds a
 * bind credential for someone's directory and is never inherited.
 */
public interface DirectoryConnectorService {

    List<DirectoryConnectorView> list();

    DirectoryConnectorView get(String name);

    /** Creates or reconfigures by name. A blank bind password KEEPS the stored one. */
    void save(DirectoryConnectorSpec spec);

    void delete(String name);

    List<DirectoryAttributeMappingView> mappings(String name);

    /** Maps a directory attribute onto a declared profile attribute, replacing any rule for that source. */
    void mapAttribute(String name, String sourceAttribute, String targetKey);

    void unmapAttribute(String name, UUID mappingId);

    /** Runs the connector now and returns what the run did. */
    DirectorySyncRunView syncNow(String name);

    /** The most recent runs, newest first. */
    List<DirectorySyncRunView> runs(String name, int limit);
}
