package com.example.sso.directory;

import java.util.UUID;

/** One "directory attribute → declared profile attribute" rule. */
public record DirectoryAttributeMappingView(UUID id, String sourceAttribute, String targetKey) {
}
