import java.util.ArrayList;
import java.util.List;

final class ImportRecord {
  String id = "";
  String month = "";
  String dataset = "";
  String period = "";
  String filename = "";
  String sheetName = "";
  String importedAt = "";
  String updatedAt = "";
  int headerRows;
  List<String> columns = new ArrayList<String>();
  List<List<String>> rows = new ArrayList<List<String>>();

  String datasetLabel() {
    return "multi".equals(dataset) ? "多重预警排查清单" : "负面闭环清单";
  }
}

final class RowRef {
  final ImportRecord record;
  final int rowIndex;
  final List<String> values;

  RowRef(ImportRecord record, int rowIndex, List<String> values) {
    this.record = record;
    this.rowIndex = rowIndex;
    this.values = values;
  }
}

final class CellUpdate {
  String month = "";
  String recordId = "";
  int rowIndex;
  int columnIndex;
  String value = "";
}

final class ImportResult {
  ImportRecord record;
  boolean replaced;
  int previousRows;
}

final class WorkbookImportException extends Exception {
  WorkbookImportException(String message) { super(message); }
  WorkbookImportException(String message, Throwable cause) { super(message, cause); }
}
