package com.pubnub.kafka.connect;

import com.pubnub.api.PNConfiguration;
import com.pubnub.api.PubNub;
import com.pubnub.api.UserId;
import org.apache.kafka.common.config.types.Password;
import org.apache.kafka.connect.sink.ErrantRecordReporter;
import org.apache.kafka.connect.sink.SinkRecord;
import org.apache.kafka.connect.sink.SinkTask;
import org.apache.kafka.connect.sink.SinkTaskContext;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.VisibleForTesting;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Collection;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

public class PubNubKafkaSinkConnectorTask extends SinkTask {

    public PubNubKafkaSinkConnectorTask() {}

    @VisibleForTesting
    PubNubKafkaSinkConnectorTask(@Nullable PubNub pubnub, @Nullable PubNubKafkaRouter router) {
        this.pubnub = pubnub;
        if (router != null) {
            this.router = router;
        }
    }

    private final Logger log = LoggerFactory.getLogger(this.toString());

    @VisibleForTesting
    @Nullable
    PubNub getPubnub() {
        return pubnub;
    }

    @VisibleForTesting
    PubNubKafkaRouter getRouter() {
        return router;
    }

    @Nullable
    private PubNub pubnub;

    @Nullable
    private ErrantRecordReporter reporter;

    private PubNubKafkaRouter router = record -> new PubNubKafkaRouter.ChannelAndMessage(record.topic(), record.value());

    @Override
    public String version() {
        return PropertiesUtil.getConnectorVersion();
    }

    CopyOnWriteArrayList<Exception> errorsEncountered = new CopyOnWriteArrayList<>();

    @Override
    public void initialize(SinkTaskContext context) {
        super.initialize(context);
        reporter = context.errantRecordReporter();
    }

    @Override
    public void start(Map<String, String> properties) {
        PubNubKafkaConnectorConfig config = new PubNubKafkaConnectorConfig(properties);
        try {
            final UserId userId = new UserId(config.getString("pubnub.user_id"));
            String publishKey = config.getString("pubnub.publish_key");
            String subscribeKey = config.getString("pubnub.subscribe_key");
            Password secretKey = config.getPassword("pubnub.secret_key");
            Class<?> routerClass = config.getClass("pubnub.router");
            if (routerClass != null) {
                router = (PubNubKafkaRouter) routerClass.getConstructor().newInstance();
            }
            PNConfiguration pnConfiguration = new PNConfiguration(userId);
            pnConfiguration.setPublishKey(publishKey);
            pnConfiguration.setSubscribeKey(subscribeKey);
            pnConfiguration.setSecretKey(secretKey.value());
            // TODO do we want to employ a retry strategy?
            pubnub = new PubNub(pnConfiguration);
        } catch (Exception exception) {
            log.error("Unable to initialize PubNub Connection", exception);
            throw new IllegalStateException(exception);
        }
    }

    private void publish(SinkRecord record, PubNubKafkaRouter router) {
        if (pubnub != null) {
            PubNubKafkaRouter.ChannelAndMessage channelAndMessage = router.route(record);
            pubnub.publish()
                    .channel(channelAndMessage.getChannel())
                    .message(channelAndMessage.getMessage())
                    .async((result, publishStatus) -> {
                        if (publishStatus.isError()) {
                            log.error("⛔️ Channel: '{}' Message {}: '{}' Publishing to PubNub Failed!", record.topic(), record.kafkaOffset(), record.value());
                            if (reporter != null) {
                                reporter.report(record, publishStatus.getErrorData().getThrowable());
                            }
                        } else {
                            log.info("✅ Channel: '{}' Message {}: '{}' Published to PubNub Successfully!", record.topic(), record.kafkaOffset(), record.value());
                        }
                    });
        }
    }

    @Override
    public void put(Collection<SinkRecord> records) {
        for (final SinkRecord record : records) {
            publish(record, router);
        }
    }

    @Override
    public void stop() {
        log.info("Stopping PubNub Sink Connector Task");
        if (pubnub != null) {
            pubnub.destroy();
        }
    }
}

