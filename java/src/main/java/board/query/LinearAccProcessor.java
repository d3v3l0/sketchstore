package board.query;

import board.StoryBoard;
import org.eclipse.collections.api.PrimitiveIterable;
import org.eclipse.collections.api.list.primitive.DoubleList;
import org.eclipse.collections.api.list.primitive.LongList;
import summary.Sketch;
import summary.accumulator.Accumulator;

import java.util.List;

public class LinearAccProcessor<T, TL extends PrimitiveIterable> implements
        LinearSelector, QueryProcessor<T> {
    public int startIdx=0, endIdx=0;
    public Accumulator<T, TL> acc;

    public LinearAccProcessor(
            Accumulator<T, TL> acc
    ) {
        this.acc = acc;
    }

    @Override
    public DoubleList query(
            StoryBoard<T> board,
            List<T> xToTrack
    ) {
        acc.reset();
        LongList tValues = board.dimensionCols.get(0);
        for (int i = 0; i < tValues.size(); i++) {
            long curT = tValues.get(i);
            if (curT >= startIdx && curT < endIdx) {
                Sketch<T> curSketch = board.sketchCol.get(i);
                acc.addSketch(curSketch);
            }
        }
        return acc.estimate(xToTrack);
    }

    @Override
    public double total(StoryBoard<T> board) {
        double result = 0;
        LongList tValues = board.dimensionCols.get(0);
        for (int i = 0; i < tValues.size(); i++) {
            long curT = tValues.get(i);
            if (curT >= startIdx && curT < endIdx) {
                result += board.totalCol.get(i);
            }
        }
        return result;
    }

    @Override
    public void setRange(int startIdx, int endIdx) {
        this.startIdx = startIdx;
        this.endIdx = endIdx;
    }
}
