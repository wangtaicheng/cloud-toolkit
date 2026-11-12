package com.cloudtoolkit.ecsdeploy.settings;

import com.cloudtoolkit.ecsdeploy.db.CloudToolkitDb;
import com.intellij.openapi.options.Configurable;
import com.intellij.openapi.ui.Messages;
import org.jetbrains.annotations.Nullable;

import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTable;
import javax.swing.JTextField;
import javax.swing.table.DefaultTableModel;
import java.awt.BorderLayout;
import java.awt.FlowLayout;
import java.awt.GridLayout;

/**
 * Manages SSH host tags. Selecting a row echoes its name into the form so the user can edit it.
 */
public final class TagsConfigurable implements Configurable {

    private final DefaultTableModel tableModel = new DefaultTableModel(new String[]{"ID", "名称"}, 0) {
        @Override
        public boolean isCellEditable(int row, int column) {
            return false;
        }
    };
    private final JTable table = new JTable(tableModel);
    private final JTextField nameField = new JTextField();
    private int selectedId;
    private boolean modified;

    @Override
    public String getDisplayName() {
        return "标签 Tags";
    }

    @Override
    public @Nullable JComponent createComponent() {
        JPanel form = new JPanel(new GridLayout(0, 2, 4, 2));
        form.add(new JLabel("标签名称"));
        form.add(nameField);

        JButton save = new JButton("保存");
        JButton delete = new JButton("删除");
        JButton clear = new JButton("清空");
        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.LEFT));
        buttons.add(save);
        buttons.add(delete);
        buttons.add(clear);

        selectedId = 0;
        save.addActionListener(event -> save());
        delete.addActionListener(event -> deleteSelected());
        clear.addActionListener(event -> clearForm());
        table.getSelectionModel().addListSelectionListener(event -> fillFromSelection());

        JPanel root = new JPanel(new BorderLayout());
        root.add(form, BorderLayout.NORTH);
        root.add(new JScrollPane(table), BorderLayout.CENTER);
        root.add(buttons, BorderLayout.SOUTH);
        refreshTable();
        return root;
    }

    @Override
    public boolean isModified() {
        return modified;
    }

    @Override
    public void apply() {
        modified = false;
    }

    @Override
    public void reset() {
        modified = false;
        clearForm();
        refreshTable();
    }

    private void save() {
        String name = nameField.getText().trim();
        if (name.isEmpty()) {
            Messages.showWarningDialog("标签名称不能为空", "Cloud Toolkit");
            return;
        }
        try {
            if (selectedId == 0) {
                if (EcsConnectionsService.getInstance().findTagByName(name) != null) {
                    Messages.showWarningDialog("标签已存在：" + name, "Cloud Toolkit");
                    return;
                }
                EcsConnectionsService.getInstance().createTag(name);
            } else {
                EcsConnectionsService.getInstance().updateTag(selectedId, name);
            }
            modified = true;
            clearForm();
            refreshTable();
        } catch (RuntimeException e) {
            Messages.showErrorDialog(e.getMessage(), "Cloud Toolkit");
        }
    }

    private void deleteSelected() {
        if (selectedId == 0) {
            Messages.showWarningDialog("请先选择要删除的标签", "Cloud Toolkit");
            return;
        }
        try {
            EcsConnectionsService.getInstance().deleteTag(selectedId);
            modified = true;
            clearForm();
            refreshTable();
        } catch (RuntimeException e) {
            Messages.showErrorDialog(e.getMessage(), "Cloud Toolkit");
        }
    }

    private void clearForm() {
        selectedId = 0;
        nameField.setText("");
    }

    /** Echo the selected tag's name back into the form (回显). */
    private void fillFromSelection() {
        int row = table.getSelectedRow();
        if (row < 0) {
            return;
        }
        selectedId = Integer.parseInt(String.valueOf(tableModel.getValueAt(row, 0)));
        nameField.setText(String.valueOf(tableModel.getValueAt(row, 1)));
    }

    private void refreshTable() {
        tableModel.setRowCount(0);
        for (CloudToolkitDb.TagRow tag : EcsConnectionsService.getInstance().listTagRows()) {
            tableModel.addRow(new Object[]{tag.id, tag.name});
        }
    }
}
