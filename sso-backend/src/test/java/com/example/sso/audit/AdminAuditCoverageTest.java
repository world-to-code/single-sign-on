package com.example.sso.audit;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.core.type.filter.AnnotationTypeFilter;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import com.example.sso.shared.security.RequirePermission;
import com.example.sso.user.rbac.Permissions;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Every state-changing admin endpoint leaves an audit trail — checked structurally, so the NEXT one cannot be
 * added without a trail.
 *
 * <p>This exists because the gap was never a decision, it was an omission, and it kept happening: the mail
 * relay, the SMS gateway and the email templates were each found unaudited one at a time, and the profile
 * screen had FIVE write endpoints of which exactly one was marked. A reviewer cannot hold that list in their
 * head, and a per-controller test only covers the controllers somebody remembered to write a test for.
 *
 * <p>An endpoint is covered by carrying {@link Audited}, or by appearing below with a reason. The exemptions
 * are deliberately per-METHOD rather than per-controller wherever a controller is mixed, so marking one
 * handler can never silently vouch for its neighbours.
 */
class AdminAuditCoverageTest {

    /**
     * Controllers whose every mutation is audited in the SERVICE layer instead, with a richer record than an
     * interceptor could build (the arrangement {@link Audited} documents). Marking them too would double-log.
     *
     * <p>Each names WHERE its trail is written, and {@link #everyServiceLayerExemptionNamesARealAuditor()}
     * checks that collaborator actually holds an audit writer — so adding a controller here costs more than
     * typing its name, which is the whole risk with an allowlist.
     */
    private static final Map<String, String> SERVICE_LAYER_AUDITED = Map.of(
            "AdminUserController", "com.example.sso.admin.internal.user.application.UserAdminService",
            "AdminRoleController", "com.example.sso.admin.internal.role.application.RoleAdminService",
            "AdminGroupController", "com.example.sso.admin.internal.group.application.GroupAdminService",
            "AdminOrganizationController",
            "com.example.sso.admin.internal.organization.application.OrganizationAdminService",
            "AdminMappingRuleController", "com.example.sso.mapping.internal.application.MappingRuleServiceImpl",
            "AdminDenyController", "com.example.sso.admin.internal.deny.application.DenyAdminService",
            "AdminFederatedIdentityController",
            "com.example.sso.federation.internal.application.FederatedIdentityAdminServiceImpl",
            "ResponseController", "com.example.sso.response.internal.application.ResponseActions");

    /** Individual handlers that change nothing, or whose effect is audited at a finer grain than the request. */
    private static final Map<String, String> EXEMPT = Map.of(
            "EmailTemplateController#preview", "renders unsaved content with sample data; persists nothing",
            "ProfileAdminController#previewCsvImport", "parses an upload to show a plan; persists nothing",
            "ProfileAdminController#importCsv",
            "each created user is audited individually by UserProvisioningService — finer than one row per file");

    @Test
    void everyAdminWriteEndpointIsAudited() {
        List<String> unaudited = new ArrayList<>();

        for (Class<?> controller : adminControllers()) {
            if (SERVICE_LAYER_AUDITED.containsKey(controller.getSimpleName())) {
                continue;
            }
            for (Method method : controller.getDeclaredMethods()) {
                if (!isWrite(method)) {
                    continue;
                }
                String id = controller.getSimpleName() + "#" + method.getName();
                if (!EXEMPT.containsKey(id) && !method.isAnnotationPresent(Audited.class)) {
                    unaudited.add(id);
                }
            }
        }

        assertThat(unaudited)
                .as("admin writes with no audit trail — mark them @Audited, or exempt them here WITH A REASON")
                .isEmpty();
    }

    /** An exemption that stops being true is worse than none: it vouches for an endpoint nobody rechecked. */
    @Test
    void everyExemptionStillNamesAnExistingEndpoint() {
        List<String> stale = new ArrayList<>(EXEMPT.keySet());
        for (Class<?> controller : adminControllers()) {
            for (Method method : controller.getDeclaredMethods()) {
                stale.remove(controller.getSimpleName() + "#" + method.getName());
            }
        }

        assertThat(stale).as("exemptions naming an endpoint that no longer exists").isEmpty();
    }

    @Test
    void everyServiceLayerAuditedControllerStillExists() {
        List<String> stale = new ArrayList<>(SERVICE_LAYER_AUDITED.keySet());
        adminControllers().forEach(controller -> stale.remove(controller.getSimpleName()));

        assertThat(stale).as("allowlisted controllers that no longer exist").isEmpty();
    }

    /**
     * The allowlist claims a trail exists somewhere else. This is what stops that claim from being free: the
     * named collaborator must hold an audit writer, so a controller cannot be exempted by assertion alone.
     */
    @Test
    void everyServiceLayerExemptionNamesARealAuditor() {
        for (Map.Entry<String, String> exemption : SERVICE_LAYER_AUDITED.entrySet()) {
            Class<?> auditor = resolve(exemption.getValue());
            List<String> collaborators = Arrays.stream(auditor.getDeclaredFields())
                    .map(field -> field.getType().getSimpleName())
                    .toList();
            assertThat(collaborators)
                    .as("%s vouches for %s, so it must actually write audit records",
                            auditor.getSimpleName(), exemption.getKey())
                    .containsAnyOf("AdminAuditLogger", "AuditService");
        }
    }

    private boolean isWrite(Method method) {
        return method.isAnnotationPresent(PostMapping.class)
                || method.isAnnotationPresent(PutMapping.class)
                || method.isAnnotationPresent(DeleteMapping.class)
                || method.isAnnotationPresent(PatchMapping.class);
    }

    /** Every {@code @RestController} mapped under {@code /api/admin} — the paths the audit interceptor runs on. */
    /**
     * A platform-tier action must SAY so, or it is filed under whichever tenant the caller was drilled into —
     * visible to an admin who may not read it, and absent from the platform feed where it would be reviewed.
     *
     * <p>Checked structurally because the two facts live in different annotations on the same method and
     * nothing otherwise keeps them together: {@code @RequirePermission} decides who may call it, and
     * {@code @Audited(platform = true)} decides whose trail it lands in. The first platform-only audited
     * endpoint got this wrong, and it was the audit collector's own configuration.
     */
    @Test
    void anAuditedEndpointGatedByAPlatformOnlyPermissionIsRecordedAtThePlatformTier() {
        List<String> misfiled = new ArrayList<>();
        for (Class<?> controller : adminControllers()) {
            for (Method method : controller.getDeclaredMethods()) {
                Audited audited = AnnotatedElementUtils.findMergedAnnotation(method, Audited.class);
                RequirePermission permission =
                        AnnotatedElementUtils.findMergedAnnotation(method, RequirePermission.class);
                if (audited == null || permission == null) {
                    continue;
                }
                if (Permissions.PLATFORM.contains(permission.value()) && !audited.platform()) {
                    misfiled.add(controller.getSimpleName() + "#" + method.getName()
                            + " (" + permission.value() + ")");
                }
            }
        }

        assertThat(misfiled)
                .as("platform-only endpoints whose audit row would be stamped with the drilled-in tenant")
                .isEmpty();
    }

    private List<Class<?>> adminControllers() {
        ClassPathScanningCandidateComponentProvider scanner =
                new ClassPathScanningCandidateComponentProvider(false);
        scanner.addIncludeFilter(new AnnotationTypeFilter(RestController.class));

        List<Class<?>> controllers = new ArrayList<>();
        for (BeanDefinition definition : scanner.findCandidateComponents("com.example.sso")) {
            Class<?> type = resolve(definition.getBeanClassName());
            RequestMapping mapping = AnnotatedElementUtils.findMergedAnnotation(type, RequestMapping.class);
            if (mapping != null && mapping.value().length > 0 && isPrivilegedWriteSurface(mapping.value()[0])) {
                controllers.add(type);
            }
        }
        // A scan that silently matched nothing would make every assertion here pass vacuously.
        assertThat(controllers).as("privileged-write controllers found by the scan").isNotEmpty();
        return controllers;
    }

    /**
     * The surfaces whose writes must all be audited: the admin console's, and the machine response API's.
     *
     * <p>The second was added with the response API and is not a courtesy. A machine can end sessions and
     * constrain accounts without a person in the loop, so an unaudited verb there is an action nobody was
     * ever in a position to notice — strictly worse than the same gap on a screen somebody was looking at.
     */
    private boolean isPrivilegedWriteSurface(String path) {
        return path.startsWith("/api/admin") || path.startsWith("/api/response");
    }

    private Class<?> resolve(String className) {
        try {
            return Class.forName(className);
        } catch (ClassNotFoundException e) {
            throw new AssertionError("scanned a class that cannot be loaded: " + className, e);
        }
    }
}
