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
  java.time.LocalDate feedbackDeadline;
  long deadlineRevision;
  java.util.Set<String> requiredFields=java.util.Set.of();
  java.time.Instant feedbackAsOf=java.time.Instant.now();
  int headerRows;
  List<String> columns = new ArrayList<String>();
  List<List<String>> rows = new ArrayList<List<String>>();
  List<Long> versions = new ArrayList<Long>();
  String organizationId = "";
  java.util.Map<String,String> legacyExtras = java.util.Map.of();

  String datasetLabel() {
    return xinguan.platform.DatasetSchema.get(dataset).label;
  }
}

final class RowRef {
  final ImportRecord record;
  final int rowIndex;
  final List<String> values;

  boolean complete(){return xinguan.platform.DatasetSchema.get(record.dataset).complete(values,record.requiredFields);}
  boolean overdue(){return xinguan.platform.FeedbackTiming.overdue(record.feedbackDeadline,complete(),record.feedbackAsOf);}
  String rowClass(){return complete()?"row-complete":overdue()?"row-overdue":"row-pending";}
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
