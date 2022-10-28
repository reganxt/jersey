/*
 * Copyright (c) 2015, 2022 Oracle and/or its affiliates. All rights reserved.
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License v. 2.0, which is available at
 * http://www.eclipse.org/legal/epl-2.0.
 *
 * This Source Code may also be made available under the following Secondary
 * Licenses when the conditions for such availability set forth in the
 * Eclipse Public License v. 2.0 are satisfied: GNU General Public License,
 * version 2 with the GNU Classpath Exception, which is available at
 * https://www.gnu.org/software/classpath/license.html.
 *
 * SPDX-License-Identifier: EPL-2.0 OR GPL-2.0 WITH Classpath-exception-2.0
 */

package org.glassfish.jersey.server.internal.monitoring;

import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Logger;

import org.glassfish.jersey.internal.guava.ThreadFactoryBuilder;

import org.glassfish.jersey.server.internal.monitoring.core.TimeReservoir;
import org.glassfish.jersey.server.internal.monitoring.core.UniformTimeSnapshot;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Multi Threading concurrency test of Jersey monitoring internals.
 *
 * @author Stepan Vavra
 */
public class MultiThreadingAggregatedReservoirTest {

    private static final Logger LOGGER = Logger.getLogger(MultiThreadingAggregatedReservoirTest.class.getName());

    private static final int PRODUCER_COUNT = 5;
    private static final int CONSUMER_COUNT = 5;

    /*
     * Note that more than 5 seconds may require more than 1G heap memory.
     */
    private static final int TEST_DURATION_MILLIS = 1_000;
    private static final int SHUTDOWN_TIMEOUT_SECONDS = 120;
    private static final double DELTA = 0.0001;

    private final AtomicInteger incrementer = new AtomicInteger(0);

    private final ExecutorService producerExecutorService = Executors
            .newFixedThreadPool(PRODUCER_COUNT, new ThreadFactoryBuilder().setDaemon(true).build());

    private final ExecutorService consumerExecutorService = Executors
            .newFixedThreadPool(CONSUMER_COUNT, new ThreadFactoryBuilder().setDaemon(true).build());

    private final long startTime = System.nanoTime();
    private final TimeUnit startUnitTime = TimeUnit.NANOSECONDS;

    private final AggregatingTrimmer trimmer =
            new AggregatingTrimmer(startTime(), startUnitTime, 10, TimeUnit.MICROSECONDS);
    private final SlidingWindowTimeReservoir time10usReservoir =
            new SlidingWindowTimeReservoir(10, TimeUnit.MICROSECONDS,
                                           startTime(), startUnitTime, trimmer);
    private final AggregatedSlidingWindowTimeReservoir time1DayAggregatedReservoir =
            new AggregatedSlidingWindowTimeReservoir(1,
                                                     TimeUnit.DAYS,
                                                     startTime(), startUnitTime, trimmer);
    private final AggregatedSlidingWindowTimeReservoir time10DaysAggregatedReservoir =
            new AggregatedSlidingWindowTimeReservoir(
                    10, TimeUnit.DAYS,
                    startTime(), startUnitTime, trimmer);
    private final List<AggregatedSlidingWindowTimeReservoir> aggregatedTimeReservoirs =
            new CopyOnWriteArrayList<>(
                    Arrays.asList(
                            new AggregatedSlidingWindowTimeReservoir(1, TimeUnit.SECONDS, startTime(),
                                                                     startUnitTime, trimmer),
                            time1DayAggregatedReservoir,
                            time10DaysAggregatedReservoir
                    ));

    /**
     * Determines the start time of the test.
     *
     * @return The start time of the test. Must be a constant value.
     */
    protected long startTime() {
        return startTime;
    }

    private volatile boolean doShutdown = false;

    /**
     * Runs {@link #PRODUCER_COUNT} producers that update {@link #time10usReservoir} 10 microseconds sliding window reservoir with
     * sequentially increasing values generated by {@link @incrementer}. This sliding window updates 1 day aggregated sliding
     * window and also 10 days aggregated sliding window ({@link #time1DayAggregatedReservoir} and {@link
     * #time10DaysAggregatedReservoir} respectively). In the meantime, {@link #CONSUMER_COUNT} consumers retrieve snapshots from
     * the aggregated window in order to increase the level of concurrency.
     *
     * @throws InterruptedException If any of the thread was interrupted and the test result won't be reliable
     */
    @Test
    public void parallelProducersAndConsumersTestingAggregatedSlidingWindows() throws InterruptedException {

        executeInParallel(consumerExecutorService, CONSUMER_COUNT, new Runnable() {
            @Override
            public void run() {
                try {
                    LOGGER.info("Consumer starting.");
                    while (!doShutdown && !Thread.currentThread().isInterrupted()) {

                        aggregatedTimeReservoirs.get(ThreadLocalRandom.current().nextInt(aggregatedTimeReservoirs.size()))
                                                .getSnapshot(System.nanoTime(), TimeUnit.NANOSECONDS);
                        Thread.sleep(100);
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    LOGGER.info("Consumer terminating.");
                }
            }
        });

        executeInParallel(producerExecutorService, PRODUCER_COUNT, new Runnable() {
            @Override
            public void run() {
                LOGGER.info("Producer starting.");
                while (!doShutdown) {
                    final int value = incrementer.incrementAndGet();
                    time10usReservoir.update((long) value, System.nanoTime(), TimeUnit.NANOSECONDS);
                }
                LOGGER.info("Producer terminating.");
            }
        });

        Thread.sleep(TEST_DURATION_MILLIS);
        LOGGER.info("Shutting down...");

        doShutdown = true;
        producerExecutorService.shutdown();
        consumerExecutorService.shutdown();
        Assertions.assertTrue(consumerExecutorService.awaitTermination(SHUTDOWN_TIMEOUT_SECONDS, TimeUnit.SECONDS),
                          "Consumer tasks didn't terminated peacefully, aborting this test.");
        Assertions.assertTrue(producerExecutorService.awaitTermination(SHUTDOWN_TIMEOUT_SECONDS, TimeUnit.SECONDS),
                          "Producer tasks didn't terminated peacefully, aborting this test.");

        final long snapshotTime = System.nanoTime();
        final long sum = (long) incrementer.get() * (incrementer.get() + 1) / 2;

        LOGGER.info("Integer reached: " + incrementer.get());

        checkInNanos(time1DayAggregatedReservoir, snapshotTime, incrementer.get(), 1, incrementer.get(),
                     (double) sum / incrementer.get(), snapshotTime - startTime());
        checkInNanos(time10DaysAggregatedReservoir, snapshotTime, incrementer.get(), 1, incrementer.get(),
                     (double) sum / incrementer.get(), snapshotTime - startTime());
    }

    private void executeInParallel(final Executor consumerExecutorService, final int count, final Runnable runnable) {
        for (int i = 0; i < count; ++i) {
            consumerExecutorService.execute(runnable);
        }
    }

    /**
     * Shutdown the producer executor service.
     */
    @AfterEach
    public void shutdownProducers() {
        producerExecutorService.shutdownNow();
    }

    /**
     * Shutdown the consumer executor service.
     */
    @AfterEach
    public void shutdownConsumers() {
        consumerExecutorService.shutdownNow();
    }

    /**
     * Checks whether the snapshot of given reservoir exhibits with expected measurements.
     *
     * @param reservoir        The reservoir to assert.
     * @param snapshotTime     The time for which to get the snapshot
     * @param expectedSize     Expected size of the snapshot
     * @param expectedMin      Expected minimum
     * @param expectedMax      Expected maximum
     * @param expectedMean     Expected mean
     * @param expectedInterval Expected interval
     */
    private static void checkInNanos(final TimeReservoir reservoir,
                                     final long snapshotTime,
                                     final long expectedSize,
                                     final long expectedMin,
                                     final long expectedMax,
                                     final double expectedMean, final long expectedInterval) {
        final UniformTimeSnapshot snapshot = reservoir.getSnapshot(snapshotTime, TimeUnit.NANOSECONDS);

        assertEquals(expectedSize, snapshot.size(), "Total count does not match!");
        assertEquals(expectedMin, snapshot.getMin(), "Min exec time does not match!");
        assertEquals(expectedMax, snapshot.getMax(), "Max exec time does not match!");
        assertEquals(expectedMean, snapshot.getMean(), DELTA, "Average exec time does not match!");
        assertEquals(expectedInterval, snapshot.getTimeInterval(TimeUnit.NANOSECONDS), "Expected interval does not match!");
    }
}
