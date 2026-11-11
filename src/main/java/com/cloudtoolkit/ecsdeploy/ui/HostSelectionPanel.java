package com.cloudtoolkit.ecsdeploy.ui;

import com.cloudtoolkit.ecsdeploy.settings.EcsConnection;
import com.intellij.icons.AllIcons;
import com.intellij.openapi.project.Project;
import com.intellij.ui.components.JBScrollPane;
import com.intellij.ui.table.JBTable;
import org.jetbrains.annotations.Nullable;

import javax.swing.JButton;
import javax.swing.JPanel;
import javax.swing.ListSelectionModel;
import javax.swing.table.DefaultTableModel;
import java.awt.BorderLayout;
import java.awt.Dimension;
import java.awt.GridLayout;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * Target host table (IP | Tag) with an "add" and a "delete" button, mirroring the Target Host
 * section of the Alibaba Cloud Toolkit deployment configuration.
 */
public final class HostSelectionPanel extends JPanel {

    private final JBTable table = new JBTable();
    private final DefaultTableModel model;
    private final List<EcsConnection> hosts = new ArrayList<>();
    private final JButton addButton = new JButton(AllIcons.General.Add);
    private final JButton removeButton = new JButton(AllIcons.General.Delete);
    private final Consumer<List<EcsConnection>> onChange;
    private Project project;

    public HostSelectionPanel(@Nullable Consumer<List<EcsConnection>> onChange) {
        super(new BorderLayout(8, 0));
        this.onChange = onChange;

        model = new DefaultTableModel(new Object[0][0], new String[]{"IP", "Tag"}) {
            @Override
            public boolean isCellEditable(int row, int column) {
                return false;
            }
        };
        table.setModel(model);
        table.setSelectionMode(ListSelectionModel.MULTIPLE_INTERVAL_SELECTION);
        table.setShowGrid(false);
        table.getEmptyText().setText("press \"+\" to add host");
        table.getColumnModel().getColumn(0).setPreferredWidth(220);
        table.getColumnModel().getColumn(1).setPreferredWidth(140);

        addButton.setToolTipText("添加目标主机");
        removeButton.setToolTipText("移除选中的主机");
        addButton.addActionListener(e -> addHosts());
        removeButton.addActionListener(e -> removeSelected());

        JPanel buttons = new JPanel(new GridLayout(2, 1, 0, 4));
        buttons.add(addButton);
        buttons.add(removeButton);

        JBScrollPane scroll = new JBScrollPane(table);
        scroll.setPreferredSize(new Dimension(420, 120));
        add(scroll, BorderLayout.CENTER);
        add(buttons, BorderLayout.EAST);
    }

    private void addHosts() {
        HostPickerDialog dialog = new HostPickerDialog(project);
        if (!dialog.showAndGet()) {
            return;
        }
        boolean changed = false;
        for (EcsConnection selected : dialog.selectedHosts()) {
            if (containsHost(selected)) {
                continue;
            }
            hosts.add(selected);
            changed = true;
        }
        if (changed) {
            refresh();
        }
    }

    private boolean containsHost(EcsConnection candidate) {
        for (EcsConnection host : hosts) {
            if (host.id != null && host.id.equals(candidate.id)) {
                return true;
            }
        }
        return false;
    }

    private void removeSelected() {
        int[] rows = table.getSelectedRows();
        if (rows.length == 0) {
            return;
        }
        for (int row : rows) {
            int modelRow = table.convertRowIndexToModel(row);
            if (modelRow >= 0 && modelRow < hosts.size()) {
                hosts.remove(modelRow);
            }
        }
        refresh();
    }

    /** Sets the project used by the host picker dialog. */
    public void setProject(@Nullable Project project) {
        this.project = project;
    }

    /** Replaces the selected hosts. */
    public void setHosts(List<EcsConnection> newHosts) {        hosts.clear();
        if (newHosts != null) {
            for (EcsConnection host : newHosts) {
                if (host != null) {
                    hosts.add(host);
                }
            }
        }
        refresh();
    }

    /** The currently selected hosts. */
    public List<EcsConnection> getHosts() {
        return new ArrayList<>(hosts);
    }

    private void refresh() {
        Object[][] data = new Object[hosts.size()][2];
        for (int i = 0; i < hosts.size(); i++) {
            EcsConnection host = hosts.get(i);
            data[i][0] = host.host + ":" + host.port;
            data[i][1] = host.tag == null ? "" : host.tag;
        }
        model.setDataVector(data, new String[]{"IP", "Tag"});
        if (onChange != null) {
            onChange.accept(getHosts());
        }
    }
}
