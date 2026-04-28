package mars.simulator;

/**
 * Immutable view of the current pipeline state for the UI.
 */
public class PipelineSnapshot {
   private final long cycleCount;
   private final long retiredInstructionCount;
   private final int fetchProgramCounter;
   private final boolean stalled;
   private final boolean flushed;
   private final String forwardingSummary;
   private final String statusMessage;
   private final int highlightAddress;
   private final boolean finished;
   private final String[] stageDescriptions;
   private final int[] stageAddresses;
   private final String[] instructionLabels;
   private final int[] instructionRowAddresses;
   private final String[][] timelineCells;
   private final String[] cycleEvents;

   public PipelineSnapshot(
         long cycleCount,
         long retiredInstructionCount,
         int fetchProgramCounter,
         boolean stalled,
         boolean flushed,
         String forwardingSummary,
         String statusMessage,
         int highlightAddress,
         boolean finished,
         String[] stageDescriptions,
         int[] stageAddresses,
         String[] instructionLabels,
         int[] instructionRowAddresses,
         String[][] timelineCells,
         String[] cycleEvents) {
      this.cycleCount = cycleCount;
      this.retiredInstructionCount = retiredInstructionCount;
      this.fetchProgramCounter = fetchProgramCounter;
      this.stalled = stalled;
      this.flushed = flushed;
      this.forwardingSummary = forwardingSummary;
      this.statusMessage = statusMessage;
      this.highlightAddress = highlightAddress;
      this.finished = finished;
      this.stageDescriptions = stageDescriptions;
      this.stageAddresses = stageAddresses;
      this.instructionLabels = instructionLabels;
      this.instructionRowAddresses = instructionRowAddresses;
      this.timelineCells = timelineCells;
      this.cycleEvents = cycleEvents;
   }

   public long getCycleCount() {
      return cycleCount;
   }

   public long getRetiredInstructionCount() {
      return retiredInstructionCount;
   }

   public int getFetchProgramCounter() {
      return fetchProgramCounter;
   }

   public boolean isStalled() {
      return stalled;
   }

   public boolean isFlushed() {
      return flushed;
   }

   public String getForwardingSummary() {
      return forwardingSummary;
   }

   public String getStatusMessage() {
      return statusMessage;
   }

   public int getHighlightAddress() {
      return highlightAddress;
   }

   public boolean isFinished() {
      return finished;
   }

   public String[] getStageDescriptions() {
      return stageDescriptions;
   }

   public int[] getStageAddresses() {
      return stageAddresses;
   }

   public String[] getInstructionLabels() {
      return instructionLabels;
   }

   public int[] getInstructionRowAddresses() {
      return instructionRowAddresses;
   }

   public String[][] getTimelineCells() {
      return timelineCells;
   }

   public String[] getCycleEvents() {
      return cycleEvents;
   }
}
