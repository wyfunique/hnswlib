package com.github.jelmerk.knn.statistics;

import com.github.jelmerk.knn.Index;
import com.github.jelmerk.knn.Item;
import com.github.jelmerk.knn.SearchResult;
import org.eclipse.collections.impl.list.mutable.primitive.DoubleArrayList;

import java.io.Serializable;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Decorator on top of an index that will collect statistics about the index. Such as the precision of the results
 * returned by the approximative index compared to a brute force baseline.
 *
 * @param <TId> Type of the external identifier of an item
 * @param <TVector> Type of the vector to perform distance calculation on
 * @param <TItem> Type of items stored in the index
 * @param <TDistance> Type of distance between items (expect any numeric type: float, double, int, ..)
 */
public class StatisticsDecorator<TId, TVector, TItem extends Item<TId, TVector>, TDistance>
        implements Index<TId, TVector, TItem, TDistance>, Serializable {

    public static final int DEFAULT_NUM_SAMPLES = 1000;

    private final Index<TId, TVector, TItem, TDistance> delegate;
    private final Index<TId, TVector, TItem, TDistance> groundTruth;
    private final int sampleFrequency;

    private AtomicLong searchCount = new AtomicLong();

    private final MovingAverageAccuracyCalculator accuracyEvaluator;


    /**
     * Constructs a new StatisticsDecorator.
     *
     * @param delegate the approximative index
     * @param groundTruth the brute force index
     * @param maxPrecisionSampleFrequency at most maxPrecisionSampleFrequency the results from the approximative index
     *                                    will be compared with those of the groundTruth to establish the the runtime
     *                                    precision of the index.
     * @param numSamples number of samples to calculate the moving average over
     */
    public StatisticsDecorator(Index<TId, TVector, TItem, TDistance> delegate,
                               Index<TId, TVector, TItem, TDistance> groundTruth,
                               int maxPrecisionSampleFrequency,
                               int numSamples) {

        this.delegate = delegate;
        this.groundTruth = groundTruth;
        this.sampleFrequency = maxPrecisionSampleFrequency;

        this.accuracyEvaluator = new MovingAverageAccuracyCalculator(1, numSamples);

        Thread thread = new Thread(accuracyEvaluator);
        thread.setName("accuracyEvaluator");
        thread.setDaemon(true);
        thread.start();
    }

    /**
     * Constructs a new StatisticsDecorator. Statistics will be calulated over the last
     * {@link StatisticsDecorator#DEFAULT_NUM_SAMPLES} number of collected datapoints.
     *
     * @param delegate the approximative index
     * @param groundTruth the brute force index
     * @param maxPrecisionSampleFrequency at most maxPrecisionSampleFrequency the results from the approximative index
     *                                    will be compared with those of the groundTruth to establish the the runtime
     *                                    precision of the index.
     */
    public StatisticsDecorator(Index<TId, TVector, TItem, TDistance> delegate,
                               Index<TId, TVector, TItem, TDistance> groundTruth,
                               int maxPrecisionSampleFrequency) {
        this(delegate, groundTruth, maxPrecisionSampleFrequency, DEFAULT_NUM_SAMPLES);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void add(TItem item) {
        delegate.add(item);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public boolean remove(TId id) {
        return delegate.remove(id);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public int size() {
        return delegate.size();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public Optional<TItem> get(TId id) {
        return delegate.get(id);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public List<SearchResult<TItem, TDistance>> findNearest(TVector vector, int k) {
        List<SearchResult<TItem, TDistance>> searchResults = delegate.findNearest(vector, k);

        if (searchCount.getAndIncrement() % sampleFrequency == 0) {
            accuracyEvaluator.offer(new RequestArgumentsAndResults(vector, k, searchResults));
        }

        return searchResults;
    }

    /**
     * Returns the collected statistics for this index.
     *
     * @return the collected statistics for this index
     */
    public IndexStats stats() {
        return new IndexStats(accuracyEvaluator.getAveragePrecision());
    }

    class MovingAverageAccuracyCalculator implements Runnable {

        private final int samples;
        private final ArrayBlockingQueue<RequestArgumentsAndResults> queue;
        private final DoubleArrayList results;

        private volatile boolean running = true;
        private volatile double averagePrecision;

        MovingAverageAccuracyCalculator(int maxBacklog, int numSamples) {
            this.samples = numSamples;

            this.queue  = new ArrayBlockingQueue<>(maxBacklog);
            this.results = new DoubleArrayList(numSamples);
        }

        @Override
        public void run() {
            try {
                while (running) {
                    RequestArgumentsAndResults item = queue.poll(500, TimeUnit.MILLISECONDS);

                    if (item != null) {
                        List<SearchResult<TItem, TDistance>> expectedResults = groundTruth.findNearest(item.vector, item.k);

                        int correct = expectedResults.stream().mapToInt(r -> item.searchResults.contains(r) ? 1 : 0).sum();
                        double precision = (double) correct / (double) expectedResults.size();

                        if (results.size() >= samples) {
                            results.removeAtIndex(0);
                        }

                        results.add(precision);

                        averagePrecision = results.sum() / results.size();
                    }
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
               // just die
            }
        }

        boolean offer(RequestArgumentsAndResults requestAndResults) {
            return queue.offer(requestAndResults); // won't block if we can't keep up but will return false
        }

        public double getAveragePrecision() {
            return averagePrecision;
        }

        void shutdown() {
            running = false;
        }
    }

    class RequestArgumentsAndResults {
        TVector vector;
        int k;
        List<SearchResult<TItem, TDistance>> searchResults;

        RequestArgumentsAndResults(TVector vector, int k, List<SearchResult<TItem, TDistance>> searchResults) {
            this.vector = vector;
            this.k = k;
            this.searchResults = searchResults;
        }
    }
}
