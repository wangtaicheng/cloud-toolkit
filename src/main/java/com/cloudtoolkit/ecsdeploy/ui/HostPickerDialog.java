package com.cloudtoolkit.ecsdeploy.ui;

import com.cloudtoolkit.ecsdeploy.settings.EcsConnection;
import com.cloudtoolkit.ecsdeploy.settings.EcsConnectionsService;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.ComboBox;
import com.intellij.openapi.ui.DialogWrapper;
import com.intellij.ui.SearchTextField;
import com.intellij.ui.components.JBScrollPane;
import com.intellij.ui.table.JBTable;
import org.jetbrains.annotations.Nullable;

import javax.swing.JComponent;
import javax.swing.JPanel;
import javax.swing.ListSelectionModel;
import javax.swing.table.DefaultTableModel;
import java.awt.BorderLayout;
import java.awt.Dimension;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Dialog used by the "add host" button of the Target Host table: lists all configured hosts
 * (filtered by tag / keyword) and lets the user pick one or more of them.
 */
public final class HostPickerDialog extends DialogWrapper {

    private final ComboBox<String> tagCombo = new ComboBox<>();
    private final SearchTextField searchField = new SearchTextField();
    private final JBTable table = new JBTable();
    private final DefaultTableModel model;
    private final List<EcsConnection> visible = new ArrayList<>();

    public HostPickerDialog(@Nullable Project project) {
        super(project, true);
        setTitle("选择目标主机");
        model = new DefaultTableModel(new Object[0][0], new String[]{"IP", "Tag"}) {
            @Override
            public boolean isCellEditable(int row, int column) {
                return false;
            }
        };
        table.setModel(model);
        table.setSelectionMode(ListSelectionModel.MULTIPLE_INTERVAL_SELECTION);
        table.setShowGrid(false);
        table.getEmptyText().setText("没有可选主机");
        table.getColumnModel().getColumn(0).setPreferredWidth(220);
        table.getColumnModel().getColumn(1).setPreferredWidth(140);

        tagCombo.addItem("");
        for (String tag : EcsConnectionsService.getInstance().listTags()) {
            tagCombo.addItem(tag);
        }
        tagCombo.addActionListener(e -> refresh());
        searchField.getTextEditor().addKeyListener(new java.awt.event.KeyAdapter() {
            @Override
            public void keyReleased(java.awt.event.KeyEvent e) {
                refresh();
            }
        });
        refresh();
        init();
    }

    private void refresh() {
        String tag = Objects.toString(tagCombo.getSelectedItem(), "");
        String keyword = searchField.getText().trim().toLowerCase();
        visible.clear();
        for (EcsConnection connection : EcsConnectionsService.getInstance().list()) {
            if (!tag.isEmpty() && !tag.equals(connection.tag)) {
                continue;
            }
            if (!keyword.isEmpty() && !connection.host.toLowerCase().contains(keyword)) {
                continue;
            }
            visible.add(connection);
        }
        Object[][] data = new Object[visible.size()][2];
        for (int i = 0; i < visible.size(); i++) {
            EcsConnection connection = visible.get(i);
            data[i][0] = connection.host + ":" + connection.port;
            data[i][1] = connection.tag == null ? "" : connection.tag;
        }
        model.setDataVector(data, new String[]{"IP", "Tag"});
    }

    /** The hosts selected by the user. */
    public List<EcsConnection> selectedHosts() {
        List<EcsConnection> result = new ArrayList<>();
        for (int row : table.getSelectedRows()) {
            int modelRow = table.convertRowIndexToModel(row);
            if (modelRow >= 0 && modelRow < visible.size()) {
                result.add(visible.get(modelRow));
            }
        }
        return result;
    }

    @Override
    protected @Nullable JComponent createCenterPanel() {
        JPanel filter = new JPanel(new BorderLayout(8, 0));
        filter.add(tagCombo, BorderLayout.WEST);
        filter.add(searchField, BorderLayout.CENTER);

        JBScrollPane scroll = new JBScrollPane(table);
        scroll.setPreferredSize(new Dimension(460, 260));

        JPanel root = new JPanel(new BorderLayout(0, 8));
        root.add(filter, BorderLayout.NORTH);
        root.add(scroll, BorderLayout.CENTER);
        return root;
    }
}
