package mars.simulator;

/**
 * Execution model selector for the simulator UI.
 */
public final class ExecutionModel {
   public static final int CLASSIC = 0;
   public static final int PIPELINED = 1;

   private ExecutionModel() { }

   public static String getName(int model) {
      return (model == PIPELINED) ? "Pipelined" : "Classic";
   }
}
