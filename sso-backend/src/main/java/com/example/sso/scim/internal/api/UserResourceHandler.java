package com.example.sso.scim.internal.api;

import com.example.sso.scim.internal.application.ScimUserService;

import de.captaingoldfish.scim.sdk.common.constants.enums.SortOrder;
import de.captaingoldfish.scim.sdk.common.resources.User;
import de.captaingoldfish.scim.sdk.common.schemas.SchemaAttribute;
import de.captaingoldfish.scim.sdk.server.endpoints.Context;
import de.captaingoldfish.scim.sdk.server.endpoints.ResourceHandler;
import de.captaingoldfish.scim.sdk.server.filter.FilterNode;
import de.captaingoldfish.scim.sdk.server.response.PartialListResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * SCIM 2.0 User endpoint adapter. Thin by design (no transactions/proxying here) —
 * delegates persistence to {@link ScimUserService}.
 */
@Component
@RequiredArgsConstructor
public class UserResourceHandler extends ResourceHandler<User> {

    private final ScimUserService service;

    @Override
    public User createResource(User resource, Context context) {
        return service.create(resource);
    }

    @Override
    public User getResource(String id, List<SchemaAttribute> attributes,
                            List<SchemaAttribute> excludedAttributes, Context context) {
        return service.get(id);
    }

    @Override
    public PartialListResponse<User> listResources(long startIndex, int count, FilterNode filter,
                                                   SchemaAttribute sortBy, SortOrder sortOrder,
                                                   List<SchemaAttribute> attributes,
                                                   List<SchemaAttribute> excludedAttributes, Context context) {
        return service.list(startIndex, count);
    }

    @Override
    public User updateResource(User resource, Context context) {
        return service.update(resource);
    }

    @Override
    public void deleteResource(String id, Context context) {
        service.delete(id);
    }
}
