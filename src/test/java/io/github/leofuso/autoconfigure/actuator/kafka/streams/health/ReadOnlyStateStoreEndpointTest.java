package io.github.leofuso.autoconfigure.actuator.kafka.streams.health;

import java.time.Duration;
import java.util.Map;
import java.util.Properties;
import java.util.UUID;

import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.serialization.Serdes;
import org.apache.kafka.common.utils.Bytes;
import org.apache.kafka.streams.KeyValue;
import org.apache.kafka.streams.StreamsBuilder;
import org.apache.kafka.streams.kstream.Consumed;
import org.apache.kafka.streams.kstream.Grouped;
import org.apache.kafka.streams.kstream.Materialized;
import org.apache.kafka.streams.kstream.Named;
import org.apache.kafka.streams.kstream.Produced;
import org.apache.kafka.streams.kstream.Transformer;
import org.apache.kafka.streams.processor.ProcessorContext;
import org.apache.kafka.streams.state.KeyValueStore;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.autoconfigure.jackson.JacksonAutoConfiguration;
import org.springframework.boot.autoconfigure.kafka.KafkaAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.support.ConversionServiceFactoryBean;
import org.springframework.kafka.test.EmbeddedKafkaBroker;
import org.springframework.kafka.test.context.EmbeddedKafka;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import io.github.leofuso.autoconfigure.actuator.kafka.streams.KStreamsSupplierAutoConfiguration;
import io.github.leofuso.autoconfigure.actuator.kafka.streams.health.setup.StreamBuilderFactoryConfiguration;
import io.github.leofuso.autoconfigure.actuator.kafka.streams.state.remote.endpoint.InteractiveQueryEndpointAutoConfiguration;
import io.github.leofuso.autoconfigure.actuator.kafka.streams.state.remote.endpoint.ReadOnlyStateStoreEndpoint;

import static io.github.leofuso.autoconfigure.actuator.kafka.streams.health.utils.KafkaStreamTestUtils.await;
import static io.github.leofuso.autoconfigure.actuator.kafka.streams.health.utils.KafkaStreamTestUtils.produce;
import static org.apache.kafka.common.serialization.Serdes.Integer;
import static org.apache.kafka.common.serialization.Serdes.Long;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link EnableAutoConfiguration Auto-configuration} test scenarios.
 */
@EmbeddedKafka(topics = {"join-in", "sum-in", "sum-out", "join-store-changelog", "sum-store-changelog"})
@TestMethodOrder(MethodOrderer.MethodName.class)
public class ReadOnlyStateStoreEndpointTest {

    private final EmbeddedKafkaBroker broker;

    ReadOnlyStateStoreEndpointTest(final EmbeddedKafkaBroker broker) {
        this.broker = broker;
    }

    private ApplicationContextRunner readonlystatestore(Boolean enabled, int port) {
        return readonlystatestore(enabled, port, new Properties());
    }

    @Test
    @DisplayName("Given enabled readonlystatestore, when App finishes starting, then bean should be found")
    void t0() {
        /* Given, When & Then */
        readonlystatestore(true, 0)
                .run(context -> assertThat(context).hasSingleBean(ReadOnlyStateStoreEndpoint.class));
    }

    @Test
    @DisplayName("Given disabled readonlystatestore, when App finishes starting, then bean should not be found")
    void t1() {
        /* Given, When & Then */
        readonlystatestore(false, 0)
                .run(context -> assertThat(context).doesNotHaveBean(ReadOnlyStateStoreEndpoint.class));
    }

    @Test
    @DisplayName("Given enabled readonlystatestore, with missing props [application.server], when App finishes starting, then bean should bot be found")
    void t2() {
        /* Given, When & Then */
        readonlystatestore(true, -1)
                .run(context -> assertThat(context).doesNotHaveBean(ReadOnlyStateStoreEndpoint.class));
    }

    @Test
    @DisplayName("Given a local state, with default key-serde when queried should return correct value")
    void t3() throws JsonProcessingException {
        /* Given */

        final String randomKey = "9daccdfc-c620-4eee-bd0b-88df8610c264";
        final ObjectMapper mapper = new ObjectMapper();
        final JsonNode expectedNode = mapper.readTree("{\"key\":\"9daccdfc-c620-4eee-bd0b-88df8610c264\", \"value\":123}");

        readonlystatestore(true, 19090)
                .run(context -> {


                    produce(
                            broker,
                            new ProducerRecord<>("join-in", randomKey, "1"),
                            new ProducerRecord<>("join-in", randomKey, "2"),
                            new ProducerRecord<>("join-in", randomKey, "3")
                    );

                    await(broker, Duration.ofSeconds(2), "join-store-changelog");

                    /* When */
                    final ReadOnlyStateStoreEndpoint endpoint = context.getBean(ReadOnlyStateStoreEndpoint.class);
                    final JsonNode actualNode = endpoint.find("join-store", randomKey, null);

                    /* Then */
                    assertThat(actualNode)
                            .isEqualTo(expectedNode);
                });
    }

    @Test
    @DisplayName(" Given a remote and local state, with default key-serde when queried then should return correct value")
    void t4() throws JsonProcessingException {
        /* Given */

        final String serverKey = "8daccdfc-c620-4eee-bd0b-88df8610c263";
        final String clientKey = "8daccdfc-c620-4eee-bd0b-88df8610c264";

        final ObjectMapper mapper = new ObjectMapper();
        final JsonNode expectedClientNode = mapper.readTree("{\"key\":\"8daccdfc-c620-4eee-bd0b-88df8610c263\", \"value\":\"abc\"}");
        final JsonNode expectedServerNode = mapper.readTree("{\"key\":\"8daccdfc-c620-4eee-bd0b-88df8610c264\", \"value\":123}");

        readonlystatestore(19099, new Properties())
                .run(serverContext -> {


                    readonlystatestore( 19199, new Properties())
                            .run(clientContext -> {

                                produce(
                                        broker,
                                        new ProducerRecord<>("join-in",clientKey, "1"),
                                        new ProducerRecord<>("join-in",clientKey, "2"),
                                        new ProducerRecord<>("join-in",clientKey, "3"),
                                        new ProducerRecord<>("join-in",serverKey, "a"),
                                        new ProducerRecord<>("join-in",serverKey, "b"),
                                        new ProducerRecord<>("join-in",serverKey, "c")
                                );
                                await(broker, Duration.ofSeconds(5), "join-store-changelog");

                                /* When */
                                final ReadOnlyStateStoreEndpoint clientEndpoint =
                                        clientContext.getBean(ReadOnlyStateStoreEndpoint.class);

                                final JsonNode actualClientNode = clientEndpoint.find(
                                        "join-store",
                                        serverKey,
                                        null
                                );

                                /* Then */
                                assertThat(actualClientNode)
                                        .isEqualTo(expectedClientNode);
                            });


                    /* When */
                    final ReadOnlyStateStoreEndpoint serverEndpoint =
                            serverContext.getBean(ReadOnlyStateStoreEndpoint.class);
                    final JsonNode actualServerNode = serverEndpoint.find("join-store", clientKey, null);


                    /* Then */
                    assertThat(actualServerNode)
                            .isEqualTo(expectedServerNode);
                });
    }

    @Test
    @DisplayName("Given a local state, with supported key-serde when queried then should return correct value")
    void t5() throws JsonProcessingException {
        /* Given */

        final ObjectMapper mapper = new ObjectMapper();
        final JsonNode expectedNode = mapper.readTree("{\"key\":\"25\", \"value\":6}");

        readonlystatestore(true, 19090)
                .run(context -> {

                    final String longKey = "25";
                    final String serdeClass = Serdes.LongSerde.class.getName();

                    produce(
                            broker,
                            new ProducerRecord<>("sum-in", longKey, "1"),
                            new ProducerRecord<>("sum-in", longKey, "2"),
                            new ProducerRecord<>("sum-in", longKey, "3")
                    );

                    await(broker, Duration.ofSeconds(2), "sum-store-changelog");

                    /* When */
                    final ReadOnlyStateStoreEndpoint endpoint = context.getBean(ReadOnlyStateStoreEndpoint.class);

                    final JsonNode actualNode = endpoint.find("sum-store", longKey, serdeClass);

                    /* Then */
                    assertThat(actualNode)
                            .isEqualTo(expectedNode);
                });
    }

    @Test
    @DisplayName("Given a local state, with supported key-serde when queried then should return correct value"
    )
    void t6() {
        /* Given */
        readonlystatestore(true, 19090)
                .run(context -> {

                    final String longKey = "25";
                    final String serdeClass = Serdes.LongSerde.class.getName();

                    produce(
                            broker,
                            new ProducerRecord<>("sum-in", longKey, "1"),
                            new ProducerRecord<>("sum-in", longKey, "2"),
                            new ProducerRecord<>("sum-in", longKey, "3")
                    );

                    await(broker, Duration.ofSeconds(2), "sum-store-changelog");

                    /* When */
                    final ReadOnlyStateStoreEndpoint endpoint = context.getBean(ReadOnlyStateStoreEndpoint.class);

                    final JsonNode jsonNode = endpoint.find("sum-store", longKey + "L", serdeClass);
                    ObjectMapper mapper = new ObjectMapper();
                    Map<String, Object> response = mapper.convertValue(jsonNode, new TypeReference<>(){});

                    /* Then */
                    assertThat(response)
                            .isNotEmpty()
                            .hasEntrySatisfying("message", s -> assertThat(s).asString().contains("NumberFormatException"));
                });
    }

    private ApplicationContextRunner readonlystatestore(Boolean enabled, int port, Properties additionalSerdes) {

        final String[] additionalProps = additionalSerdes.stringPropertyNames()
                                                         .toArray(String[]::new);
        return new ApplicationContextRunner()
                .withBean(ConversionServiceFactoryBean.class)
                .withPropertyValues(
                        "logging.level.org.apache.kafka=OFF",
                        "management.endpoints.web.exposure.include=" + (enabled ? "readonlystatestore" : ""),
                        "spring.kafka.bootstrap-servers=" + broker.getBrokersAsString(),
                        "spring.kafka.streams.application-id=" + UUID.randomUUID(),
                        "spring.kafka.streams.cleanup.on-startup=true",
                        "spring.kafka.streams.cleanup.on-stop=true",
                        "spring.kafka.streams.properties." +
                                (port >= 0 ? "application.server=localhost:" + port : "random.property=0"),
                        "spring.kafka.streams.properties.default.key.serde=org.apache.kafka.common.serialization.Serdes$StringSerde",
                        "spring.kafka.streams.properties.default.value.serde=org.apache.kafka.common.serialization.Serdes$StringSerde",
                        "spring.kafka.streams.properties.num.stream.threads=1",
                        "spring.kafka.streams.properties.state.dir=./local-state-store/" + port
                )
                .withPropertyValues(additionalProps)
                .withUserConfiguration(StreamBuilderFactoryConfiguration.class, KStreamApplication.class)
                .withConfiguration(
                        AutoConfigurations.of(
                                JacksonAutoConfiguration.class,
                                KStreamsSupplierAutoConfiguration.class,
                                KafkaAutoConfiguration.class,
                                InteractiveQueryEndpointAutoConfiguration.class
                        ));
    }

    private ApplicationContextRunner readonlystatestore(int port, Properties additionalSerdes) {

        final String[] additionalProps = additionalSerdes.stringPropertyNames()
                                                         .toArray(String[]::new);
        return new ApplicationContextRunner()
                .withBean(ConversionServiceFactoryBean.class)
                .withPropertyValues(
                        "logging.level.org.apache.kafka=OFF",
                        "management.endpoints.web.exposure.include=readonlystatestore",
                        "spring.kafka.bootstrap-servers=" + broker.getBrokersAsString(),
                        "spring.kafka.streams.application-id=stateful-application",
                        "spring.kafka.streams.cleanup.on-startup=true",
                        "spring.kafka.streams.properties.application.server=http://localhost:" + port,
                        "spring.kafka.streams.properties.default.key.serde=org.apache.kafka.common.serialization.Serdes$StringSerde",
                        "spring.kafka.streams.properties.default.value.serde=org.apache.kafka.common.serialization.Serdes$StringSerde",
                        "spring.kafka.streams.properties.num.stream.threads=1",
                        "spring.kafka.streams.properties.state.dir=./local-state-store/" + port
                )
                .withPropertyValues(additionalProps)
                .withUserConfiguration(StreamBuilderFactoryConfiguration.class, KStreamApplication.class)
                .withConfiguration(
                        AutoConfigurations.of(
                                JacksonAutoConfiguration.class,
                                KStreamsSupplierAutoConfiguration.class,
                                KafkaAutoConfiguration.class,
                                InteractiveQueryEndpointAutoConfiguration.class
                        ));
    }

    /**
     * A Dummy App.
     */
    @Configuration
    public static class KStreamApplication {

        @Autowired
        public void stream(final ObjectProvider<StreamsBuilder> builderProvider) {

            final StreamsBuilder builder = builderProvider.getIfAvailable();
            if (builder == null) {
                return;
            }

            builder.<String, String>stream("join-in", Consumed.as("join-consumer"))
                   .groupByKey(Grouped.as("join-group-by"))
                   .reduce(
                           String::concat,
                           Named.as("join"),
                           Materialized.as("join-store")
                   );

            builder.<String, String>stream("sum-in", Consumed.as("sum-consumer"))
                   .transform(() -> new Transformer<String, String, KeyValue<Long, Integer>>() {
                       @Override
                       public void init(final ProcessorContext context) {}

                       @Override
                       public KeyValue<Long, Integer> transform(final String key, final String value) {
                           return new KeyValue<>(Long.valueOf(key), Integer.valueOf(value));
                       }

                       @Override
                       public void close() {}
                   }, Named.as("sum-to-uuid"))
                   .groupByKey(Grouped.<Long, Integer>as("sum-group-by")
                                      .withKeySerde(Long())
                                      .withValueSerde(Integer()))
                   .reduce(
                           Integer::sum,
                           Named.as("sum"),
                           Materialized.<Long, Integer, KeyValueStore<Bytes, byte[]>>as("sum-store")
                                       .withKeySerde(Long())
                                       .withValueSerde(Integer())
                   )
                   .toStream(Named.as("sum-as-stream"))
                   .transform(() -> new Transformer<Long, Integer, KeyValue<String, String>>() {
                       @Override
                       public void init(final ProcessorContext context) {}

                       @Override
                       public KeyValue<String, String> transform(final Long key, final Integer value) {
                           return new KeyValue<>(key.toString(), value.toString());
                       }

                       @Override
                       public void close() {}
                   }, Named.as("sum-to-string"))
                   .to("sum-out", Produced.as("sum-sink"));
        }
    }
}
