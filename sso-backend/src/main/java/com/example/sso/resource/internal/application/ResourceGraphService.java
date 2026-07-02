package com.example.sso.resource.internal.application;

import com.example.sso.resource.internal.domain.Resource;
import com.example.sso.resource.internal.domain.ResourceRepository;
import com.example.sso.shared.error.ConflictException;
import com.example.sso.shared.error.NotFoundException;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Graph mutations that need GLOBAL invariants: an edge may only be inserted when the child does not
 * already reach the parent (which would close a cycle). The {@link Resource} aggregate enforces the
 * local invariants (self-loop, member kinds); this service runs the reachability check first, in the
 * same transaction as the insert.
 */
@Service
@RequiredArgsConstructor
public class ResourceGraphService {

    private final ResourceRepository resources;
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
        Resource child = resources.findById(childId)
                .orElseThrow(() -> new NotFoundException("Resource not found."));

        if (scope.reaches(childId, parentId)) {
            throw new ConflictException("Attaching this child would create a cycle in the resource graph.");
        }
        parent.addChild(child);
    }

    @Transactional
    public void detachChild(UUID parentId, UUID childId) {
        Resource parent = resources.findById(parentId)
                .orElseThrow(() -> new NotFoundException("Resource not found."));
        Resource child = resources.findById(childId)
                .orElseThrow(() -> new NotFoundException("Resource not found."));
        parent.removeChild(child);
    }
}
