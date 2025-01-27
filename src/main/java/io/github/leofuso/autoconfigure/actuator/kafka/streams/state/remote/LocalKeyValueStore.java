package io.github.leofuso.autoconfigure.actuator.kafka.streams.state.remote;

import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

import org.apache.kafka.streams.KafkaStreams;
import org.apache.kafka.streams.StoreQueryParameters;
import org.apache.kafka.streams.StreamsConfig;
import org.apache.kafka.streams.state.HostInfo;
import org.apache.kafka.streams.state.QueryableStoreType;
import org.apache.kafka.streams.state.ReadOnlyKeyValueStore;
import org.apache.kafka.streams.state.ValueAndTimestamp;

import io.github.leofuso.autoconfigure.actuator.kafka.streams.KStreamsSupplier;

import static org.apache.kafka.streams.StoreQueryParameters.fromNameAndType;
import static org.apache.kafka.streams.state.QueryableStoreTypes.keyValueStore;
import static org.apache.kafka.streams.state.QueryableStoreTypes.timestampedKeyValueStore;

/**
 * A {@link RemoteKeyValueStateStore} that offers local query functionality and can be accessed through a remote
 * interface.
 */
public class LocalKeyValueStore implements RemoteKeyValueStateStore {

    private final KStreamsSupplier kStreamsSupplier;
    private final HostInfo self;

    /**
     * Creates a new instance of this {@link RemoteKeyValueStateStore store}. Will throw an
     * {@link IllegalStateException} if missing required configurations, including the ability to create a
     * {@link HostInfo host}, necessary to expose this {@link RemoteKeyValueStateStore store} to the
     * {@link io.grpc.Server server}.
     *
     * @param supplier used to extract the necessary configurations and provide access to a running
     *                 {@link KafkaStreams}.
     */
    public LocalKeyValueStore(final KStreamsSupplier supplier) {
        this.kStreamsSupplier = Objects.requireNonNull(supplier, "KStreamsSupplier [supplier] is required.");
        this.self = Optional.of(this.kStreamsSupplier)
                .map(KStreamsSupplier::configAsProperties)
                .map(StreamsConfig::new)
                .map(config -> config.getString(StreamsConfig.APPLICATION_SERVER_CONFIG))
                .map(HostInfo::buildFromEndpoint)
                .orElseThrow(() -> {
                    final String message = "A required config is missing [application.server].";
                    return new IllegalStateException(message);
                });
    }

    public HostInfo self() {
        return self;
    }

    @Override
    public <K, V> CompletableFuture<V> findOne(final K key, final String storeName) {
        final KafkaStreams streams = kStreamsSupplier.getOrThrows();

        final QueryableStoreType<ReadOnlyKeyValueStore<K, V>> type = keyValueStore();
        final StoreQueryParameters<ReadOnlyKeyValueStore<K, V>> parameters = fromNameAndType(storeName, type);

        final ReadOnlyKeyValueStore<K, V> store = streams.store(parameters);
        final V v = store.get(key);
        return CompletableFuture.completedFuture(v);
    }

    @Override
    public <K, V> CompletableFuture<ValueAndTimestamp<V>> findOneTimestamped(final K key, final String storeName) {
        final KafkaStreams streams = kStreamsSupplier.getOrThrows();
        final QueryableStoreType<ReadOnlyKeyValueStore<K, ValueAndTimestamp<V>>> type = timestampedKeyValueStore();
        final StoreQueryParameters<ReadOnlyKeyValueStore<K, ValueAndTimestamp<V>>> parameters =
                fromNameAndType(storeName, type);

        final ReadOnlyKeyValueStore<K, ValueAndTimestamp<V>> store = streams.store(parameters);
        final ValueAndTimestamp<V> v = store.get(key);
        return CompletableFuture.completedFuture(v);
    }


    @Override
    public int hashCode() {
        return Objects.hash(reference());
    }

    @Override
    public boolean equals(final Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof LocalKeyValueStore)) {
            return false;
        } else {
            LocalKeyValueStore that = (LocalKeyValueStore) other;
            return reference().equals(that.reference());
        }
    }
}
