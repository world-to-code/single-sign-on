package com.example.sso.scim.internal.api;

import com.example.sso.scim.internal.application.ScimGroupService;

import de.captaingoldfish.scim.sdk.common.constants.enums.SortOrder;
import de.captaingoldfish.scim.sdk.common.resources.Group;
import de.captaingoldfish.scim.sdk.common.schemas.SchemaAttribute;
import de.captaingoldfish.scim.sdk.server.endpoints.Context;
import de.captaingoldfish.scim.sdk.server.endpoints.ResourceHandler;
import de.captaingoldfish.scim.sdk.server.filter.FilterNode;
import de.captaingoldfish.scim.sdk.server.response.PartialListResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * SCIM 2.0 Group endpoint adapter. Thin by design — delegates persistence to
 * {@link ScimGroupService}.
 */
@Component
@RequiredArgsConstructor
public class GroupResourceHandler extends ResourceHandler<Group> {

    private final ScimGroupService service;

    @Override
    public Group createResource(Group resource, Context context) {
        return service.create(resource);
    }

    @Override
    public Group getResource(String id, List<SchemaAttribute> attributes,
                             List<SchemaAttribute> excludedAttributes, Context context) {
        return service.get(id);
    }

    @Override
    public PartialListResponse<Group> listResources(long startIndex, int count, FilterNode filter,
                                                    SchemaAttribute sortBy, SortOrder sortOrder,
                                                    List<SchemaAttribute> attributes,
                                                    List<SchemaAttribute> excludedAttributes, Context context) {
        return service.list(startIndex, count);
    }

    @Override
    public Group updateResource(Group resource, Context context) {
        return service.update(resource);
    }

    @Override
    public void deleteResource(String id, Context context) {
        service.delete(id);
    }
}
