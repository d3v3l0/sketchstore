package board.query;

import board.StoryBoard;
import org.eclipse.collections.api.PrimitiveIterable;
import org.eclipse.collections.api.list.primitive.DoubleList;
import org.eclipse.collections.api.list.primitive.LongList;
import summary.Sketch;
import summary.accumulator.Accumulator;

import java.util.List;

public class LinearAccProcessor<T, TL extends PrimitiveIterable> implements
        LinearQueryProcessor<T> {
    public int startIdx=0, endIdx=0;
    public Accumulator<T, TL> acc;
    public int accumulatorSize;
    public int span;

    public LinearAccProcessor(
            Accumulator<T, TL> acc,
            int accumulatorSize
    ) {
        this.acc = acc;
        this.accumulatorSize = accumulatorSize;
        this.span = 0;
    }

    @Override
    public DoubleList query(
            StoryBoard<T> board,
            List<T> xToTrack
    ) {
        acc.reset();
        span = 0;
        LongList tValues = board.dimensionCols.get(0);
        for (int i = 0; i < tValues.size(); i++) {
            long curT = tValues.get(i);
            if (curT >= startIdx && curT < endIdx) {
                Sketch<T> curSketch = board.sketchCol.get(i);
                acc.addSketch(curSketch);
                span++;
                if (accumulatorSize > 0) {
                    acc.compress(accumulatorSize);
                }
            }
        }
        return acc.estimate(xToTrack);
    }

    @Override
    public double total(StoryBoard<T> board) {
        double result = 0;
        span = 0;
        LongList tValues = board.dimensionCols.get(0);
        for (int i = 0; i < tValues.size(); i++) {
            long curT = tValues.get(i);
            if (curT >= startIdx && curT < endIdx) {
                span++;
                result += board.totalCol.get(i);
            }
        }
        return result;
    }

    @Override
    public int span() {
        return span;
    }

    @Override
    public void setRange(int startIdx, int endIdx) {
        this.startIdx = startIdx;
        this.endIdx = endIdx;
    }
}
