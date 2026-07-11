/**
 * Named interface for user groups: the {@link UserGroupService} facade, the group spec/view/request DTOs,
 * membership read models, the paged members view and the {@code GroupDeletedEvent}. Group persistence stays
 * module-internal.
 */
@NamedInterface("group")
package com.example.sso.user.group;

import org.springframework.modulith.NamedInterface;
