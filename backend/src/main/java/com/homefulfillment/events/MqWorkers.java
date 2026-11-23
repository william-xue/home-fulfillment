package com.homefulfillment.events;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PreDestroy;
import org.apache.rocketmq.client.consumer.DefaultMQPushConsumer;
import org.apache.rocketmq.client.consumer.listener.ConsumeConcurrentlyStatus;
import org.apache.rocketmq.client.consumer.listener.MessageListenerConcurrently;
import org.apache.rocketmq.client.producer.DefaultMQProducer;
import org.apache.rocketmq.client.producer.SendStatus;
import org.apache.rocketmq.common.consumer.ConsumeFromWhere;
import org.apache.rocketmq.common.message.Message;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;

@Component
@EnableScheduling
@ConditionalOnProperty(name="hf.events.enabled",havingValue="true")
class MqWorkers {
    private static final Logger log=LoggerFactory.getLogger(MqWorkers.class);
    private final OutboxStore outbox;
    private final DispatchConsumer handler;
    private final ObjectMapper json;
    private final String nameserver;
    private DefaultMQProducer producer;
    private DefaultMQPushConsumer consumer;
    private boolean reportedConsumerFailure;

    MqWorkers(OutboxStore outbox,DispatchConsumer handler,ObjectMapper json,@Value("${hf.events.nameserver}") String nameserver) {
        this.outbox=outbox; this.handler=handler; this.json=json; this.nameserver=nameserver;
    }

    @Scheduled(fixedDelay=500)
    public void publish() {
        outbox.claim().ifPresent(claim -> {
            try {
                var msg=new Message(DispatchEvent.TOPIC,claim.eventType(),claim.id(),claim.payload().getBytes(StandardCharsets.UTF_8));
                var result=producer().send(msg,1500);
                if (result.getSendStatus()!=SendStatus.SEND_OK) {
                    outbox.fail(claim,"Broker 未确认：" + result.getSendStatus());
                    return;
                }
            } catch (Exception error) {
                outbox.fail(claim,"Broker 未确认：" + error.getClass().getSimpleName());
                return;
            }
            // Not coupled to the send: if this update fails, the lease expires and delivery may repeat.
            outbox.published(claim);
        });
    }

    private DefaultMQProducer producer() throws Exception {
        if (producer!=null) return producer;
        var candidate=new DefaultMQProducer("hf-outbox-publisher-v1");
        candidate.setNamesrvAddr(nameserver);
        candidate.setInstanceName("hf-outbox");
        candidate.setVipChannelEnabled(false);
        candidate.setRetryTimesWhenSendFailed(0);
        candidate.setPollNameServerInterval(2000);
        candidate.start();
        producer=candidate;
        return producer;
    }

    @Scheduled(fixedDelay=3000)
    public void ensureConsumer() {
        if (consumer!=null) return;
        var candidate=new DefaultMQPushConsumer(DispatchConsumer.GROUP);
        candidate.setNamesrvAddr(nameserver);
        candidate.setInstanceName("hf-inbox");
        candidate.setVipChannelEnabled(false);
        candidate.setPollNameServerInterval(2000);
        candidate.setConsumeFromWhere(ConsumeFromWhere.CONSUME_FROM_FIRST_OFFSET);
        candidate.setConsumeMessageBatchMaxSize(1);
        candidate.setConsumeThreadMin(1);
        candidate.setConsumeThreadMax(2);
        candidate.setMaxReconsumeTimes(4);
        candidate.registerMessageListener((MessageListenerConcurrently) (messages,context) -> {
            for (var message : messages) {
                try {
                    if (!handler.apply(json.readValue(message.getBody(),DispatchEvent.class))) {
                        log.info("Shipment consumer ignored replay eventKey={}",message.getKeys());
                    }
                } catch (Exception error) {
                    log.warn("Shipment consumer failed eventKey={} reconsumeTimes={} error={}",
                            message.getKeys(),message.getReconsumeTimes(),error.getClass().getSimpleName());
                    return ConsumeConcurrentlyStatus.RECONSUME_LATER;
                }
            }
            return ConsumeConcurrentlyStatus.CONSUME_SUCCESS;
        });
        try {
            candidate.subscribe(DispatchEvent.TOPIC,DispatchEvent.TYPE);
            candidate.start();
            consumer=candidate;
        } catch (Exception error) {
            candidate.shutdown();
            if (!reportedConsumerFailure) {
                log.warn("Shipment consumer not ready; will retry: {}",error.getClass().getSimpleName());
                reportedConsumerFailure=true;
            }
        }
    }

    @PreDestroy
    void close() {
        if (consumer!=null) consumer.shutdown();
        if (producer!=null) producer.shutdown();
    }
}
