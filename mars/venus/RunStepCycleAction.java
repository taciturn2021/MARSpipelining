package mars.venus;

import java.awt.event.ActionEvent;

import javax.swing.Icon;
import javax.swing.KeyStroke;
import javax.swing.JOptionPane;

import mars.Globals;
import mars.ProcessingException;
import mars.simulator.ExecutionModel;

/**
 * Advance the pipeline by a single clock cycle.
 */
public class RunStepCycleAction extends GuiAction {
   public RunStepCycleAction(String name, Icon icon, String descrip,
         Integer mnemonic, KeyStroke accel, VenusUI gui) {
      super(name, icon, descrip, mnemonic, accel, gui);
   }

   public void actionPerformed(ActionEvent e) {
      if (!FileStatus.isAssembled()) {
         JOptionPane.showMessageDialog(mainUI, "The program must be assembled before it can be run.");
         return;
      }
      if (Globals.getExecutionModel() != ExecutionModel.PIPELINED) {
         JOptionPane.showMessageDialog(mainUI, "Step Cycle is available only in Pipelined mode.");
         return;
      }
      ExecutePane executePane = mainUI.getMainPane().getExecutePane();
      try {
         boolean done = Globals.program.simulateCycleAtPC(this);
         executePane.refreshPipelineView();
         executePane.getRegistersWindow().updateRegisters();
         executePane.getCoprocessor1Window().updateRegisters();
         executePane.getCoprocessor0Window().updateRegisters();
         executePane.getDataSegmentWindow().updateValues();
         executePane.highlightPipelineState();
         FileStatus.set(done ? FileStatus.TERMINATED : FileStatus.RUNNABLE);
         mainUI.setReset(false);
         mainUI.setStarted(true);
      }
      catch (ProcessingException pe) {
         mainUI.getMessagesPane().postMarsMessage(pe.errors().generateErrorReport());
         executePane.refreshPipelineView();
         executePane.highlightPipelineState();
         FileStatus.set(FileStatus.TERMINATED);
      }
   }
}
