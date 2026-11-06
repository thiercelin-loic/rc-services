package com.rollercoaster.export;

import com.rollercoaster.export.ExportDtos.ExportJobReceipt;
import com.rollercoaster.export.ExportDtos.ExportRequest;

public interface ExportService {

    ExportJobReceipt exportPdf(ExportRequest request);

    ExportJobReceipt exportVisual(String reportId, String visualId, ExportRequest request);
}
