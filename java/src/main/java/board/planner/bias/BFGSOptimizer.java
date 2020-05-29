package board.planner.bias;

import com.github.lbfgs4j.LbfgsMinimizer;
import com.github.lbfgs4j.liblbfgs.Function;
import com.github.lbfgs4j.liblbfgs.Lbfgs;
import com.github.lbfgs4j.liblbfgs.LbfgsConstant;
import com.github.lbfgs4j.liblbfgs.MutableDouble;

import static com.github.lbfgs4j.liblbfgs.LbfgsConstant.ReturnValue.LBFGS_CONVERGENCE;
import static com.github.lbfgs4j.liblbfgs.LbfgsConstant.ReturnValue.LBFGS_SUCCESS;
import static java.lang.System.out;

public class BFGSOptimizer {
    public FunctionWithGrad P;
    private int maxIter;
    private int stepCount;
    private boolean converged;

    private boolean verbose;

    public BFGSOptimizer(FunctionWithGrad P) {
        this.P = P;
        this.maxIter = 100;
        this.stepCount = 0;
        this.converged = false;
    }
    public void setVerbose(boolean flag) {
        this.verbose = flag;
    }
    public void setMaxIter(int maxIter) {
        this.maxIter = maxIter;
    }
    public int getStepCount() {
        return stepCount;
    }

    public FunctionWithGrad getP() {
        return P;
    }
    public boolean isConverged() {
        return converged;
    }

    static class Evaluator implements LbfgsConstant.LBFGS_Evaluate {
        private FunctionWithGrad func;

        public Evaluator (
                FunctionWithGrad func
        ) {
            this.func = func;
        }

        public double eval(
                Object   instance,
                double[] x,
                double[] g,
                int      n,
                double   step
        )
        {
            func.compute(x);
            double[] grad = func.getGradient();
            System.arraycopy(grad, 0, g, 0, grad.length);
            return func.getValue();
        }
    }

    static class Progress implements LbfgsConstant.LBFGS_Progress {
        private int numSteps;
        private double gnorm;
        private double[] g;
        private boolean verbose;

        public Progress(boolean verbose) {
            this.verbose = verbose;
        }

        public LbfgsConstant.ReturnValue eval(
                Object   instance,
                double[] x,
                double[] g,
                double   fx,
                double   xnorm,
                double   gnorm,
                double   step,
                int      n,
                int      k,
                int      ls
        )
        {
            this.numSteps = k;
            this.gnorm = gnorm;
            this.g = g;
            if(verbose) {
                out.printf("Iteration %d:\n", k);
                out.printf("\tfx = %f, xnorm = %f, gnorm = %f, step = %f\n", fx, xnorm, gnorm, step);
                out.printf("\tn = %d, k = %d, ls = %d\n\n", n, k, ls);
            }
            return LBFGS_SUCCESS;
        }

        public int getNumSteps() {
            return numSteps;
        }
        public double[] getGradient() {
            return g;
        }
    }

    public double[] solve(double[] xArg, double gradTol) {
        double[] x = xArg.clone();
        LbfgsConstant.LBFGS_Param params = Lbfgs.defaultParams();
        params.max_iterations = maxIter;
        params.epsilon = gradTol;

        int dim = xArg.length;
        MutableDouble fx  = new MutableDouble();
        LbfgsConstant.LBFGS_Evaluate eval     = new Evaluator(P);
        LbfgsConstant.LBFGS_Progress progress = new Progress(verbose);
        LbfgsConstant.ReturnValue retVal = Lbfgs.lbfgs(
                dim, x, fx, eval, progress, null, params
        );

        stepCount = ((Progress) progress).getNumSteps();
        converged = (retVal == LBFGS_CONVERGENCE);
//        System.out.println("retval: "+retVal);
        return x;
    }
}
