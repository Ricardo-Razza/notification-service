package dev.ricardo.notification.infrastructure.messaging;

import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.DirectExchange;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.QueueBuilder;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.amqp.support.converter.JacksonJsonMessageConverter;
import org.springframework.amqp.support.converter.MessageConverter;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class RabbitConfig {

    @Value("${app.rabbitmq.exchange:notification.exchange}")
    private String exchangeName;

    @Value("${app.rabbitmq.queue:notification.queue}")
    private String queueName;

    @Value("${app.rabbitmq.routing-key:notification.send}")
    private String routingKey;

    @Value("${app.rabbitmq.retry-1-ttl:10000}")
    private int retry1Ttl;

    @Value("${app.rabbitmq.retry-2-ttl:30000}")
    private int retry2Ttl;

    @Value("${app.rabbitmq.retry-3-ttl:120000}")
    private int retry3Ttl;

    @Value("${app.rabbitmq.retry-1-routing-key:notification.retry.10s}")
    private String retry1RoutingKey;

    @Value("${app.rabbitmq.retry-2-routing-key:notification.retry.30s}")
    private String retry2RoutingKey;

    @Value("${app.rabbitmq.retry-3-routing-key:notification.retry.2m}")
    private String retry3RoutingKey;

    @Value("${app.rabbitmq.dlq:notification.dlq}")
    private String dlqName;

    @Value("${app.rabbitmq.dlq-routing-key:notification.dlq}")
    private String dlqRoutingKey;

    @Bean
    public DirectExchange notificationExchange() {
        return new DirectExchange(exchangeName, true, false);
    }

    @Bean
    public Queue notificationQueue() {
        return QueueBuilder.durable(queueName).build();
    }

    @Bean
    public Binding notificationBinding(Queue notificationQueue, DirectExchange notificationExchange) {
        return BindingBuilder.bind(notificationQueue).to(notificationExchange).with(routingKey);
    }

    @Bean
    public Queue retry10sQueue() {
        return QueueBuilder.durable(retry1RoutingKey)
                .ttl(retry1Ttl)
                .deadLetterExchange(exchangeName)
                .deadLetterRoutingKey(routingKey)
                .build();
    }

    @Bean
    public Binding retry10sBinding(Queue retry10sQueue, DirectExchange notificationExchange) {
        return BindingBuilder.bind(retry10sQueue).to(notificationExchange).with(retry1RoutingKey);
    }

    @Bean
    public Queue retry30sQueue() {
        return QueueBuilder.durable(retry2RoutingKey)
                .ttl(retry2Ttl)
                .deadLetterExchange(exchangeName)
                .deadLetterRoutingKey(routingKey)
                .build();
    }

    @Bean
    public Binding retry30sBinding(Queue retry30sQueue, DirectExchange notificationExchange) {
        return BindingBuilder.bind(retry30sQueue).to(notificationExchange).with(retry2RoutingKey);
    }

    @Bean
    public Queue retry2mQueue() {
        return QueueBuilder.durable(retry3RoutingKey)
                .ttl(retry3Ttl)
                .deadLetterExchange(exchangeName)
                .deadLetterRoutingKey(routingKey)
                .build();
    }

    @Bean
    public Binding retry2mBinding(Queue retry2mQueue, DirectExchange notificationExchange) {
        return BindingBuilder.bind(retry2mQueue).to(notificationExchange).with(retry3RoutingKey);
    }

    @Bean
    public Queue dlqQueue() {
        return QueueBuilder.durable(dlqName).build();
    }

    @Bean
    public Binding dlqBinding(Queue dlqQueue, DirectExchange notificationExchange) {
        return BindingBuilder.bind(dlqQueue).to(notificationExchange).with(dlqRoutingKey);
    }

    @Bean
    public MessageConverter messageConverter() {
        return new JacksonJsonMessageConverter("*");
    }

    @Bean
    public RabbitTemplate rabbitTemplate(ConnectionFactory connectionFactory, MessageConverter messageConverter) {
        RabbitTemplate template = new RabbitTemplate(connectionFactory);
        template.setMessageConverter(messageConverter);
        template.setMandatory(true);
        return template;
    }

    @Bean
    public org.springframework.amqp.rabbit.config.SimpleRabbitListenerContainerFactory rabbitListenerContainerFactory(
            ConnectionFactory connectionFactory,
            @Value("${app.rabbitmq.concurrency:2}") int concurrency,
            @Value("${app.rabbitmq.max-concurrency:5}") int maxConcurrency,
            @Value("${spring.rabbitmq.listener.simple.auto-startup:true}") boolean autoStartup) {
        org.springframework.amqp.rabbit.config.SimpleRabbitListenerContainerFactory factory =
                new org.springframework.amqp.rabbit.config.SimpleRabbitListenerContainerFactory();
        factory.setConnectionFactory(connectionFactory);
        factory.setAcknowledgeMode(org.springframework.amqp.core.AcknowledgeMode.MANUAL);
        factory.setConcurrentConsumers(concurrency);
        factory.setMaxConcurrentConsumers(maxConcurrency);
        factory.setAutoStartup(autoStartup);
        factory.setMessageConverter(new org.springframework.amqp.support.converter.SimpleMessageConverter());
        return factory;
    }
}
