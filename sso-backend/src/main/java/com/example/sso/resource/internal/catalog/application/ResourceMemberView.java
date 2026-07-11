package com.example.sso.resource.internal.catalog.application;

/** A polymorphic leaf member of a {@link ResourceView} (GROUP | APPLICATION | USER + its id). */
public record ResourceMemberView(String memberType, String memberId) {
}
