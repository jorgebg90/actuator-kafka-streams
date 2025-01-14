package io.github.leofuso.autoconfigure.actuator.kafka.streams.autopilot;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import java.text.NumberFormat;
import java.time.Duration;
import java.util.Collection;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Properties;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.function.Predicate;
import java.util.regex.Pattern;

import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.streams.KafkaStreams;
import org.apache.kafka.streams.StreamsConfig;
import org.apache.kafka.streams.TaskMetadata;
import org.apache.kafka.streams.ThreadMetadata;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.common.collect.Sets;

import io.github.leofuso.autoconfigure.actuator.kafka.streams.utils.CompactDurationFormat;
import io.github.leofuso.autoconfigure.actuator.kafka.streams.utils.ConfigUtils;

import static io.github.leofuso.autoconfigure.actuator.kafka.streams.autopilot.Autopilot.State.*;
import static io.github.leofuso.autoconfigure.actuator.kafka.streams.autopilot.Autopilot.State.BOOSTING;
import static io.github.leofuso.autoconfigure.actuator.kafka.streams.autopilot.Autopilot.State.DECREASING;
import static io.github.leofuso.autoconfigure.actuator.kafka.streams.autopilot.AutopilotConfiguration.Period;
import static java.util.concurrent.CompletableFuture.supplyAsync;
import static java.util.concurrent.locks.ReentrantReadWriteLock.WriteLock;
import static org.apache.kafka.clients.consumer.ConsumerConfig.MAX_POLL_INTERVAL_MS_CONFIG;
import static org.apache.kafka.clients.consumer.ConsumerConfig.SESSION_TIMEOUT_MS_CONFIG;
import static org.apache.kafka.streams.StreamsConfig.NUM_STREAM_THREADS_CONFIG;

/**
 * A default implementation of the {@link Autopilot autopilot} API.
 */
public class DefaultAutopilot implements Autopilot {

    private static final Logger logger = LoggerFactory.getLogger(DefaultAutopilot.class);

    /**
     * The current {@link io.github.leofuso.autoconfigure.actuator.kafka.streams.autopilot.Autopilot.State}.
     */
    private State state = STAND_BY;

    /**
     * A lock for the {@link io.github.leofuso.autoconfigure.actuator.kafka.streams.autopilot.Autopilot.State}.
     */
    private final ReentrantReadWriteLock stateLock = new ReentrantReadWriteLock(true);

    /**
     * Stores the most recent lag info.
     */
    private final Map<String, Map<TopicPartition, Long>> threadInfo = new ConcurrentHashMap<>();

    /**
     * As long as the target is above or bellow the {@link DefaultAutopilot#desiredThreadCount}, an action must be
     * taken.
     */
    private Integer targetThreadCount;

    /**
     * A desired thread count. Based on the {@code num.threads} property, as defined by the user.
     */
    private final Integer desiredThreadCount;

    /**
     * A timeout period the Autopilot should wait when performing asynchronous tasks.
     */
    private final Duration genericTimeout;

    /**
     * Keeps track of {@link KafkaStreams} state changes.
     */
    @Nullable
    private RecoveryWindowManager windowManager;

    /**
     * Manages additional {@link org.apache.kafka.streams.processor.internals.StreamThread StreamThreads}.
     */
    private final KafkaStreams streams;

    /**
     * To coordinate the {@link Autopilot autopilot}.
     */
    private final AutopilotConfiguration config;

    /**
     * {@link java.util.concurrent.Executor Executor} responsible for performing {@link Autopilot autopilot} automated
     * runs, and asynchronous actions.
     */
    private final ScheduledExecutorService executor = Executors.newSingleThreadScheduledExecutor();

    /**
     * Helper to format huge numbers, commonly placed in situations of high partition-lag.
     */
    private static final NumberFormat NUMBER_FORMAT = NumberFormat.getNumberInstance(
            Locale.US
    );

    /**
     * Constructs a new DefaultAutopilot instance, non-automated.
     *
     * @param streams    to delegate the actions to, and holder of required thread info.
     * @param config     used to coordinate {@link Autopilot} automated actions.
     * @param properties used to coordinate {@link Autopilot} automated actions.
     */
    public DefaultAutopilot(KafkaStreams streams, AutopilotConfiguration config, Properties properties) {
        this.streams = Objects.requireNonNull(streams, "KafkaStreams [streams] is required.");
        this.config = Objects.requireNonNull(config, "AutopilotConfiguration [config] is required.");
        Objects.requireNonNull(properties, "Properties [properties] is required.");

        final Integer threadCount = ConfigUtils
                .<Integer>access(properties, NUM_STREAM_THREADS_CONFIG, StreamsConfig.configDef())
                .orElse(1);

        this.desiredThreadCount = threadCount;
        this.targetThreadCount = threadCount;

        final int maxPollInterval = ConfigUtils
                .<Integer>access(properties, MAX_POLL_INTERVAL_MS_CONFIG, ConsumerConfig.configDef())
                .orElse(300_000);

        final int sessionTimeout = ConfigUtils
                .<Integer>access(properties, SESSION_TIMEOUT_MS_CONFIG, ConsumerConfig.configDef())
                .orElse(45_000);

        final int timeoutInMillis = Math.max(maxPollInterval, sessionTimeout);
        this.genericTimeout = Duration.ofMillis(timeoutInMillis);
    }

    @Override
    public void run() {

        final Long threshold = config.getLagThreshold();
        logger.info(
                "Autopilot is gathering lag info from all StreamThreads. Looking for partition-lag above [{}].",
                NUMBER_FORMAT.format(threshold)
        );

        final WriteLock lock = stateLock.writeLock();

        try {

            final State oldState = state;

            final boolean unlocked = lock.tryLock() || lock.tryLock(genericTimeout.toMillis(), TimeUnit.MILLISECONDS);
            if (!unlocked) {
                final String message = "Autopilot [NOOP]. Could not get lock, is someone else holding it?";
                throw new IllegalStateException(message);
            }

            final State state = doRun().get(genericTimeout.toMillis(), TimeUnit.MILLISECONDS);
            logger.info("Autopilot successfully transitioned from [{}] to [{}].", oldState, state);

        } catch (Exception ex) {
            logger.error("Autopilot [NOOP]. Something went wrong.", ex);
        } finally {
            lock.unlock();
        }
    }

    private CompletableFuture<State> doRun() {

        final Map<String, Map<TopicPartition, Long>> threads = threadInfo();
        if (threads.isEmpty()) {
            return CompletableFuture.completedFuture(state);
        }

        final boolean canActUpon = state.isValidTransition(
                BOOSTING,
                DECREASING,
                STAND_BY
        );

        if (!canActUpon) {
            logger.info("Autopilot [NOOP]. Nothing to be done. State [{}]", state);
            return CompletableFuture.completedFuture(state);
        }

        if (windowManager == null) {
            throw new IllegalStateException(
                    "Autopilot [NOOP]. Autopilot cannot perform its run without a window manager."
            );
        }

        final boolean isOpen = windowManager.isOpen();
        if (isOpen) {
            return CompletableFuture.completedFuture(state);
        }

        final State nextState = decideNextState();
        switch (nextState) {
            case STAND_BY:
            case BOOSTED:
                state = nextState;
                return CompletableFuture.completedFuture(state);
            case BOOSTING:
                logger.info("Autopilot is [{}] the StreamThread count.", BOOSTING);
                return doAddStreamThread()
                        .thenApply(thread -> state);
            case DECREASING:
                logger.info("Autopilot is [{}] the StreamThread count.", DECREASING);
                return doRemoveStreamThread()
                        .thenApply(thread -> state);
            default:
                throw new IllegalArgumentException();
        }
    }

    private State decideNextState() {

        final int threadCount = threadInfo.size();
        final long accumulatedLag = threadInfo
                .values()
                .stream()
                .flatMap(thread -> {
                    final Collection<Long> threadLag = thread.values();
                    return threadLag.stream();
                })
                .reduce(Long::sum)
                .orElse(0L);

        final long average = accumulatedLag / (threadCount == 0 ? 1 : threadCount);
        logger.info("Autopilot found an average partition-lag of {}", NUMBER_FORMAT.format(average));

        final int threadLimit = desiredThreadCount + config.getStreamThreadLimit();
        if (threadLimit == threadCount) {
            logger.warn(
                    "Autopilot [NOOP]. StreamThread count [{}] has reached its defined limit of [{}].",
                    threadCount,
                    threadLimit
            );
            return BOOSTED;
        }

        final Long threshold = config.getLagThreshold();
        final int upperLimit = desiredThreadCount + threadLimit;
        for (targetThreadCount = desiredThreadCount; targetThreadCount < upperLimit; targetThreadCount++) {
            final long averageLag = accumulatedLag / targetThreadCount;
            if (averageLag <= threshold) {
                break;
            }
        }

        logger.info(
                "Autopilot found StreamThread optimal target count to be {}, current count is {}",
                targetThreadCount,
                threadCount
        );
        return targetThreadCount > threadCount ? BOOSTING
                : targetThreadCount < threadCount ? DECREASING
                : targetThreadCount.equals(desiredThreadCount) ? STAND_BY
                : BOOSTED;
    }

    @Override
    public CompletableFuture<String> addStreamThread(final Duration timeout) {
        if (!state.isValidTransition(BOOSTING)) {
            final String message = String.format("Autopilot [NOOP]. Cannot manually transition from [%s] to [%s].",
                    state,
                    BOOSTING
            );
            final IllegalStateException ex = new IllegalStateException(message);
            return CompletableFuture.failedFuture(ex);
        }

        final WriteLock lock = stateLock.writeLock();

        try {

            if (lock.tryLock() || lock.tryLock(timeout.toMillis(), TimeUnit.MILLISECONDS)) {
                return doAddStreamThread();
            }

            final IllegalStateException exception =
                    new IllegalStateException("Autopilot [NOOP]. Could not get lock, is someone else holding it?");

            return CompletableFuture.failedFuture(exception);

        } catch (Throwable e) {
            return CompletableFuture.failedFuture(e);
        } finally {
            lock.unlock();
        }
    }

    private CompletableFuture<String> doAddStreamThread() {
        state = BOOSTING;
        return supplyAsync(streams::addStreamThread)
                .handleAsync((result, throwable) -> {

                    threadInfo();
                    if (throwable != null) {
                        logger.error(
                                "Oops, something went wrong. Autopilot couldn't add a new StreamThread.",
                                throwable
                        );
                        throw new RuntimeException(throwable);
                    }

                    final boolean empty = result.isEmpty();
                    if (empty) {
                        logger.error("Oops, something went wrong. Autopilot couldn't add a new StreamThread.");
                        return null;
                    }

                    final String threadName = result.get();
                    logger.info("A StreamThread [{}] was successfully added by Autopilot.", threadName);
                    state = BOOSTED;

                    return threadName;
                })
                .toCompletableFuture();
    }

    @Override
    public CompletableFuture<String> removeStreamThread(final Duration timeout) {
        if (!state.isValidTransition(DECREASING)) {
            final String message = String.format("Autopilot [NOOP]. Cannot manually transition from [%s] to [%s].",
                    state,
                    DECREASING
            );
            final IllegalStateException ex = new IllegalStateException(message);
            return CompletableFuture.failedFuture(ex);
        }

        final WriteLock lock = stateLock.writeLock();

        try {

            if (lock.tryLock() || lock.tryLock(timeout.toMillis(), TimeUnit.MILLISECONDS)) {
                return doRemoveStreamThread();
            }

            final IllegalStateException exception =
                    new IllegalStateException("Autopilot [NOOP]. Could not get lock, is someone else holding it?");

            return CompletableFuture.failedFuture(exception);

        } catch (Throwable e) {
            return CompletableFuture.failedFuture(e);
        } finally {
            lock.unlock();
        }
    }

    private CompletableFuture<String> doRemoveStreamThread() {
        state = DECREASING;
        return supplyAsync(streams::removeStreamThread)
                .handleAsync((result, throwable) -> {

                    threadInfo();
                    if (throwable != null) {
                        logger.error(
                                "Oops, something went wrong. Autopilot couldn't remove any StreamThread.",
                                throwable
                        );
                        throw new RuntimeException(throwable);
                    }

                    final boolean empty = result.isEmpty();
                    if (empty) {
                        logger.error("Oops, something went wrong. Autopilot couldn't remove any StreamThread.");
                        return null;
                    }

                    final String threadName = result.get();
                    logger.info("StreamThread [{}] successfully removed by Autopilot.", threadName);
                    state = decideNextState();
                    return threadName;

                })
                .toCompletableFuture();
    }

    @Override
    public State state() {
        return state;
    }

    @Override
    public HashMap<String, Map<TopicPartition, Long>> threadInfo() {

        final Pattern exclusionPattern = config.getExclusionPattern();
        final Predicate<String> exclusionPredicate = exclusionPattern.asPredicate();

        final HashMap<String, Map<TopicPartition, Long>> threads = new HashMap<>();
        final Set<ThreadMetadata> localThreads = streams.metadataForLocalThreads();
        for (ThreadMetadata threadMetadata : localThreads) {

            final HashMap<TopicPartition, Long> partitionLag = new HashMap<>();
            final Set<TaskMetadata> activeTasks = threadMetadata.activeTasks();
            final Set<TaskMetadata> standbyTasks = threadMetadata.standbyTasks();

            for (TaskMetadata taskMetadata : Sets.union(activeTasks, standbyTasks)) {

                final Map<TopicPartition, Long> endOffsets = taskMetadata.endOffsets();
                final Map<TopicPartition, Long> committedOffsets = taskMetadata.committedOffsets();

                for (Map.Entry<TopicPartition, Long> endOffsetEntry : endOffsets.entrySet()) {
                    final TopicPartition partition = endOffsetEntry.getKey();

                    final String topic = partition.topic();
                    final boolean isExcluded = exclusionPredicate.test(topic);
                    if (isExcluded) {
                        continue;
                    }

                    final Long endOffset = endOffsetEntry.getValue();
                    final Long committedOffset = committedOffsets.get(partition);

                    if (endOffset <= 0 || committedOffset <= 0) {
                        /* We don't need to store zero-lag or undefined lag (-1) info. */
                        continue;
                    }

                    final long lag = Math.max(0, endOffset - committedOffset);
                    partitionLag.put(partition, lag);
                }
            }

            final String name = threadMetadata.threadName();
            threads.put(name, partitionLag);
        }

        final Set<String> managedThreads = threadInfo.keySet();
        final Set<String> updatedThreads = threads.keySet();

        final Sets.SetView<String> difference = Sets.difference(managedThreads, updatedThreads);
        for (String threadName : difference) {
            threadInfo.compute(threadName, (k, v) -> null);
        }

        threadInfo.putAll(threads);

        if (threadInfo.isEmpty()) {
            logger.warn("Autopilot [NOOP]. Could not gather lag info. No active or inactive Tasks.");
        }

        return threads;
    }

    @Override
    public void automate(@Nonnull RecoveryWindowManager windowManager) {
        final Period period = config.getPeriod();
        final Duration initialDelay = period.getInitialDelay();
        final Duration betweenRuns = period.getBetweenRuns();

        final long initialDelayInMillis = initialDelay.toMillis();
        final long betweenRunsInMillis = betweenRuns.toMillis();

        executor.scheduleAtFixedRate(this, initialDelayInMillis, betweenRunsInMillis, TimeUnit.MILLISECONDS);
        this.windowManager = windowManager;

        final String prettyInitialDelay = CompactDurationFormat.format(initialDelay);
        final String prettyPeriod = CompactDurationFormat.format(betweenRuns);
        logger.info(
                "Autopilot scheduled. Will commence in {} with evaluation periods every {}.",
                prettyInitialDelay,
                prettyPeriod
        );
    }

    @Override
    public void shutdown() {
        executor.shutdownNow();
    }

}
