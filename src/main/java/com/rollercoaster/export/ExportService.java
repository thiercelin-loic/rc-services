package com.rollercoaster.export;

import com.rollercoaster.export.ExportDtos.ExportJobReceipt;
import com.rollercoaster.export.ExportDtos.ExportRequest;

public interface ExportService {

    ExportJobReceipt exportReport(String reportId, ExportRequest request);
}
