package com.example.sso.metadata.internal.api;

import com.example.sso.metadata.AttributeDataType;
import com.example.sso.metadata.AttributeDefinitionSpec;
import com.example.sso.metadata.AttributeSource;
import com.example.sso.metadata.EntityKind;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.List;

/**
 * Declares one attribute of the acting tenant's profile schema. Bean validation only bounds the shape — the
 * key's referenceability, the ENUM/values agreement and the tier scoping are enforced in the service, which is
 * also what a directory sync goes through.
 */
public record AttributeDefinitionRequest(@NotNull EntityKind entityKind,
                                         @NotBlank @Size(max = 64) String key,
                                         @NotBlank @Size(max = 120) String displayName,
                                         @Size(max = 2000) String description,
                                         @NotNull AttributeDataType dataType,
                                         List<String> enumValues,
                                         boolean multiValued,
                                         boolean required,
                                         @NotNull AttributeSource source,
                                         int sortOrder) {

    public AttributeDefinitionSpec toSpec() {
        return new AttributeDefinitionSpec(entityKind, key, displayName, description, dataType, enumValues,
                multiValued, required, source, sortOrder);
    }
}
