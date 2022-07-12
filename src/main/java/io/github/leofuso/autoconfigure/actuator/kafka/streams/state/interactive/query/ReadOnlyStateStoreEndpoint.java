package io.github.leofuso.autoconfigure.actuator.kafka.streams.state.interactive.query;

import javax.annotation.Nullable;

import java.util.Map;

import org.apache.logging.log4j.util.Strings;
import org.springframework.boot.actuate.endpoint.annotation.Endpoint;
import org.springframework.boot.actuate.endpoint.annotation.ReadOperation;
import org.springframework.boot.actuate.endpoint.annotation.Selector;

import io.github.leofuso.autoconfigure.actuator.kafka.streams.state.interactive.query.remote.RemoteQueryableReadOnlyKeyValueStore;

import static org.apache.kafka.streams.state.QueryableStoreTypes.keyValueStore;

/**
 * Actuator endpoint for querying {@link org.apache.kafka.streams.state.ReadOnlyKeyValueStore ReadOnlyKeyValue} stores.
 */
@Endpoint(id = "readonlystatestore")
public class ReadOnlyStateStoreEndpoint {

    private static final String ERROR_MESSAGE_KEY = "message";

    private final InteractiveQuery executor;

    public ReadOnlyStateStoreEndpoint(final InteractiveQuery executor) {
        this.executor = executor;
    }

    /**
     * Query for a value associated with given key and store.
     *
     * @param store of the {@link org.apache.kafka.streams.state.ReadOnlyKeyValueStore queryable store}.
     * @param key   to query for.
     * @param serde the key class. Restricted to supported {@link org.apache.kafka.common.serialization.Serdes serdes}
     *              types.
     * @return the value associated with the key, if any. Will encapsulate eventual
     * {@link Exception#getMessage() exception's messages} into a response object.
     *
     * @implNote Due to the nature of the query Api this is a relative expensive operation and should be invoked with
     * care. All disposable objects will only persist during the lifecycle of this query to save on resources.
     */
    @ReadOperation
    public <K, V> Map<String, String> find(@Selector String store, @Selector String key, @Nullable String serde) {
        try {

            var action = Action.<K, V, RemoteQueryableReadOnlyKeyValueStore>performOn(store, keyValueStore())
                               .usingStringifiedKey(key)
                               .withKeySerdeClass(serde)
                               .aQuery((k, s) -> s.findByKey(k, store));

            return executor.execute(action)
                           .map(value -> Map.of(key, value.toString()))
                           .orElseGet(() -> Map.of(key, Strings.EMPTY));


        } catch (Exception ex) {
            return Map.of(ERROR_MESSAGE_KEY, ex.getMessage());
        }
    }

}
