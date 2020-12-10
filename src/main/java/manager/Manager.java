package manager;

import adapters.S3Adapter;
import adapters.SQSAdapter;
import org.javatuples.Pair;
import protocol.MessageProtocol;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.sqs.SqsClient;
import software.amazon.awssdk.services.sqs.model.Message;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.UUID;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.LongAdder;
import java.util.stream.IntStream;

public class Manager {

  private static final Region REGION = Region.US_EAST_1;
  private static final String JAR_BUCKET =
      String.format("%s-%s", MessageProtocol.BUCKET_PREFIX, "jar-files");

  private static final double MEMORY_PERCENTAGE_THRESHOLD = 0.9;
  private static final int INACTIVE_KEEP_WORKER_MINUTE = 5;

  private static final ConcurrentHashMap<UUID, Pair<String, LongAdder>> applicationLines =
      new ConcurrentHashMap<>(); // Application id to application queue URL and current remaining tasks

  private static final String workerTasksQueueName = "worker_tasks";
  private static final String workerResponseQueueName = "worker_response";

  private static final Object TASK_START_LOCK = new Object();

  private static SqsClient sqs;
  private static S3Adapter storage;
  private static SQSAdapter appTasksQueue;
  private static SQSAdapter workerTasksQueue;
  private static SQSAdapter workerResponseQueue;
  private static WorkerHandler workerHandler;

  private static final int WORKER_LISTENERS_COUNT = 6;
  private static final int TASK_SENDERS_COUNT = 6;

  private static ExecutorService workerListeners =
      Executors.newFixedThreadPool(WORKER_LISTENERS_COUNT);
  private static ExecutorService taskHandlers = Executors.newFixedThreadPool(TASK_SENDERS_COUNT);
  private static ExecutorService appListener = Executors.newSingleThreadScheduledExecutor();

  private static AtomicBoolean terminated;
  private static volatile boolean started = false;

  public static void main(String[] args) {
    String appTasksQueueName = args[0];
    String workerAmi = args[1];
    String iamArn = args[2];
    String userId = args[3];

    sqs = SqsClient.builder().region(REGION).build();
    appTasksQueue = new SQSAdapter(sqs, appTasksQueueName, false);
    workerTasksQueue = new SQSAdapter(sqs, workerTasksQueueName, true);
    workerResponseQueue = new SQSAdapter(sqs, workerResponseQueueName, true);
    workerHandler = new WorkerHandler(REGION, workerAmi, iamArn, userId, getWorkerBashScript());

    storage = new S3Adapter(REGION);
    terminated = new AtomicBoolean(false);
    start();
  }

  private static void start() {
    appListener.execute(Manager::appTaskListener);
    IntStream.range(0, WORKER_LISTENERS_COUNT)
        .forEach(i -> workerListeners.execute(Manager::workerResponseListener));

    awaitTermination();
    workerHandler.terminateWorkers();
    terminate();
  }

  private static void awaitTermination() {
    try {
      appListener.shutdown();
      appListener.awaitTermination(Long.MAX_VALUE, TimeUnit.MINUTES);
      workerListeners.shutdown();
      workerListeners.awaitTermination(Long.MAX_VALUE, TimeUnit.MINUTES);

      taskHandlers.shutdown();
      if (!taskHandlers.awaitTermination(Long.MAX_VALUE, TimeUnit.MINUTES)) {
        taskHandlers.shutdownNow();
      }
    } catch (InterruptedException e) {
      e.printStackTrace();
    }
  }

  private static void terminate() {
    workerTasksQueue.deleteQueue();
    workerResponseQueue.deleteQueue();
    appTasksQueue.deleteQueue();
    Runtime r = Runtime.getRuntime();
    try {
      r.exec("sudo shutdown -h now").waitFor();
    } catch (InterruptedException | IOException e) {
      System.exit(1);
    }
    System.exit(0);
  }

  private static void workerResponseListener() {
    while (!terminated.get() || (started && !applicationLines.isEmpty())) {
      workerResponseQueue.receiveMessageBatch(Manager::parseWorkerMessage);
    }
  }

  private static void appTaskListener() {
    while (!terminated.get()) {
      appTasksQueue.receiveMessage(Manager::parseApplicationMessage);
    }

    boolean hasMoreWaitingApps = true;
    while(hasMoreWaitingApps) {
      hasMoreWaitingApps = appTasksQueue.receiveMessage(Manager::notifyTermination);
    }
  }

  private static void parseApplicationMessage(Message message) {
    try {
      if (isOverMemoryThreshold()) return;
      String[] messageSplits = MessageProtocol.split(message.body());
      UUID appId = UUID.fromString(messageSplits[1]);
      String inputFileBucketKey = messageSplits[2];
      int URLPerWorker = Integer.parseInt(messageSplits[3]);
      handleApplicationTask(appId, inputFileBucketKey, URLPerWorker);
      terminated.compareAndSet(false, Boolean.parseBoolean(messageSplits[4]));
      appTasksQueue.deleteMessage(message);
    } catch (InterruptedException | ExecutionException | IOException e) {
      e.printStackTrace();
    }
  }

  private static void notifyTermination(Message message) {
    try {
      String[] messageSplits = MessageProtocol.split(message.body());
      UUID appId = UUID.fromString(messageSplits[1]);
      new SQSAdapter(sqs, appId.toString(), false).sendMessage(MessageProtocol.createManagerTerminationMessage());
      appTasksQueue.deleteMessage(message);
    } catch (InterruptedException | ExecutionException e) {
      e.printStackTrace();
    }
  }

  private static void parseWorkerMessage(Message message) {
    try {
      String[] messageSplits = MessageProtocol.split(message.body());
      String messageType = messageSplits[0];
      handleWorkerResponse(messageType, messageSplits);
      workerResponseQueue.deleteMessage(message);
    } catch (ExecutionException | InterruptedException e) {
      e.printStackTrace();
    }
  }

  private static void handleWorkerResponse(String messageType, String[] messageSplits) {
    try {
      switch (messageType) {
        case MessageProtocol.WORKER_TASK_FINISH:
          UUID appId = UUID.fromString(messageSplits[1]);
          String imageURL = messageSplits[2];
          String result = messageSplits[3];
          while (!terminated.get() && !applicationLines.containsKey(appId)) {
            synchronized (TASK_START_LOCK) {
              TASK_START_LOCK.wait();
            }
          }
          storage.uploadString(
              appId.toString(),
              UUID.randomUUID().toString(),
              MessageProtocol.createResultSummary(imageURL, result));
          applicationLines.get(appId).getValue1().decrement();
          isAppTaskFinished(appId);
          break;
        case MessageProtocol.WORKER_TASK_FAIL:
          String failMessage = messageSplits[1];
          System.err.println(failMessage + " error occurred while processing a message.");
          break;
      }
    } catch (InterruptedException | ExecutionException | IOException e) {
      e.printStackTrace();
    }
  }

  private static void isAppTaskFinished(UUID appId)
      throws ExecutionException, InterruptedException, IOException {
    LongAdder remainingLines = applicationLines.getOrDefault(appId, null).getValue1();
    if (remainingLines != null && remainingLines.longValue() <= 0) {
      String queueURL = applicationLines.get(appId).getValue0();
      applicationLines.remove(appId);
      new SQSAdapter(sqs, queueURL).sendMessage(MessageProtocol.createManagerResponseMessage());
      if (!terminated.get() && applicationLines.isEmpty()) {
        synchronized (TASK_START_LOCK) {
          TASK_START_LOCK.wait(
              1000
                  * 60
                  * INACTIVE_KEEP_WORKER_MINUTE); // Wait for 5 minutes before terminating workers
                                                  // if no additional tasks in the timespan
          if (applicationLines.isEmpty()) {
            workerHandler.terminateWorkers();
          }
        }
      }
    }
  }

  private static void handleApplicationTask(
      UUID appId, String bucketKey, int linesPerWorker) throws IOException {
    String bucketName = appId.toString();
    InputStream inputFileStream = storage.getFileStream(bucketName, bucketKey);
    int workerCount = queueWorkerTasks(inputFileStream, appId, linesPerWorker);
    storage.deleteObject(bucketName, bucketKey);
    workerHandler.startWorkers(workerCount);
  }

  private static int queueWorkerTasks(InputStream fileStream, UUID appId, int linePerWorker) {
    try (BufferedReader reader = new BufferedReader(new InputStreamReader(fileStream))) {
      String queueURL = new SQSAdapter(sqs, appId.toString(), false).getQueueURL();
      long lineCount = reader.lines().peek(line -> sendWorkerMessage(appId, line)).count();
      LongAdder taskAccumulator = new LongAdder();
      taskAccumulator.add(lineCount);
      applicationLines.putIfAbsent(appId, new Pair<>(queueURL, taskAccumulator));
      synchronized (TASK_START_LOCK) {
        started = true;
        TASK_START_LOCK.notifyAll();
      }
      return (int) Math.ceil((double) taskAccumulator.longValue() / linePerWorker);
    } catch (IOException e) {
      e.printStackTrace();
    }
    return 0;
  }

  private static void sendWorkerMessage(UUID appId, String url) {
    taskHandlers.execute(
        () -> workerTasksQueue.sendMessage(MessageProtocol.createWorkerTaskMessage(appId, url)));
  }

  private static synchronized boolean isOverMemoryThreshold() {
    Runtime runtime = Runtime.getRuntime();
    long allocatedMemory = runtime.totalMemory() - runtime.freeMemory();
    return ((double) allocatedMemory / runtime.maxMemory()) >= MEMORY_PERCENTAGE_THRESHOLD;
  }

  private static String getWorkerBashScript() {
    String userData =
        "#!/bin/bash\n"
            + "cd /home/ubuntu\n"
            + "sudo apt-get update\n"
            + "sudo apt-get install default-jdk -y\n"
            + "sudo curl \"https://awscli.amazonaws.com/awscli-exe-linux-x86_64.zip\" -o \"awscliv2.zip\"\n"
            + "sudo unzip awscliv2.zip\n"
            + "sudo ./aws/install"
            + "sudo apt install tesseract-ocr -y\n"
            + "sudo apt install libtesseract-dev -y\n"
            + "sudo apt-get install unzip -y\n"
            + String.format(
                "if ! test -f \"worker.zip\"; then\n"
                    + "    sudo aws s3 cp s3://%s/worker.zip worker.zip\n"
                    + "else\n"
                    + "    echo \"Worker file exists!\"\n"
                    + "fi\n",
                JAR_BUCKET)
            + "sudo unzip worker.zip\n"
            + String.format(
                "sudo java -jar worker.jar %s %s", workerTasksQueueName, workerResponseQueueName);
    return encodeStringBase64(userData);
  }

  private static String encodeStringBase64(String str) {
    return new String(
        Base64.getEncoder().encode(str.getBytes(StandardCharsets.UTF_8)), StandardCharsets.UTF_8);
  }
}
