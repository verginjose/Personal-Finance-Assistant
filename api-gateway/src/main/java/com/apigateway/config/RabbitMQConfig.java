package com.apigateway.config;

import org.springframework.amqp.core.*;
import org.springframework.amqp.support.converter.Jackson2JsonMessageConverter;
import org.springframework.amqp.support.converter.MessageConverter;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class RabbitMQConfig {

    public static final String EXCHANGE_NAME = "finance-events";
    public static final String OCR_QUEUE = "ocr-jobs";

    /**
     * Parking lot for OCR jobs whose result could not be delivered to
     * command-service. The ocr-service publishes there and acks the original
     * message only after the park succeeded; the gateway declares the same
     * exchange, queue and binding so the topology exists from either side.
     */
    public static final String DEAD_LETTER_EXCHANGE = "finance-events-dlx";
    public static final String DEAD_LETTER_QUEUE = "ocr-jobs-dead";
    public static final String DEAD_LETTER_ROUTING_KEY = "ocr.job.dead";

    @Bean
    public TopicExchange financeExchange() {
        return new TopicExchange(EXCHANGE_NAME);
    }

    @Bean
    public Queue ocrQueue() {
        return new Queue(OCR_QUEUE, true);
    }

    @Bean
    public Binding bindingOcrQueue(@Qualifier("ocrQueue") Queue ocrQueue, TopicExchange financeExchange) {
        return BindingBuilder.bind(ocrQueue).to(financeExchange).with("ocr.job");
    }

    @Bean
    public DirectExchange ocrDeadLetterExchange() {
        return new DirectExchange(DEAD_LETTER_EXCHANGE);
    }

    @Bean
    public Queue ocrDeadQueue() {
        return new Queue(DEAD_LETTER_QUEUE, true);
    }

    @Bean
    public Binding bindingOcrDeadQueue(@Qualifier("ocrDeadQueue") Queue ocrDeadQueue,
                                       DirectExchange ocrDeadLetterExchange) {
        return BindingBuilder.bind(ocrDeadQueue).to(ocrDeadLetterExchange).with(DEAD_LETTER_ROUTING_KEY);
    }

    @Bean
    public MessageConverter jsonMessageConverter() {
        return new Jackson2JsonMessageConverter();
    }
}
