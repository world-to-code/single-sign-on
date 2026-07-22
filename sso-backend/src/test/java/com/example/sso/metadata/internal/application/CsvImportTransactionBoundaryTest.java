package com.example.sso.metadata.internal.application;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.stereotype.Component;
import com.example.sso.metadata.CsvPlannedUser;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartRequest;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The plan's transaction must live on a bean the write path cannot bypass.
 *
 * <p>{@code apply} derives its plan by calling {@code preview} on its own instance. That is a self invocation,
 * so the proxy is not involved and any {@code @Transactional} declared on {@code preview} applies to the
 * preview endpoint and silently does nothing on the path that writes. The plan's reads then span several
 * transactions, and a lazy access added later would fail only when applying — the half of the feature nobody
 * exercises by hand.
 *
 * <p>Structural rather than behavioural on purpose: the defect is invisible at runtime until the day something
 * depends on the boundary, so what is worth pinning is WHERE the annotation lives.
 */
class CsvImportTransactionBoundaryTest {

    private Method previewMethod() throws NoSuchMethodException {
        return CsvImportServiceImpl.class.getDeclaredMethod("preview", UUID.class, MultipartRequest.class);
    }

    private Method planMethod() throws NoSuchMethodException {
        return CsvImportPlanner.class.getDeclaredMethod("plan", UUID.class, String.class);
    }

    @Test
    void theSelfInvokedPreviewDeclaresNoTransactionOfItsOwn() throws NoSuchMethodException {
        assertThat(previewMethod().isAnnotationPresent(Transactional.class))
                .as("apply() calls preview() on the same bean, so a transaction here is bypassed on the write "
                        + "path — it belongs on CsvImportPlanner, which is a separate bean")
                .isFalse();
    }

    @Test
    void thePlannerOwnsTheReadOnlyTransaction() throws NoSuchMethodException {
        Transactional transactional = planMethod().getAnnotation(Transactional.class);

        assertThat(transactional).isNotNull();
        assertThat(transactional.readOnly()).isTrue();
    }

    /**
     * The boundary only exists if Spring proxies the bean that declares it.
     *
     * <p>Asserted because the annotation assertions above cannot see this: remove {@code @Component} from the
     * planner, or construct it with {@code new} instead of injecting it, and they stay green while the
     * transaction disappears — which is the defect they exist to prevent, restored.
     */
    @Test
    void thePlannerIsASpringBeanSoTheBoundaryIsReallyApplied() throws NoSuchMethodException {
        assertThat(CsvImportPlanner.class.isAnnotationPresent(Component.class))
                .as("a plain object has no proxy, so its @Transactional does nothing")
                .isTrue();
        assertThat(Modifier.isPublic(planMethod().getModifiers()))
                .as("Spring's proxying of non-public methods is version-dependent; do not rely on it here")
                .isTrue();
    }

    /**
     * And {@code apply} must NOT be transactional. Wrapping the row loop turns the per-row
     * DataIntegrityViolationException catch into an UnexpectedRollbackException over the whole import — the
     * poisoned-transaction failure this repository has already shipped once.
     */
    @Test
    void theImportItselfDeclaresNoTransaction() throws NoSuchMethodException {
        Method apply = CsvImportServiceImpl.class.getDeclaredMethod("apply", UUID.class, MultipartRequest.class);

        assertThat(apply.isAnnotationPresent(Transactional.class)).isFalse();
    }

    /**
     * The per-row creator is REQUIRES_NEW, and that propagation is load-bearing rather than decorative.
     *
     * <p>A review guessed this was unpinned — that {@code REQUIRED} would pass everything, since {@code apply}
     * is itself non-transactional so each row already opens a fresh transaction. It said it had not read the
     * concurrency IT, and that guess is what the IT disproves: its deterministic case runs a competing import
     * INSIDE the first creator call, so under {@code REQUIRED} the two share one transaction, the violation
     * rolls both back, and zero accounts survive instead of one. Switching the propagation there fails
     * {@code CsvImportConcurrencyIT.aUsernameTakenBetweenPlanAndWriteFailsThatRowAlone}.
     *
     * <p>This asserts the declaration; that IT asserts the behaviour it produces. Both, because the annotation
     * is easy to drop and the reason it is there is easy to forget.
     */
    @Test
    void theCreatorRunsEachRowInItsOwnTransaction() throws ReflectiveOperationException {
        // Loaded by name: the adapter is package-private in the admin module, so it is not on the compile path
        // here, but the propagation it declares is exactly what this test exists to hold.
        Class<?> adapter = Class.forName(
                "com.example.sso.admin.internal.user.application.CsvUserCreatorAdapter");
        Method create = adapter.getDeclaredMethod("create", CsvPlannedUser.class, UUID.class);

        Transactional tx = create.getAnnotation(Transactional.class);
        assertThat(tx).isNotNull();
        assertThat(tx.propagation()).isEqualTo(Propagation.REQUIRES_NEW);
    }
}
