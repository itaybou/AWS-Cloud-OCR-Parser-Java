package local;

import adapters.S3Adapter;
import adapters.SQSAdapter;
import protocol.MessageProtocol;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.cloudwatch.CloudWatchClient;
import software.amazon.awssdk.services.cloudwatch.model.*;
import software.amazon.awssdk.services.ec2.Ec2Client;
import software.amazon.awssdk.services.ec2.model.Tag;
import software.amazon.awssdk.services.ec2.model.*;
import software.amazon.awssdk.services.sqs.SqsClient;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

public class Local {

  private static final Region REGION = Region.US_EAST_1;
  private static final UUID appId = UUID.randomUUID();
  private static final String JAR_BUCKET =
      String.format("%s-%s", MessageProtocol.BUCKET_PREFIX, "jar-files");

  private static int linesPerWorker;
  private static String inputFile;
  private static String outputFile;
  private static String iamArn;
  private static String userId;
  private static boolean terminateManager;

  private static S3Adapter managerStorageAdapter;

  private static Ec2Client ec2;
  private static CloudWatchClient cw;

  private static SQSAdapter responseQueue;
  private static SQSAdapter taskQueue;

  private static final String MANAGER_AMI = "ami-061c3fb70d78ecdbd";
  private static final String WORKER_AMI = "ami-09611376a9e8f95c7";
  private static final String queueAppManagerName = "manager_tasks";
  private static final String IAM_ARN_FILENAME = "iam_arn.txt";

  private static volatile boolean receivedResponse = false;
  private static boolean coldStart = false;
  private static long startTimeMS;

  public static void main(String[] args) {
    try {
      File file = new File(IAM_ARN_FILENAME);
      BufferedReader br = new BufferedReader(new FileReader(file));
      userId = br.readLine();
      iamArn = br.readLine();
      if (!iamArn.contains("instance-profile") || !userId.matches("[0-9]+")) {
        throw new IOException();
      }
    } catch (IOException | NumberFormatException e) {
      System.err.println(
          "IAM role arn file could not be found or one of the parameters was invalid. please provide the user-id and role in a file named: \""
              + IAM_ARN_FILENAME
              + "\"");
      return;
    }

    if (!parseArgs(args)) {
      System.err.println(
          "Wrong argument format, provide one of the following:\n"
              + "\t<input_file> <output_file> <lines_per_worker>\n"
              + "\t<input_file> <output_file> <lines_per_worker> <terminate>\n");
      return;
    }

    ec2 = Ec2Client.builder().region(REGION).build();
    cw = CloudWatchClient.builder().region(REGION).build();

    SqsClient sqs = SqsClient.builder().region(REGION).build();
    taskQueue = new SQSAdapter(sqs, queueAppManagerName, true);
    responseQueue = new SQSAdapter(sqs, appId.toString(), true);

    managerStorageAdapter = new S3Adapter(REGION);
    start();
  }

  private static void start() {
    startTimeMS = System.currentTimeMillis();
    try {
      managerStorageAdapter.createBucketAndUploadFile(appId.toString(), inputFile);
      sendManagerTask();
      startResponseListener(appId.toString());
      startManagerNode();
    } catch (InterruptedException | ExecutionException | IOException e) {
      System.err.println("Application terminated without result.\n");
    }
  }

  private static void startResponseListener(String bucketName) {
    new Thread(
            () -> {
              while (!receivedResponse) {
                responseQueue.receiveMessage(
                    message -> {
                      try {
                        responseQueue.deleteMessage(message);
                        long totalTimeMS = System.currentTimeMillis() - startTimeMS;
                        String[] messageSplits = MessageProtocol.split(message.body());
                        if (messageSplits[0].equals(MessageProtocol.MANAGER_DONE)) {
                          generateHTMLFromBucket(bucketName, ((double) totalTimeMS / 1000));
                          terminate(bucketName);
                          receivedResponse = true;
                        }
                      } catch (IOException | ExecutionException | InterruptedException e) {
                        System.err.println("Application terminated without result.\n");
                        terminate(bucketName);
                      }
                    });
              }
            })
        .start();
  }

  private static void terminate(String bucketName) throws ExecutionException, InterruptedException {
    managerStorageAdapter.deleteBucket(bucketName);
    responseQueue.deleteQueue();
  }

  private static boolean parseArgs(String[] args) {
    switch (args.length) {
      case 3:
      case 4:
        try {
          linesPerWorker = Integer.parseInt(args[2]);
        } catch (Exception e) {
          throw new IllegalArgumentException("you need to pass number of files per worker");
        }
        inputFile = args[0];
        outputFile = args[1];
        terminateManager = args[args.length - 1].equals("terminate");
        return true;
      default:
        return false;
    }
  }

  private static void sendManagerTask() throws ExecutionException, InterruptedException {
    String message =
        MessageProtocol.createManagerTaskMessage(
            appId, inputFile, linesPerWorker, terminateManager);
    taskQueue.sendMessage(message);
  }

  private static void startManagerNode()
      throws ExecutionException, InterruptedException, IOException {
    boolean managerRunning = checkIfManagerActive();
    if (!managerRunning) {
      coldStart = true;

      try {
        IamInstanceProfileSpecification profile =
            IamInstanceProfileSpecification.builder().arn(iamArn).build();

        RunInstancesRequest request =
            RunInstancesRequest.builder()
                .minCount(1)
                .maxCount(1)
                .imageId(MANAGER_AMI)
                .iamInstanceProfile(profile)
                .instanceType(InstanceType.T2_MICRO)
                .instanceInitiatedShutdownBehavior(ShutdownBehavior.TERMINATE)
                .userData(getManagerBashScript())
                .build();

        RunInstancesResponse response = ec2.runInstances(request);
        String instanceId = response.instances().get(0).instanceId();

        Tag tag = Tag.builder().key("Name").value("manager").build();
        CreateTagsRequest tagRequest =
            CreateTagsRequest.builder().resources(instanceId).tags(tag).build();
        ec2.createTags(tagRequest);

        Dimension dimension = Dimension.builder().name("InstanceId").value(instanceId).build();

        PutMetricAlarmRequest metricAlarmRequest =
            PutMetricAlarmRequest.builder()
                .alarmName(String.format("%s-recover-alarm", MessageProtocol.BUCKET_PREFIX))
                .alarmActions(
                    String.format(
                        "arn:aws:swf:%s:%s:action/actions/AWS_EC2.InstanceId.Reboot/1.0",
                        REGION, userId))
                .evaluationPeriods(1)
                .comparisonOperator(ComparisonOperator.GREATER_THAN_OR_EQUAL_TO_THRESHOLD)
                .metricName("StatusCheckFailed_System")
                .threshold(5.0)
                .namespace("AWS/EC2")
                .period(60 * 5)
                .statistic(Statistic.MINIMUM)
                .actionsEnabled(true)
                .alarmDescription(
                    "Alarm when system constantly fails due to:\n"
                        + "\t- Loss of network connectivity\n"
                        + "\t- Loss of system power\n"
                        + "\t- Software issues on the physical host\n"
                        + "\t- Hardware issues on the physical host that impact network reachability")
                .unit(StandardUnit.SECONDS)
                .dimensions(dimension)
                .build();

        cw.putMetricAlarm(metricAlarmRequest);
      } catch (Exception e) {
        System.out.println(e.toString());
      }
    }
  }

  private static boolean checkIfManagerActive() {
    DescribeInstancesRequest request = DescribeInstancesRequest.builder().build();
    DescribeInstancesResponse response = ec2.describeInstances(request);
    List<Reservation> reservationList = response.reservations();
    for (Reservation reservation : reservationList) {
      for (Instance instance : reservation.instances()) {
        if (instance.state().name().equals(InstanceStateName.RUNNING)
            || instance.state().name().equals(InstanceStateName.PENDING))
          for (Tag tag : instance.tags()) {
            if (tag.value().equals("manager")) return true;
          }
      }
    }
    return false;
  }

  private static void generateHTMLFromBucket(String bucketName, double totalTimeSec)
      throws IOException {
    try (BufferedWriter bw = new BufferedWriter(new FileWriter(outputFile))) {
      bw.write("<!DOCTYPE html>");
      bw.write(
          String.format(
              "<html>\n"
                  + "<head>\n"
                  + "<title>Response %s</title>\n"
                  + "</head>\n"
                  + "<body>\n"
                  + getHTMLStyle()
                  + startHTMLTable(totalTimeSec),
              appId.toString()));

      AtomicInteger index = new AtomicInteger(1);
      managerStorageAdapter.streamBucket(
          bucketName,
          object -> {
            InputStream stream = managerStorageAdapter.getFileStream(bucketName, object.key());
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(stream))) {
              String partialSummary = reader.lines().collect(Collectors.joining("\n"));
              bw.write(generateImageTableCells(partialSummary, index.getAndIncrement()));
            } catch (IOException e) {
              System.err.printf(
                  "Unable to read response from %s, key: %s%n", bucketName, object.key());
            }
          });

      bw.write("\t</tbody>\n</table>" + "</body>\n" + "</html>");
    } catch (IOException e) {
      e.printStackTrace();
    }
  }

  private static String generateImageTableCells(String line, int index) {
    String[] lineSplit = MessageProtocol.split(line);
    String URL = lineSplit[0];
    String resultText = lineSplit[1];
    boolean isError = resultText.startsWith(MessageProtocol.ERROR_PREFIX);
    return String.format(
        "<tr>\n"
            + "\t\t<td><h3>%d</h3></td>\n"
            + "\t\t<td><a href=\"%s\">Image Link</a></td>\n"
            + "\t\t<td><img src=\"%s\" width=\"800\"/></td>\n"
            + "\t\t<td><h2 style=\"color: %s\">%s</h2></td>\n"
            + "\t</tr>",
        index, URL, URL, isError ? "red" : "black", resultText.replace("\n", "<br/>"));
  }

  private static String getHTMLStyle() {
    return "<style>\n"
        + "\t.table {\n"
        + "\t\tborder:1px solid #C0C0C0;\n"
        + "\t\tborder-collapse:collapse;\n"
        + "\t\tpadding:5px;\n"
        + "\t\tmargin-left: auto;\n"
        + "\t\tmargin-right: auto;\n"
        + "\t}\n"
        + "\t.table th {\n"
        + "\t\tborder:1px solid #C0C0C0;\n"
        + "\t\tpadding:5px;\n"
        + "\t\tbackground:#F0F0F0;\n"
        + "\t}\n"
        + "\t.table td {\n"
        + "\t\tborder:1px solid #C0C0C0;\n"
        + "\t\tpadding:5px;\n"
        + "\t\t    vertical-align: middle;\n"
        + "    text-align: center;"
        + "\t}\n"
        + "</style>";
  }

  private static String startHTMLTable(double totalTimeSec) {
    return String.format(
        "<table class=\"table\">\n"
            + "\t<caption><h1>OCR Output Response - Request Time E2E: %.2f sec%s</h1></caption>"
            + "<thead>\n"
            + "\t<tr>\n"
            + "\t\t<th>Index</th>\n"
            + "\t\t<th>Image URL</th>\n"
            + "\t\t<th>Image</th>\n"
            + "\t\t<th>Output Text</th>\n"
            + "\t</tr>\n"
            + "\t</thead>"
            + "\t<tbody>",
        totalTimeSec, coldStart ? " (Cold start)" : "");
  }

  private static String getManagerBashScript() {
    String userData =
        "#!/bin/bash\n"
            + "cd /home/ubuntu\n"
            + "sudo apt-get update\n"
            + "sudo apt-get install default-jdk -y\n"
            + "sudo curl \"https://awscli.amazonaws.com/awscli-exe-linux-x86_64.zip\" -o \"awscliv2.zip\"\n"
            + "sudo unzip awscliv2.zip\n"
            + "sudo ./aws/install"
            + "sudo apt-get install unzip -y\n"
            + String.format(
                "if ! test -f \"manager.zip\"; then\n"
                    + "    sudo aws s3 cp s3://%s/manager.zip manager.zip\n"
                    + "else\n"
                    + "    echo \"Manager file exists!\"\n"
                    + "fi\n",
                JAR_BUCKET)
            + "sudo unzip manager.zip\n"
            + String.format("sudo java -jar manager.jar %s %s %s", queueAppManagerName, WORKER_AMI, iamArn);
    return encodeStringBase64(userData);
  }

  private static String encodeStringBase64(String str) {
    return new String(
        Base64.getEncoder().encode(str.getBytes(StandardCharsets.UTF_8)), StandardCharsets.UTF_8);
  }
}
