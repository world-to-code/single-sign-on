package com.example.sso.session.internal.application;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.listener.ChannelTopic;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;

/** Subscribes {@link SessionCacheRedisBridge} to the cross-node cache-invalidation channel. */
@Configuration
class SessionCacheRedisConfig {

    @Bean
    RedisMessageListenerContainer sessionCacheListenerContainer(RedisConnectionFactory connectionFactory,
                                                               SessionCacheRedisBridge bridge) {
        RedisMessageListenerContainer container = new RedisMessageListenerContainer();
        container.setConnectionFactory(connectionFactory);
        container.addMessageListener(bridge, new ChannelTopic(SessionCacheRedisBridge.CHANNEL));
        return container;
    }
}
