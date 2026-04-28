package mars.simulator;

import mars.Globals;
import mars.MIPSprogram;
import mars.ProcessingException;
import mars.ProgramStatement;
import mars.mips.hardware.AddressErrorException;
import mars.mips.hardware.Memory;
import mars.mips.hardware.RegisterFile;
import mars.mips.instructions.Instruction;
import mars.util.Binary;

import java.util.ArrayList;

/**
 * Educational five-stage pipeline simulator.
 *
 * This engine is intentionally scoped for a first release:
 * arithmetic/logical R-type ops, addi, lw, sw, beq, j and nop.
 */
public class PipelineEngine {
   private static final String[] STAGE_NAMES = { "IF", "ID", "EX", "MEM", "WB" };

   private final MIPSprogram program;

   private PipeReg ifId;
   private PipeReg idEx;
   private PipeReg exMem;
   private PipeReg memWb;
   private int fetchProgramCounter;
   private boolean fetchExhausted;
   private boolean finished;
   private long cycleCount;
   private long retiredInstructionCount;
   private boolean stalledLastCycle;
   private boolean flushedLastCycle;
   private String forwardingSummary;
   private String statusMessage;
   private int[] timelineInstructionAddresses;
   private String[] timelineInstructionLabels;
   private ArrayList timelineColumns;
   private ArrayList timelineEvents;

   public PipelineEngine(MIPSprogram program) {
      this.program = program;
      reset();
   }

   public void reset() {
      ifId = PipeReg.empty();
      idEx = PipeReg.empty();
      exMem = PipeReg.empty();
      memWb = PipeReg.empty();
      fetchProgramCounter = RegisterFile.getProgramCounter();
      fetchExhausted = false;
      finished = false;
      cycleCount = 0;
      retiredInstructionCount = 0;
      stalledLastCycle = false;
      flushedLastCycle = false;
      forwardingSummary = "none";
      statusMessage = "Pipeline reset";
      initializeTimelineRows();
      timelineColumns = new ArrayList();
      timelineEvents = new ArrayList();
      RegisterFile.initializeProgramCounter(fetchProgramCounter);
   }

   public boolean isFinished() {
      return finished;
   }

   public PipelineSnapshot getSnapshot() {
      String[] descriptions = new String[STAGE_NAMES.length];
      int[] addresses = new int[STAGE_NAMES.length];
      descriptions[0] = fetchExhausted ? "drain" : "next fetch @ " + Integer.toHexString(fetchProgramCounter);
      descriptions[1] = describeStage(ifId);
      descriptions[2] = describeStage(idEx);
      descriptions[3] = describeStage(exMem);
      descriptions[4] = describeStage(memWb);
      addresses[0] = fetchProgramCounter;
      addresses[1] = ifId.pc;
      addresses[2] = idEx.pc;
      addresses[3] = exMem.pc;
      addresses[4] = memWb.pc;
      return new PipelineSnapshot(
            cycleCount,
            retiredInstructionCount,
            fetchProgramCounter,
            stalledLastCycle,
            flushedLastCycle,
            forwardingSummary,
            statusMessage,
            getHighlightAddress(),
            finished,
            descriptions,
            addresses,
            timelineInstructionLabels,
            timelineInstructionAddresses,
            buildTimelineMatrix(),
            buildCycleEvents());
   }

   public boolean stepCycle() throws ProcessingException {
      if (finished) {
         statusMessage = "Program already finished";
         return true;
      }

      cycleCount++;
      stalledLastCycle = false;
      flushedLastCycle = false;
      forwardingSummary = "none";

      PipeReg wbThisCycle = memWb;
      PipeReg memThisCycle = exMem;
      PipeReg exThisCycle = idEx;
      PipeReg idThisCycle = ifId;

      retire(memWb);

      PipeReg nextMemWb = buildMemStage(exMem);
      ExecuteResult executeResult = buildExecuteStage(idEx, exMem, memWb);
      PipeReg nextExMem = executeResult.nextRegister;
      forwardingSummary = executeResult.forwardingSummary;

      boolean loadUseStall = hasLoadUseHazard(idEx, ifId);
      PipeReg nextIdEx = PipeReg.empty();
      if (loadUseStall) {
         stalledLastCycle = true;
         statusMessage = "Load-use stall inserted";
      }
      else if (!executeResult.flushFetchDecode) {
         nextIdEx = decode(ifId);
      }

      PipeReg nextIfId = PipeReg.empty();
      PipeReg fetchedThisCycle = PipeReg.empty();
      if (executeResult.flushFetchDecode) {
         flushedLastCycle = true;
         statusMessage = "Control hazard flush";
      }
      else if (loadUseStall) {
         nextIfId = ifId.copy();
      }
      else {
         nextIfId = fetch();
         fetchedThisCycle = nextIfId.copy();
      }

      memWb = nextMemWb;
      exMem = nextExMem;
      idEx = nextIdEx;
      ifId = nextIfId;
      RegisterFile.initializeProgramCounter(fetchProgramCounter);

      finished = fetchExhausted && ifId.isEmpty() && idEx.isEmpty() && exMem.isEmpty() && memWb.isEmpty();
      if (finished) {
         statusMessage = "Pipeline drained";
      }
      else if (!stalledLastCycle && !flushedLastCycle) {
         statusMessage = "Cycle completed";
      }
      recordTimelineColumn(fetchedThisCycle, idThisCycle, exThisCycle, memThisCycle, wbThisCycle, buildEventLabel());
      return finished;
   }

   public boolean stepUntilCommit() throws ProcessingException {
      long retiredBefore = retiredInstructionCount;
      boolean done = false;
      do {
         done = stepCycle();
      }
      while (!done && retiredInstructionCount == retiredBefore);
      return done;
   }

   private void retire(PipeReg wb) {
      if (wb == null || wb.isEmpty()) {
         return;
      }
      if (wb.regWrite && wb.destinationRegister > 0) {
         RegisterFile.updateRegister(wb.destinationRegister, wb.resultValue);
      }
      retiredInstructionCount++;
   }

   private PipeReg buildMemStage(PipeReg input) throws ProcessingException {
      if (input == null || input.isEmpty()) {
         return PipeReg.empty();
      }
      PipeReg output = input.copy();
      try {
         if (input.memRead) {
            output.resultValue = Globals.memory.getWord(input.aluResult);
         }
         else if (input.memWrite) {
            Globals.memory.setWord(input.aluResult, input.storeValue);
            output.resultValue = input.storeValue;
         }
         else {
            output.resultValue = input.aluResult;
         }
      }
      catch (AddressErrorException e) {
         throw new ProcessingException(input.statement, e);
      }
      return output;
   }

   private ExecuteResult buildExecuteStage(PipeReg input, PipeReg exMemInput, PipeReg memWbInput) throws ProcessingException {
      if (input == null || input.isEmpty()) {
         return new ExecuteResult(PipeReg.empty(), false, "none");
      }

      PipeReg output = input.copy();
      String forwarding = "none";
      int operandA = input.sourceValueA;
      int operandB = input.sourceValueB;

      if (input.readsRegisterA) {
         ForwardResult forwardedA = applyForwarding(input.sourceRegisterA, operandA, exMemInput, memWbInput);
         operandA = forwardedA.value;
         forwarding = mergeForwarding(forwarding, forwardedA.description);
      }
      if (input.readsRegisterB) {
         ForwardResult forwardedB = applyForwarding(input.sourceRegisterB, operandB, exMemInput, memWbInput);
         operandB = forwardedB.value;
         forwarding = mergeForwarding(forwarding, forwardedB.description);
      }

      output.storeValue = operandB;

      if ("nop".equals(input.mnemonic)) {
         output.regWrite = false;
      }
      else if ("add".equals(input.mnemonic) || "addu".equals(input.mnemonic)) {
         output.aluResult = operandA + operandB;
      }
      else if ("sub".equals(input.mnemonic) || "subu".equals(input.mnemonic)) {
         output.aluResult = operandA - operandB;
      }
      else if ("and".equals(input.mnemonic)) {
         output.aluResult = operandA & operandB;
      }
      else if ("or".equals(input.mnemonic)) {
         output.aluResult = operandA | operandB;
      }
      else if ("nor".equals(input.mnemonic)) {
         output.aluResult = ~(operandA | operandB);
      }
      else if ("slt".equals(input.mnemonic)) {
         output.aluResult = (operandA < operandB) ? 1 : 0;
      }
      else if ("sll".equals(input.mnemonic)) {
         output.aluResult = operandB << input.immediateValue;
      }
      else if ("srl".equals(input.mnemonic)) {
         output.aluResult = operandB >>> input.immediateValue;
      }
      else if ("addi".equals(input.mnemonic) || "addiu".equals(input.mnemonic)) {
         output.aluResult = operandA + input.immediateValue;
      }
      else if ("lw".equals(input.mnemonic) || "sw".equals(input.mnemonic)) {
         output.aluResult = operandA + input.immediateValue;
      }
      else if ("beq".equals(input.mnemonic)) {
         if (operandA == operandB) {
            fetchProgramCounter = input.branchTarget;
            return new ExecuteResult(PipeReg.empty(), true, forwarding);
         }
         output.regWrite = false;
      }
      else if ("j".equals(input.mnemonic)) {
         fetchProgramCounter = input.branchTarget;
         return new ExecuteResult(PipeReg.empty(), true, forwarding);
      }
      else {
         throw unsupported(input.statement);
      }

      return new ExecuteResult(output, false, forwarding);
   }

   private PipeReg decode(PipeReg fetchRegister) throws ProcessingException {
      if (fetchRegister == null || fetchRegister.isEmpty()) {
         return PipeReg.empty();
      }

      ProgramStatement statement = fetchRegister.statement;
      PipeReg decoded = PipeReg.fromStatement(statement, fetchRegister.pc);
      String mnemonic = decoded.mnemonic;
      int[] operands = statement.getOperands();

      if ("nop".equals(mnemonic)) {
         return decoded;
      }
      if ("add".equals(mnemonic) || "addu".equals(mnemonic) || "sub".equals(mnemonic) || "subu".equals(mnemonic)
            || "and".equals(mnemonic) || "or".equals(mnemonic) || "nor".equals(mnemonic) || "slt".equals(mnemonic)) {
         decoded.destinationRegister = operands[0];
         decoded.sourceRegisterA = operands[1];
         decoded.sourceRegisterB = operands[2];
         decoded.readsRegisterA = true;
         decoded.readsRegisterB = true;
         decoded.sourceValueA = RegisterFile.getValue(decoded.sourceRegisterA);
         decoded.sourceValueB = RegisterFile.getValue(decoded.sourceRegisterB);
         decoded.regWrite = true;
      }
      else if ("sll".equals(mnemonic) || "srl".equals(mnemonic)) {
         decoded.destinationRegister = operands[0];
         decoded.sourceRegisterB = operands[1];
         decoded.immediateValue = operands[2];
         decoded.readsRegisterB = true;
         decoded.sourceValueB = RegisterFile.getValue(decoded.sourceRegisterB);
         decoded.regWrite = true;
      }
      else if ("addi".equals(mnemonic) || "addiu".equals(mnemonic)) {
         decoded.destinationRegister = operands[0];
         decoded.sourceRegisterA = operands[1];
         decoded.immediateValue = operands[2];
         decoded.readsRegisterA = true;
         decoded.sourceValueA = RegisterFile.getValue(decoded.sourceRegisterA);
         decoded.regWrite = true;
      }
      else if ("lw".equals(mnemonic)) {
         decoded.destinationRegister = operands[0];
         decoded.immediateValue = operands[1];
         decoded.sourceRegisterA = operands[2];
         decoded.readsRegisterA = true;
         decoded.sourceValueA = RegisterFile.getValue(decoded.sourceRegisterA);
         decoded.memRead = true;
         decoded.regWrite = true;
      }
      else if ("sw".equals(mnemonic)) {
         decoded.destinationRegister = -1;
         decoded.immediateValue = operands[1];
         decoded.sourceRegisterA = operands[2];
         decoded.sourceRegisterB = operands[0];
         decoded.readsRegisterA = true;
         decoded.readsRegisterB = true;
         decoded.sourceValueA = RegisterFile.getValue(decoded.sourceRegisterA);
         decoded.sourceValueB = RegisterFile.getValue(decoded.sourceRegisterB);
         decoded.memWrite = true;
      }
      else if ("beq".equals(mnemonic)) {
         decoded.sourceRegisterA = operands[0];
         decoded.sourceRegisterB = operands[1];
         decoded.readsRegisterA = true;
         decoded.readsRegisterB = true;
         decoded.sourceValueA = RegisterFile.getValue(decoded.sourceRegisterA);
         decoded.sourceValueB = RegisterFile.getValue(decoded.sourceRegisterB);
         decoded.branchTarget = decoded.pc + Instruction.INSTRUCTION_LENGTH + (operands[2] << 2);
      }
      else if ("j".equals(mnemonic)) {
         decoded.branchTarget = operands[0];
      }
      else {
         throw unsupported(statement);
      }
      return decoded;
   }

   private PipeReg fetch() throws ProcessingException {
      if (fetchExhausted) {
         return PipeReg.empty();
      }
      try {
         ProgramStatement statement = Globals.memory.getStatement(fetchProgramCounter);
         if (statement == null) {
            fetchExhausted = true;
            return PipeReg.empty();
         }
         PipeReg fetched = PipeReg.fromStatement(statement, fetchProgramCounter);
         fetchProgramCounter += Instruction.INSTRUCTION_LENGTH;
         return fetched;
      }
      catch (AddressErrorException e) {
         fetchExhausted = true;
         throw new ProcessingException(program.getMachineList().isEmpty() ? null : (ProgramStatement) program.getMachineList().get(0), e);
      }
   }

   private boolean hasLoadUseHazard(PipeReg executeRegister, PipeReg decodeRegister) {
      if (executeRegister == null || decodeRegister == null || executeRegister.isEmpty() || decodeRegister.isEmpty()) {
         return false;
      }
      if (!executeRegister.memRead || executeRegister.destinationRegister < 0) {
         return false;
      }
      int[] usedRegisters = getReadRegisters(decodeRegister.statement);
      for (int i = 0; i < usedRegisters.length; i++) {
         if (usedRegisters[i] == executeRegister.destinationRegister && usedRegisters[i] != 0) {
            return true;
         }
      }
      return false;
   }

   private int[] getReadRegisters(ProgramStatement statement) {
      if (statement == null || statement.getInstruction() == null) {
         return new int[0];
      }
      String mnemonic = statement.getInstruction().getName();
      int[] operands = statement.getOperands();
      if ("add".equals(mnemonic) || "addu".equals(mnemonic) || "sub".equals(mnemonic) || "subu".equals(mnemonic)
            || "and".equals(mnemonic) || "or".equals(mnemonic) || "nor".equals(mnemonic) || "slt".equals(mnemonic)) {
         return new int[] { operands[1], operands[2] };
      }
      if ("sll".equals(mnemonic) || "srl".equals(mnemonic)) {
         return new int[] { operands[1] };
      }
      if ("addi".equals(mnemonic) || "addiu".equals(mnemonic)) {
         return new int[] { operands[1] };
      }
      if ("lw".equals(mnemonic)) {
         return new int[] { operands[2] };
      }
      if ("sw".equals(mnemonic)) {
         return new int[] { operands[2], operands[0] };
      }
      if ("beq".equals(mnemonic)) {
         return new int[] { operands[0], operands[1] };
      }
      return new int[0];
   }

   private ForwardResult applyForwarding(int sourceRegister, int currentValue, PipeReg exMemInput, PipeReg memWbInput) {
      if (sourceRegister <= 0) {
         return new ForwardResult(currentValue, "none");
      }
      if (exMemInput != null && !exMemInput.isEmpty() && exMemInput.regWrite && !exMemInput.memRead
            && exMemInput.destinationRegister == sourceRegister) {
         return new ForwardResult(exMemInput.aluResult, "EX/MEM->r" + sourceRegister);
      }
      if (memWbInput != null && !memWbInput.isEmpty() && memWbInput.regWrite
            && memWbInput.destinationRegister == sourceRegister) {
         return new ForwardResult(memWbInput.resultValue, "MEM/WB->r" + sourceRegister);
      }
      return new ForwardResult(currentValue, "none");
   }

   private String mergeForwarding(String current, String update) {
      if (update == null || "none".equals(update)) {
         return current;
      }
      if (current == null || "none".equals(current)) {
         return update;
      }
      return current + ", " + update;
   }

   private int getHighlightAddress() {
      if (!memWb.isEmpty()) {
         return memWb.pc;
      }
      if (!exMem.isEmpty()) {
         return exMem.pc;
      }
      if (!idEx.isEmpty()) {
         return idEx.pc;
      }
      if (!ifId.isEmpty()) {
         return ifId.pc;
      }
      return fetchProgramCounter;
   }

   private String describeStage(PipeReg reg) {
      if (reg == null || reg.isEmpty() || reg.statement == null || reg.statement.getInstruction() == null) {
         return "bubble";
      }
      return reg.statement.getInstruction().getName() + " @ " + Integer.toHexString(reg.pc);
   }

   private void initializeTimelineRows() {
      ArrayList machineList = program.getMachineList();
      timelineInstructionAddresses = new int[machineList.size()];
      timelineInstructionLabels = new String[machineList.size()];
      for (int i = 0; i < machineList.size(); i++) {
         ProgramStatement statement = (ProgramStatement) machineList.get(i);
         timelineInstructionAddresses[i] = statement.getAddress();
         timelineInstructionLabels[i] =
            Binary.intToHexString(statement.getAddress()) + "  " + statement.getPrintableBasicAssemblyStatement();
      }
   }

   private void recordTimelineColumn(PipeReg fetchedThisCycle, PipeReg idThisCycle, PipeReg exThisCycle, PipeReg memThisCycle, PipeReg wbThisCycle, String eventLabel) {
      String[] column = new String[timelineInstructionAddresses.length];
      setTimelineCell(column, fetchedThisCycle, "IF");
      setTimelineCell(column, idThisCycle, "ID");
      setTimelineCell(column, exThisCycle, "EX");
      setTimelineCell(column, memThisCycle, "MEM");
      setTimelineCell(column, wbThisCycle, "WB");
      timelineColumns.add(column);
      timelineEvents.add(eventLabel);
   }

   private void setTimelineCell(String[] column, PipeReg reg, String stage) {
      if (reg == null || reg.isEmpty() || reg.statement == null) {
         return;
      }
      int row = findTimelineRow(reg.pc);
      if (row >= 0) {
         column[row] = stage;
      }
   }

   private int findTimelineRow(int address) {
      for (int i = 0; i < timelineInstructionAddresses.length; i++) {
         if (timelineInstructionAddresses[i] == address) {
            return i;
         }
      }
      return -1;
   }

   private String[][] buildTimelineMatrix() {
      String[][] matrix = new String[timelineInstructionAddresses.length][timelineColumns.size()];
      for (int column = 0; column < timelineColumns.size(); column++) {
         String[] currentColumn = (String[]) timelineColumns.get(column);
         for (int row = 0; row < timelineInstructionAddresses.length; row++) {
            matrix[row][column] = currentColumn[row];
         }
      }
      return matrix;
   }

   private String[] buildCycleEvents() {
      String[] events = new String[timelineEvents.size()];
      for (int i = 0; i < timelineEvents.size(); i++) {
         events[i] = (String) timelineEvents.get(i);
      }
      return events;
   }

   private String buildEventLabel() {
      StringBuffer label = new StringBuffer();
      if (stalledLastCycle) {
         label.append("STALL");
      }
      if (flushedLastCycle) {
         if (label.length() > 0) {
            label.append(" ");
         }
         label.append("FLUSH");
      }
      if (forwardingSummary != null && forwardingSummary.length() > 0 && !"none".equals(forwardingSummary)) {
         if (label.length() > 0) {
            label.append(" ");
         }
         label.append("FWD");
      }
      return label.toString();
   }

   private ProcessingException unsupported(ProgramStatement statement) {
      String name = (statement == null || statement.getInstruction() == null) ? "<invalid>" : statement.getInstruction().getName();
      return new ProcessingException(statement, "Pipelined mode does not yet support instruction '" + name + "'");
   }

   private static class ExecuteResult {
      private final PipeReg nextRegister;
      private final boolean flushFetchDecode;
      private final String forwardingSummary;

      private ExecuteResult(PipeReg nextRegister, boolean flushFetchDecode, String forwardingSummary) {
         this.nextRegister = nextRegister;
         this.flushFetchDecode = flushFetchDecode;
         this.forwardingSummary = forwardingSummary;
      }
   }

   private static class ForwardResult {
      private final int value;
      private final String description;

      private ForwardResult(int value, String description) {
         this.value = value;
         this.description = description;
      }
   }

   private static class PipeReg {
      private ProgramStatement statement;
      private int pc;
      private String mnemonic;
      private int sourceRegisterA = -1;
      private int sourceRegisterB = -1;
      private int destinationRegister = -1;
      private int sourceValueA;
      private int sourceValueB;
      private int immediateValue;
      private int branchTarget;
      private int aluResult;
      private int resultValue;
      private int storeValue;
      private boolean readsRegisterA;
      private boolean readsRegisterB;
      private boolean regWrite;
      private boolean memRead;
      private boolean memWrite;

      private static PipeReg empty() {
         return new PipeReg();
      }

      private static PipeReg fromStatement(ProgramStatement statement, int pc) {
         PipeReg reg = new PipeReg();
         reg.statement = statement;
         reg.pc = pc;
         reg.mnemonic = (statement == null || statement.getInstruction() == null) ? null : statement.getInstruction().getName();
         return reg;
      }

      private boolean isEmpty() {
         return statement == null;
      }

      private PipeReg copy() {
         PipeReg copy = new PipeReg();
         copy.statement = statement;
         copy.pc = pc;
         copy.mnemonic = mnemonic;
         copy.sourceRegisterA = sourceRegisterA;
         copy.sourceRegisterB = sourceRegisterB;
         copy.destinationRegister = destinationRegister;
         copy.sourceValueA = sourceValueA;
         copy.sourceValueB = sourceValueB;
         copy.immediateValue = immediateValue;
         copy.branchTarget = branchTarget;
         copy.aluResult = aluResult;
         copy.resultValue = resultValue;
         copy.storeValue = storeValue;
         copy.readsRegisterA = readsRegisterA;
         copy.readsRegisterB = readsRegisterB;
         copy.regWrite = regWrite;
         copy.memRead = memRead;
         copy.memWrite = memWrite;
         return copy;
      }
   }
}
