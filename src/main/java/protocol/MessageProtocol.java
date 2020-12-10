package protocol;

import java.util.UUID;

public class MessageProtocol {

  public static final String BUCKET_PREFIX = "ass1ocr";
  public static final String DELIMITER_REGEX = "\\|\\|\\|";

  public static final String ERROR_PREFIX = "ERROR: ";

  // Application messages
  public static final String APP_MANAGER_TASK = "new_task";

  // Manager messages
  public static final String MANAGER_WORKER_TASK = "new_image_task";
  public static final String MANAGER_DONE = "done_task";
  public static final String MANAGER_TERMINATED = "terminated";

  // Worker messages
  public static final String WORKER_TASK_FINISH = "done_ocr_task";
  public static final String WORKER_TASK_FAIL = "task_fail";

  public static String createManagerTaskMessage(
      UUID id, String inputFilePath, int linesPerWorker, boolean terminate) {
    return String.format(
        "%s|||%s|||%s|||%d|||%b",
        APP_MANAGER_TASK, id, inputFilePath, linesPerWorker, terminate);
  }

  public static String createManagerResponseMessage() {
    return MANAGER_DONE;
  }

  public static String createManagerTerminationMessage() {
    return MANAGER_TERMINATED;
  }

  public static String createWorkerTaskMessage(UUID id, String line) {
    return String.format("%s|||%s|||%s", MANAGER_WORKER_TASK, id, line);
  }

  public static String createWorkerResponse(UUID id, String URL, String result) {
    return String.format("%s|||%s|||%s|||%s", WORKER_TASK_FINISH, id, URL, result);
  }

  public static String createWorkerFailMessage(String message) {
    return String.format("%s|||%s", WORKER_TASK_FAIL, message);
  }

  public static String createResultSummary(String URL, String result) {
    return String.format("%s|||%s", URL, result);
  }

  public static String[] split(String str) {
    return str.split(DELIMITER_REGEX);
  }
}
