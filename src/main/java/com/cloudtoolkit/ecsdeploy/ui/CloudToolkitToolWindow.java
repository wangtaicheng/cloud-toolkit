package com.cloudtoolkit.ecsdeploy.ui;

import com.cloudtoolkit.ecsdeploy.settings.EcsConnection;
import com.cloudtoolkit.ecsdeploy.settings.EcsConnectionsConfigurable;
import com.cloudtoolkit.ecsdeploy.settings.EcsConnectionsService;
import com.intellij.openapi.options.ShowSettingsUtil;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.ComboBox;
import com.intellij.openapi.ui.SimpleToolWindowPanel;
import com.intellij.ui.SearchTextField;
import com.intellij.ui.table.JBTable;
import com.intellij.util.ui.JBUI;
import com.intellij.openapi.wm.ToolWindow;
import com.intellij.openapi.wm.ToolWindowFactory;
import com.intellij.ui.content.Content;
import com.intellij.ui.content.ContentFactory;
import org.jetbrains.annotations.NotNull;

import javax.swing.JButton;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.table.DefaultTableModel;
import javax.swing.table.TableColumn;
import java.awt.BorderLayout;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * "Cloud Toolkit" operation interface, modelled after easy-deploy's DashboardView: a host table with
 * a Tag filter and search box, a toolbar (Add Host / Refresh / Settings), and per-row actions
 * (Deploy / Test / Edit / Delete). All data is loaded from {@code ~/.cloudidea/cloudtoolkit.db}.
 */
public final class CloudToolkitToolWindow implements ToolWindowFactory {

    private static final String[] COLUMNS = {"ID", "Address", "Username", "Tag", "Description", "Actions"};

    private JBTable hostTable;
    private ComboBox<String> tagComboBox;
    private SearchTextField searchInput;
    private Project project;

    @Override
    public void createToolWindowContent(@NotNull Project project, @NotNull ToolWindow toolWindow) {
        this.project = project;

        SimpleToolWindowPanel panel = new SimpleToolWindowPanel(true);
        panel.setToolbar(buildToolbar());
        initHostTable(panel);
        loadTableData();

        Content content = ContentFactory.getInstance().createContent(panel, "", false);
        toolWindow.getContentManager().addContent(content);
    }

    private JPanel buildToolbar() {
        initTagComboBox();
        initSearchField();

        JPanel northPanel = new JPanel(new GridBagLayout());

        JButton addBtn = new JButton("Add Host");
        addBtn.addActionListener(e -> {
            new AddHostDialog(project).show();
            loadTableData();
        });
        JButton refreshBtn = new JButton("Refresh");
        refreshBtn.addActionListener(e -> loadTableData());
        JButton settingsBtn = new JButton("Settings");
        settingsBtn.addActionListener(e -> ShowSettingsUtil.getInstance()
                .showSettingsDialog(project, EcsConnectionsConfigurable.class));

        GridBagConstraints c = new GridBagConstraints();
        c.insets = JBUI.insets(2, 4);
        c.anchor = GridBagConstraints.WEST;
        northPanel.add(tagComboBox, c);
        northPanel.add(searchInput, c);

        JPanel buttonBar = new JPanel(new java.awt.FlowLayout(java.awt.FlowLayout.RIGHT, 4, 0));
        buttonBar.add(addBtn);
        buttonBar.add(refreshBtn);
        buttonBar.add(settingsBtn);

        c.gridx = 2;
        c.weightx = 1.0;
        c.anchor = GridBagConstraints.EAST;
        northPanel.add(buttonBar, c);

        northPanel.setBorder(JBUI.Borders.empty(2, 0));
        return northPanel;
    }

    private void initTagComboBox() {
        tagComboBox = new ComboBox<>();
        tagComboBox.addItem("");
        for (String tag : EcsConnectionsService.getInstance().listTags()) {
            tagComboBox.addItem(tag);
        }
        tagComboBox.addActionListener(e -> loadTableData());
    }

    private void initSearchField() {
        searchInput = new SearchTextField() {
            @Override
            protected void onFieldCleared() {
                loadTableData();
            }
        };
        searchInput.getTextEditor().getEmptyText().appendText("IP | Name | Desc");
        searchInput.getTextEditor().addKeyListener(new java.awt.event.KeyAdapter() {
            @Override
            public void keyReleased(java.awt.event.KeyEvent e) {
                loadTableData();
            }
        });
    }

    private void initHostTable(SimpleToolWindowPanel panel) {
        hostTable = new JBTable();
        panel.setContent(new JScrollPane(hostTable));
    }

    private void loadTableData() {
        String tag = Objects.toString(tagComboBox.getSelectedItem(), "");
        String keyword = searchInput.getText().trim().toLowerCase();

        List<EcsConnection> hosts = new ArrayList<>();
        for (EcsConnection c : EcsConnectionsService.getInstance().list()) {
            if (!tag.isEmpty() && !tag.equals(c.tag)) {
                continue;
            }
            if (!keyword.isEmpty()) {
                String haystack = (c.host + " " + (c.displayName == null ? "" : c.displayName) + " "
                        + (c.remark == null ? "" : c.remark)).toLowerCase();
                if (!haystack.contains(keyword)) {
                    continue;
                }
            }
            hosts.add(c);
        }

        Object[][] data = new Object[hosts.size()][COLUMNS.length];
        for (int i = 0; i < hosts.size(); i++) {
            EcsConnection c = hosts.get(i);
            data[i][0] = c.id;
            data[i][1] = c.host + ":" + c.port;
            data[i][2] = c.username;
            data[i][3] = c.tag == null ? "" : c.tag;
            data[i][4] = c.remark == null ? "" : c.remark;
            data[i][5] = c.id;
        }

        DefaultTableModel model = new DefaultTableModel(data, COLUMNS) {
            @Override
            public boolean isCellEditable(int row, int column) {
                return column == 5;
            }
        };
        hostTable.setModel(model);
        hostTable.getEmptyText().setText("No hosts. Click \"Add Host\" to create one.");
        hostTable.removeColumn(hostTable.getColumn("ID"));

        TableColumn actionColumn = hostTable.getColumn("Actions");
        actionColumn.setCellRenderer(new HostActionCellRenderer(project, this::loadTableData));
        actionColumn.setCellEditor(new HostActionCellEditor(project, this::loadTableData));
        actionColumn.setMinWidth(360);
        actionColumn.setMaxWidth(460);
    }

    /** Renders the per-row action buttons. */
    private static final class HostActionCellRenderer
            extends javax.swing.table.DefaultTableCellRenderer {
        private final Project project;
        private final Runnable onChange;

        HostActionCellRenderer(Project project, Runnable onChange) {
            this.project = project;
            this.onChange = onChange;
        }

        @Override
        public java.awt.Component getTableCellRendererComponent(javax.swing.JTable table, Object value,
                                                               boolean isSelected, boolean hasFocus, int row, int column) {
            java.awt.Component c = super.getTableCellRendererComponent(table, value, isSelected, hasFocus, row, column);
            String id = Objects.toString(value);
            HostActionPanel panel = new HostActionPanel(id, project, onChange);
            panel.setForeground(c.getForeground());
            panel.setBackground(c.getBackground());
            return panel;
        }
    }

    /** Editor that makes the per-row action buttons clickable. */
    private static final class HostActionCellEditor extends javax.swing.AbstractCellEditor
            implements javax.swing.table.TableCellEditor {
        private final Project project;
        private final Runnable onChange;
        private HostActionPanel panel;

        HostActionCellEditor(Project project, Runnable onChange) {
            this.project = project;
            this.onChange = onChange;
        }

        @Override
        public java.awt.Component getTableCellEditorComponent(javax.swing.JTable table, Object value,
                                                             boolean isSelected, int row, int column) {
            String id = Objects.toString(value);
            panel = new HostActionPanel(id, project, onChange);
            return panel;
        }

        @Override
        public Object getCellEditorValue() {
            return panel == null ? "" : panel.getState();
        }

        @Override
        public boolean isCellEditable(java.util.EventObject anEvent) {
            return true;
        }
    }
}
