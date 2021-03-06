package runner;

import board.StoryBoard;
import board.query.CubeQueryProcessor;
import board.query.ErrorMetric;
import board.query.LinearQueryProcessor;
import board.workload.CubeWorkload;
import board.workload.LinearWorkload;
import io.*;
import org.eclipse.collections.api.PrimitiveIterable;
import org.eclipse.collections.api.factory.Lists;
import org.eclipse.collections.api.factory.Maps;
import org.eclipse.collections.api.list.primitive.DoubleList;
import org.eclipse.collections.api.list.primitive.IntList;
import org.eclipse.collections.api.list.primitive.LongList;
import org.eclipse.collections.api.map.MutableMap;
import org.eclipse.collections.impl.list.mutable.FastList;
import org.eclipse.collections.impl.map.mutable.UnifiedMap;
import runner.factory.FreqSketchGenFactory;
import runner.factory.QuantileSketchGenFactory;
import runner.factory.SketchGenFactory;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class QueryRunner<T, TL extends PrimitiveIterable> {
    RunConfig config;

    String experiment;
    boolean quantile;
    String outputDir;
    String xToTrackPath;
    int numQueries;
    List<String> sketches;
    List<Integer> sizes;

    int granularity;
    List<Integer> accumulatorSizes;

    List<String> dimensionCols;
    List<Double> queryWorkloadProbs;

    boolean cacheQueries;

    boolean isCube;


    public QueryRunner(RunConfig config) {
        this.config = config;

        experiment = config.get("experiment");
        quantile = config.get("quantile");
        outputDir = config.get("out_dir");

        sizes = config.get("sizes");
        sketches = config.get("sketches");

        xToTrackPath = config.get("x_to_track");
        numQueries = config.get("num_queries");

        granularity = config.get("granularity", 0);
        accumulatorSizes = config.get("accumulator_sizes", Lists.mutable.of(0));
        dimensionCols = config.get("dimension_cols", Lists.mutable.empty());
        queryWorkloadProbs = config.get("query_workload_probs", Lists.mutable.<Double>empty());

        cacheQueries = config.get("cache_queries", true);

        isCube = (!dimensionCols.isEmpty());
    }

    public FastList<Map<String, String>> runLinear(
            SimpleCSVDataSource<T> xTrackSource,
            SketchGenFactory<T, TL> genFactory
    ) throws Exception {
        Path boardDir = Paths.get(outputDir, "boards", experiment);
        xTrackSource.setHasHeader(true);
        FastList<T> xToTrack = xTrackSource.get(
                xToTrackPath, 0
        );

        LinearWorkload workloadGen = new LinearWorkload(0);
        FastList<IntList> workloadIntervals = workloadGen.generate(granularity, numQueries);

        MutableMap<IntList, DoubleList> memoizedTrueResults = new UnifiedMap<>();
        MutableMap<IntList, Double> memoizedTrueTotals = new UnifiedMap<>();

        FastList<Map<String, String>> results = new FastList<>();
        MutableMap<String, String> baseResults = Maps.mutable.empty();
        baseResults.put("experiment", experiment);

        for (int curSize : sizes) {
            System.out.println("Size: " + curSize);
            for (String curSketch : sketches) {
                System.out.println("Running Sketch: " + curSketch);
                String boardPath = String.format("%s/%s",
                        boardDir,
                        IOUtil.getBoardName(
                                curSketch,
                                curSize,
                                granularity
                        ));
                File fIn = new File(boardPath);
                StoryBoard<T> board = IOUtil.loadBoard(fIn);

                Timer sketchTotalTimer = new Timer();
                for (int accumulatorSize : accumulatorSizes) {
                    LinearQueryProcessor<T> p_raw = genFactory.getLinearQueryProcessor(
                            curSketch,
                            granularity,
                            accumulatorSize
                    );

                    // Warm-Up
                    for (IntList curInterval : workloadIntervals) {
                        int startIdx = curInterval.get(0);
                        int endIdx = curInterval.get(1);
                        p_raw.setRange(startIdx, endIdx);
                        p_raw.query(board, xToTrack);
                    }
                    System.runFinalization();
                    System.gc();
                    System.out.println("Warmed Up");

                    Timer queryTimer = new Timer();
                    for (IntList curInterval : workloadIntervals) {
                        int startIdx = curInterval.get(0);
                        int endIdx = curInterval.get(1);

                        p_raw.setRange(startIdx, endIdx);
                        sketchTotalTimer.start();
                        queryTimer.reset();
                        queryTimer.start();
                        DoubleList queryResults = p_raw.query(board, xToTrack);
                        queryTimer.end();
                        sketchTotalTimer.end();

                        if (curSketch.equals("top_values") && !memoizedTrueResults.containsKey(curInterval)) {
                            memoizedTrueResults.put(curInterval, queryResults);
                            memoizedTrueTotals.put(curInterval, p_raw.total());
                        }
                        DoubleList trueResults = memoizedTrueResults.get(curInterval);
                        double trueTotal = memoizedTrueTotals.get(curInterval);
                        int curSpan = p_raw.span();

                        MutableMap<String, Double> errorQuantities = ErrorMetric.calcErrors(
                                trueResults,
                                queryResults
                        );

                        MutableMap<String, String> curResults = baseResults.clone();
                        curResults.put("sketch", curSketch);
                        curResults.put("size", Integer.toString(curSize));
                        curResults.put("start_idx", Integer.toString(startIdx));
                        curResults.put("end_idx", Integer.toString(endIdx));
                        curResults.put("query_len", Integer.toString(endIdx - startIdx));
                        curResults.put("segment_span", Integer.toString(curSpan));
                        curResults.put("granularity", Integer.toString(granularity));
                        curResults.put("total", Double.toString(trueTotal));
                        curResults.put("query_time", Double.toString(queryTimer.getTotalMs()));
                        curResults.put("accumulator_size", Integer.toString(accumulatorSize));
                        errorQuantities.forEachKeyValue((String errType, Double errValue) -> {
                            curResults.put(errType, errValue.toString());
                        });
                        results.add(curResults);
                    }
                } // accumulators
                System.out.println("Sketch Ran in Time: " + sketchTotalTimer.getTotalMs());
            } // sketch
        } // size

        Path resultsDir = Paths.get(outputDir, "results", experiment);
        Files.createDirectories(resultsDir);
        Path resultsFile = resultsDir.resolve("errors.csv");

        CSVOutput.writeAllResults(results, resultsFile.toString());
        return results;
    }

    public FastList<Map<String, String>> runCube(
            SimpleCSVDataSource<T> xTrackSource,
            SketchGenFactory<T, TL> genFactory
    ) throws Exception {
        Path boardDir = Paths.get(outputDir, "boards", experiment);
        int curSize = sizes.get(0);

        xTrackSource.setHasHeader(true);
        FastList<T> xToTrack = xTrackSource.get(
                xToTrackPath, 0
        );

        MutableMap<LongList, DoubleList> memoizedTrueResults = new UnifiedMap<>();
        MutableMap<LongList, Double> memoizedTrueTotals = new UnifiedMap<>();

        FastList<Map<String, String>> results = new FastList<>();
        MutableMap<String, String> baseResults = Maps.mutable.empty();
        baseResults.put("experiment", experiment);

        for (String curSketch: sketches) {
            System.out.println("Running Sketch: "+curSketch);
            String boardPath = String.format("%s/%s",
                    boardDir,
                    IOUtil.getBoardName(
                            curSketch,
                            curSize,
                            granularity
                    ));
            File fIn = new File(boardPath);
            StoryBoard<T> board = IOUtil.loadBoard(fIn);

            CubeQueryProcessor<T> p_raw = genFactory.getCubeQueryProcessor(curSketch);
            Timer sketchTotalTimer = new Timer();
            Timer queryTimer = new Timer();

            for (double curWorkloadProbability : queryWorkloadProbs) {
                System.out.println("Running with Workload Prob: "+curWorkloadProbability);
                CubeWorkload workloadGen = new CubeWorkload(0);
                LongList dimensionCardinalities = board.getDimCardinalities();
                FastList<LongList> workloadDimensions = workloadGen.generate(
                        dimensionCardinalities,
                        curWorkloadProbability,
                        numQueries
                );

                // Warm-Up
                for (LongList curDimensions: workloadDimensions) {
                    p_raw.setDimensions(curDimensions);
                    p_raw.query(board, xToTrack);
                }
                System.runFinalization();
                System.gc();
                System.out.println("Warmed Up");

                MutableMap<LongList, Map<String, String>> memoized = new UnifiedMap<>();

                int queryNum = 0;
                for (LongList curFilterDimensions: workloadDimensions) {
                    if (cacheQueries && memoized.containsKey(curFilterDimensions)) {
                        results.add(memoized.get(curFilterDimensions));
                        continue;
                    }

                    p_raw.setDimensions(curFilterDimensions);
                    sketchTotalTimer.start();
                    queryTimer.reset();
                    queryTimer.start();
                    DoubleList queryResults = p_raw.query(board, xToTrack);
                    queryTimer.end();
                    sketchTotalTimer.end();

                    if (curSketch.equals("top_values") && !memoizedTrueResults.containsKey(curFilterDimensions)) {
                        memoizedTrueResults.put(curFilterDimensions, queryResults);
                        memoizedTrueTotals.put(curFilterDimensions, p_raw.total());
                    }
                    DoubleList trueResults = memoizedTrueResults.get(curFilterDimensions);
                    double trueTotal = memoizedTrueTotals.get(curFilterDimensions);
                    int curSpan = p_raw.span();

                    MutableMap<String, Double> errorQuantities = ErrorMetric.calcErrors(
                            trueResults,
                            queryResults
                    );

                    int numFilters = curFilterDimensions.select((long x) -> (x >= 0)).size();

                    MutableMap<String, String> curResults = baseResults.clone();
                    curResults.put("sketch", curSketch);
                    curResults.put("size", Integer.toString(curSize));
                    curResults.put("query_len", Integer.toString(numFilters));
                    curResults.put("total", Double.toString(trueTotal));
                    curResults.put("query_time", Double.toString(queryTimer.getTotalMs()));
                    curResults.put("segment_span", Integer.toString(curSpan));
                    curResults.put("workload_query_prob", Double.toString(curWorkloadProbability));
                    errorQuantities.forEachKeyValue((String errType, Double errValue) -> {
                        curResults.put(errType, errValue.toString());
                    });
                    results.add(curResults);
                    queryNum++;
                    if (cacheQueries) {
                        memoized.put(curFilterDimensions, curResults);
                    }
                }
                System.out.println("Query Runner #: "+queryNum);
            }
            System.out.println("Sketch Ran in Time: " + sketchTotalTimer.getTotalMs());
        }

        Path resultsDir = Paths.get(outputDir, "results", experiment);
        Files.createDirectories(resultsDir);
        Path resultsFile = resultsDir.resolve("errors.csv");

        CSVOutput.writeAllResults(results, resultsFile.toString());

        return results;
    }


    public static void main(String[] args) throws Exception {
        System.out.println("Starting Query Runner");
//        System.in.read();
        String confFile = args[0];
        RunConfig config = RunConfig.fromJsonFile(confFile);
        boolean quantile = config.get("quantile");
        if (quantile) {
            QueryRunner<Double, DoubleList> runner = new QueryRunner<>(config);
            SimpleCSVDataSource<Double> xTrackSource = new SimpleCSVDataSourceDouble();
            SketchGenFactory<Double, DoubleList> sketchGenFactory = new QuantileSketchGenFactory();
            if (runner.isCube) {
                runner.runCube(xTrackSource, sketchGenFactory);
            } else {
                runner.runLinear(
                        xTrackSource,
                        sketchGenFactory
                );
            }
        } else {
            QueryRunner<Long, LongList> runner = new QueryRunner<>(config);
            SimpleCSVDataSource<Long> xTrackSource = new SimpleCSVDataSourceLong();
            SketchGenFactory<Long, LongList> sketchGenFactory = new FreqSketchGenFactory();
            if (runner.isCube) {
                runner.runCube(xTrackSource, sketchGenFactory);
            } else {
                runner.runLinear(
                        xTrackSource,
                        sketchGenFactory
                );
            }
        }
    }
}
