package com.example.sso.resource.internal.application;

import com.example.sso.resource.internal.domain.MemberType;
import com.example.sso.resource.internal.domain.Resource;
import com.example.sso.resource.internal.domain.ResourceEdge;
import com.example.sso.resource.internal.domain.ResourceEdgeRepository;
import com.example.sso.resource.internal.domain.ResourceRepository;
import com.example.sso.resource.internal.domain.ResourceTypeAllowedMember;
import com.example.sso.resource.internal.domain.ResourceTypeAllowedMemberRepository;
import com.example.sso.shared.error.ConflictException;
import com.example.sso.shared.error.NotFoundException;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Graph mutations that need GLOBAL invariants: an edge may only be inserted when the child does not
 * already reach the parent (which would close a cycle). Edges are EXPLICIT {@link ResourceEdge} rows —
 * this service inserts/deletes them directly. The reachability check runs first, in the same
 * transaction as the insert; the parent aggregate still validates the local member-kind invariant
 * (its type must allow {@code RESOURCE}) against the type's explicitly-loaded allowed kinds.
 */
@Service
@RequiredArgsConstructor
public class ResourceGraphService {

    private final ResourceRepository resources;
    private final ResourceEdgeRepository edges;
    private final ResourceTypeAllowedMemberRepository allowedMembers;
    private final ResourceScope scope;

    /** Attaches {@code childId} under {@code parentId}, rejecting edges that would create a cycle. */
    @Transactional
    public void attachChild(UUID parentId, UUID childId) {
        // Serialize edge mutations: the cycle check below is check-then-act, so two concurrent
        // attaches could otherwise each pass it and insert the two halves of a cycle. Correctness
        // assumes READ COMMITTED (the default): reaches() re-snapshots per statement, so after the lock
        // it sees the winner's committed edge. Under REPEATABLE READ/SERIALIZABLE the tx snapshot could
        // predate the lock — re-read the edges after locking before raising isolation.
        resources.lockEdgeMutations();
        Resource parent = resources.findById(parentId)
                .orElseThrow(() -> new NotFoundException("Resource not found."));
        resources.findById(childId)
                .orElseThrow(() -> new NotFoundException("Resource not found."));

        // Reachability covers self-loops too (a node reaches itself), so a parent==child edge is a cycle.
        if (scope.reaches(childId, parentId)) {
            throw new ConflictException("Attaching this child would create a cycle in the resource graph.");
        }
        parent.requireCanNest(allowedMemberTypes(parent.getType().getId()));
        edges.save(new ResourceEdge(parentId, childId)); // (parent, child) PK → re-attach is idempotent
    }

    @Transactional
    public void detachChild(UUID parentId, UUID childId) {
        resources.findById(parentId)
                .orElseThrow(() -> new NotFoundException("Resource not found."));
        resources.findById(childId)
                .orElseThrow(() -> new NotFoundException("Resource not found."));
        edges.deleteByParentIdAndChildId(parentId, childId); // no-op when the edge is absent
    }

    private Set<MemberType> allowedMemberTypes(UUID typeId) {
        return allowedMembers.findByTypeId(typeId).stream()
                .map(ResourceTypeAllowedMember::getMemberType)
                .collect(Collectors.toSet());
    }
}
