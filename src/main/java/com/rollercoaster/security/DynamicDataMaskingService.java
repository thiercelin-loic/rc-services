package com.rollercoaster.security;

import com.rollercoaster.model.ColumnDefinition;
import com.rollercoaster.model.SemanticModelSchema;
import com.rollercoaster.model.TableDefinition;
import org.springframework.stereotype.Service;

import java.util.*;

@Service
public class DynamicDataMaskingService {

    public List<Map<String, Object>> maskQueryResultRows(
            List<Map<String, Object>> resultRows,
            SemanticModelSchema model,
            boolean isPrivileged) {

        if (resultRows == null || resultRows.isEmpty() || isPrivileged) {
            return resultRows;
        }

        Set<String> piiColumns = new HashSet<>();
        if (model != null && model.tables() != null) {
            for (TableDefinition table : model.tables()) {
                if (table.columns() != null) {
                    for (ColumnDefinition col : table.columns()) {
                        if (col.piiClassification() != null || col.isHidden()) {
                            piiColumns.add(col.name());
                        }
                    }
                }
            }
        }

        if (piiColumns.isEmpty()) {
            return resultRows;
        }

        List<Map<String, Object>> maskedResult = new ArrayList<>(resultRows.size());
        for (Map<String, Object> row : resultRows) {
            Map<String, Object> maskedRow = new HashMap<>(row);
            for (String piiCol : piiColumns) {
                if (maskedRow.containsKey(piiCol)) {
                    Object val = maskedRow.get(piiCol);
                    if (val != null) {
                        maskedRow.put(piiCol, maskValue(val.toString()));
                    }
                }
            }
            maskedResult.add(maskedRow);
        }
        return maskedResult;
    }

    private String maskValue(String val) {
        if (val.contains("@")) {
            int atIdx = val.indexOf('@');
            return val.charAt(0) + "****" + val.substring(atIdx);
        }
        if (val.length() > 4) {
            return "****-****-" + val.substring(val.length() - 4);
        }
        return "****";
    }
}
