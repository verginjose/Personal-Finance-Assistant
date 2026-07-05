package com.apigateway.config;

import org.springframework.amqp.core.*;
import org.springframework.amqp.support.converter.Jackson2JsonMessageConverter;
import org.springframework.amqp.support.converter.MessageConverter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class RabbitMQConfig {

    public static final String EXCHANGE_NAME = "finance-events";
    public static final String OCR_QUEUE = "ocr-jobs";

    @Bean
    public TopicExchange financeExchange() {
        return new TopicExchange(EXCHANGE_NAME);
    }

    @Bean
    public Queue ocrQueue() {
        return new Queue(OCR_QUEUE, true);
    }

    @Bean
    public Binding bindingOcrQueue(Queue ocrQueue, TopicExchange financeExchange) {
        return BindingBuilder.bind(ocrQueue).to(financeExchange).with("ocr.job");
    }

    @Bean
    public MessageConverter jsonMessageConverter() {
        return new Jackson2JsonMessageConverter();
    }
}
