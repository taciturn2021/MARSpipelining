package mars.venus;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Font;
import java.awt.GridLayout;

import javax.swing.JInternalFrame;
import javax.swing.JLabel;
import javax.swing.JScrollPane;
import javax.swing.JTable;
import javax.swing.JPanel;
import javax.swing.SwingConstants;
import javax.swing.table.AbstractTableModel;
import javax.swing.table.DefaultTableCellRenderer;

import mars.simulator.PipelineSnapshot;
import mars.util.Binary;

/**
 * Pipeline state viewer with a cycle timeline diagram.
 */
public class PipelineWindow extends JInternalFrame {
   private final JLabel cycleValue;
   private final JLabel retiredValue;
   private final JLabel fetchPcValue;
   private final JLabel stallValue;
   private final JLabel flushValue;
   private final JLabel forwardingValue;
   private final JLabel statusValue;
   private final PipelineTimelineModel timelineModel;
   private final JTable timelineTable;

   public PipelineWindow() {
      super("Pipeline", true, false, true, true);
      cycleValue = new JLabel("-");
      retiredValue = new JLabel("-");
      fetchPcValue = new JLabel("-");
      stallValue = new JLabel("-");
      flushValue = new JLabel("-");
      forwardingValue = new JLabel("-");
      statusValue = new JLabel("-");
      timelineModel = new PipelineTimelineModel();
      timelineTable = new JTable(timelineModel);
      timelineTable.setAutoResizeMode(JTable.AUTO_RESIZE_OFF);
      timelineTable.setRowSelectionAllowed(false);
      timelineTable.setCellSelectionEnabled(false);
      timelineTable.setDefaultRenderer(Object.class, new TimelineCellRenderer(timelineModel));
      timelineTable.getTableHeader().setReorderingAllowed(false);
      timelineTable.setFont(new Font("Monospaced", Font.PLAIN, 12));
      getContentPane().setLayout(new BorderLayout());
      getContentPane().add(buildSummaryPanel(), BorderLayout.NORTH);
      getContentPane().add(new JScrollPane(timelineTable), BorderLayout.CENTER);
      updateSnapshot(null);
      pack();
   }

   public void updateSnapshot(PipelineSnapshot snapshot) {
      if (snapshot == null) {
         cycleValue.setText("-");
         retiredValue.setText("-");
         fetchPcValue.setText("-");
         stallValue.setText("-");
         flushValue.setText("-");
         forwardingValue.setText("-");
         statusValue.setText("Select Pipelined mode and assemble a program to view the pipeline.");
         timelineModel.setSnapshot(null);
         return;
      }
      cycleValue.setText(Long.toString(snapshot.getCycleCount()));
      retiredValue.setText(Long.toString(snapshot.getRetiredInstructionCount()));
      fetchPcValue.setText(Binary.intToHexString(snapshot.getFetchProgramCounter()));
      stallValue.setText(snapshot.isStalled() ? "yes" : "no");
      flushValue.setText(snapshot.isFlushed() ? "yes" : "no");
      forwardingValue.setText(snapshot.getForwardingSummary());
      statusValue.setText(snapshot.getStatusMessage());
      timelineModel.setSnapshot(snapshot);
      timelineTable.revalidate();
      if (timelineTable.getColumnModel().getColumnCount() > 0) {
         timelineTable.getColumnModel().getColumn(0).setPreferredWidth(220);
         for (int i = 1; i < timelineTable.getColumnModel().getColumnCount(); i++) {
            timelineTable.getColumnModel().getColumn(i).setPreferredWidth(48);
         }
      }
   }

   private JPanel buildSummaryPanel() {
      JPanel summaryPanel = new JPanel(new GridLayout(4, 2, 6, 2));
      summaryPanel.add(buildMetricLabel("Cycle:", cycleValue));
      summaryPanel.add(buildMetricLabel("Retired:", retiredValue));
      summaryPanel.add(buildMetricLabel("Fetch PC:", fetchPcValue));
      summaryPanel.add(buildMetricLabel("Forwarding:", forwardingValue));
      summaryPanel.add(buildMetricLabel("Stall:", stallValue));
      summaryPanel.add(buildMetricLabel("Flush:", flushValue));
      summaryPanel.add(buildMetricLabel("Status:", statusValue));
      summaryPanel.add(new JLabel(""));
      return summaryPanel;
   }

   private JPanel buildMetricLabel(String title, JLabel valueLabel) {
      JPanel panel = new JPanel(new BorderLayout());
      JLabel titleLabel = new JLabel(title);
      titleLabel.setHorizontalAlignment(SwingConstants.LEFT);
      valueLabel.setHorizontalAlignment(SwingConstants.LEFT);
      panel.add(titleLabel, BorderLayout.WEST);
      panel.add(valueLabel, BorderLayout.CENTER);
      return panel;
   }

   private static class PipelineTimelineModel extends AbstractTableModel {
      private PipelineSnapshot snapshot;

      public void setSnapshot(PipelineSnapshot snapshot) {
         this.snapshot = snapshot;
         fireTableStructureChanged();
      }

      public int getRowCount() {
         return (snapshot == null || snapshot.getInstructionLabels() == null) ? 0 : snapshot.getInstructionLabels().length;
      }

      public int getColumnCount() {
         if (snapshot == null) {
            return 1;
         }
         String[][] timeline = snapshot.getTimelineCells();
         return 1 + ((timeline.length == 0) ? 0 : timeline[0].length);
      }

      public Object getValueAt(int rowIndex, int columnIndex) {
         if (snapshot == null) {
            return "";
         }
         if (columnIndex == 0) {
            return snapshot.getInstructionLabels()[rowIndex];
         }
         return snapshot.getTimelineCells()[rowIndex][columnIndex - 1];
      }

      public String getColumnName(int column) {
         if (column == 0) {
            return "Instruction";
         }
         return "C" + column;
      }

      public PipelineSnapshot getSnapshot() {
         return snapshot;
      }
   }

   private static class TimelineCellRenderer extends DefaultTableCellRenderer {
      private final PipelineTimelineModel model;
      private final Color currentCycleColor = new Color(255, 243, 205);
      private final Color activeRowColor = new Color(225, 240, 255);
      private final Color ifColor = new Color(214, 234, 248);
      private final Color idColor = new Color(217, 247, 190);
      private final Color exColor = new Color(255, 230, 204);
      private final Color memColor = new Color(242, 220, 255);
      private final Color wbColor = new Color(255, 214, 220);

      TimelineCellRenderer(PipelineTimelineModel model) {
         this.model = model;
         setHorizontalAlignment(SwingConstants.CENTER);
         setFont(new Font("Monospaced", Font.PLAIN, 12));
      }

      public Component getTableCellRendererComponent(JTable table, Object value,
            boolean isSelected, boolean hasFocus, int row, int column) {
         JLabel cell = (JLabel) super.getTableCellRendererComponent(table, value, isSelected, hasFocus, row, column);
         PipelineSnapshot snapshot = model.getSnapshot();
         cell.setOpaque(true);
         cell.setForeground(Color.BLACK);

         boolean currentCycleColumn = snapshot != null && column > 0 && column == snapshot.getCycleCount();
         boolean activeRow = snapshot != null
            && snapshot.getInstructionRowAddresses() != null
            && row < snapshot.getInstructionRowAddresses().length
            && snapshot.getInstructionRowAddresses()[row] == snapshot.getHighlightAddress();

         if (column == 0) {
            cell.setHorizontalAlignment(SwingConstants.LEFT);
            cell.setBackground(activeRow ? activeRowColor : Color.WHITE);
            cell.setFont(new Font("Monospaced", activeRow ? Font.BOLD : Font.PLAIN, 12));
            return cell;
         }

         cell.setHorizontalAlignment(SwingConstants.CENTER);
         cell.setFont(new Font("Monospaced", Font.BOLD, 12));
         String stage = (value == null) ? "" : value.toString();
         if ("IF".equals(stage)) {
            cell.setBackground(ifColor);
         }
         else if ("ID".equals(stage)) {
            cell.setBackground(idColor);
         }
         else if ("EX".equals(stage)) {
            cell.setBackground(exColor);
         }
         else if ("MEM".equals(stage)) {
            cell.setBackground(memColor);
         }
         else if ("WB".equals(stage)) {
            cell.setBackground(wbColor);
         }
         else if (currentCycleColumn) {
            cell.setBackground(currentCycleColor);
         }
         else {
            cell.setBackground(Color.WHITE);
         }

         if (currentCycleColumn && stage.length() > 0) {
            cell.setBackground(cell.getBackground().darker());
         }
         return cell;
      }
   }
}
