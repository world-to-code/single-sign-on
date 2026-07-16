package com.example.sso.mapping;

import com.example.sso.mapping.internal.application.AttributeChangeListener;
import com.example.sso.mapping.internal.application.MembershipChangeListener;
import com.example.sso.metadata.EntityAttributeChangedEvent;
import com.example.sso.support.AbstractIntegrationTest;
import com.example.sso.user.group.GroupMembershipChangedEvent;
import java.util.concurrent.ThreadPoolExecutor;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;
import org.springframework.scheduling.annotation.Async;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The auto-mapping reconcile runs on a DEDICATED BOUNDED pool with caller-runs backpressure — not the shared,
 * effectively-unbounded default {@code applicationTaskExecutor} — so a burst of attribute changes can neither
 * grow memory without limit nor starve onboarding/provisioning. Guards both the bean's shape and the listener's
 * wiring to it.
 */
class MappingReconcileExecutorConfigTest extends AbstractIntegrationTest {

    @Autowired ApplicationContext context;

    @Test
    void theReconcileExecutorIsBoundedWithCallerRunsBackpressure() {
        ThreadPoolTaskExecutor executor = context.getBean("mappingReconcileExecutor", ThreadPoolTaskExecutor.class);
        ThreadPoolExecutor pool = executor.getThreadPoolExecutor();

        assertThat(pool.getQueue().remainingCapacity()).isEqualTo(500); // bounded queue, not Integer.MAX_VALUE
        assertThat(pool.getRejectedExecutionHandler()).isInstanceOf(ThreadPoolExecutor.CallerRunsPolicy.class);
        assertThat(executor.getCorePoolSize()).isEqualTo(4);
        assertThat(executor.getMaxPoolSize()).isEqualTo(8);
    }

    @Test
    void theAttributeListenerRunsOnTheDedicatedReconcileExecutor() throws NoSuchMethodException {
        assertRunsOnReconcileExecutor(AttributeChangeListener.class
                .getMethod("onAttributeChanged", EntityAttributeChangedEvent.class).getAnnotation(Async.class));
    }

    @Test
    void theMembershipListenerRunsOnTheDedicatedReconcileExecutor() throws NoSuchMethodException {
        assertRunsOnReconcileExecutor(MembershipChangeListener.class
                .getMethod("onMembershipChanged", GroupMembershipChangedEvent.class).getAnnotation(Async.class));
    }

    private void assertRunsOnReconcileExecutor(Async async) {
        assertThat(async).isNotNull();
        assertThat(async.value()).isEqualTo("mappingReconcileExecutor"); // not the shared default pool
    }
}
