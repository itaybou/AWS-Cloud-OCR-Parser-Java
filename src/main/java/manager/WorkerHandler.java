package manager;

import protocol.MessageProtocol;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.cloudwatch.CloudWatchClient;
import software.amazon.awssdk.services.cloudwatch.model.*;
import software.amazon.awssdk.services.ec2.Ec2Client;
import software.amazon.awssdk.services.ec2.model.Tag;
import software.amazon.awssdk.services.ec2.model.*;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

public class WorkerHandler {

    private final Ec2Client ec2;
    private static CloudWatchClient cw;

    private static final int MAX_AWS_WORKERS = 20;

    private final String workerAMI;
    private final String iamArn;
    private final String userId;
    private final String workerBashScript;
    private final Region region;

    private final AtomicInteger expectedWorkerCount;

    public WorkerHandler(Region region, String workerAMI, String iamArn, String userId, String workerBashScript) {
        ec2 = Ec2Client.builder().region(region).build();
        cw = CloudWatchClient.builder().region(region).build();
        expectedWorkerCount = new AtomicInteger(0);
        this.workerBashScript = workerBashScript;
        this.workerAMI = workerAMI;
        this.iamArn = iamArn;
        this.userId = userId;
        this.region = region;
    }

    public synchronized void startWorkers(int workerCount) {
        List<String> activeWorkerIds = getActiveWorkerInstanceIds();
        int workersToCreate = workerCount - activeWorkerIds.size();
        if (activeWorkerIds.size() == MAX_AWS_WORKERS || workersToCreate <= 0) return;

        if (expectedWorkerCount.get() > activeWorkerIds.size()) {
            workersToCreate += expectedWorkerCount.get() - activeWorkerIds.size();
        }

        initWorkers(workersToCreate);
    }

    private synchronized void initWorkers(int workersToCreate) {
        try {
            IamInstanceProfileSpecification profile =
                    IamInstanceProfileSpecification.builder().arn(iamArn).build();

            RunInstancesRequest request =
                    RunInstancesRequest.builder()
                            .minCount(1)
                            .maxCount(workersToCreate)
                            .imageId(workerAMI)
                            .iamInstanceProfile(profile)
                            .instanceType(InstanceType.T2_MICRO)
                            .userData(workerBashScript)
                            .build();

            RunInstancesResponse response = ec2.runInstances(request);
            List<Instance> workers = response.instances();
            List<String> instanceIds =
                    workers.stream().map(Instance::instanceId).collect(Collectors.toList());
            addWorkerTags(instanceIds);
            addWorkerAlarms(instanceIds);
            expectedWorkerCount.set(expectedWorkerCount.get() + workersToCreate);
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private synchronized void addWorkerTags(List<String> instanceIds) {
        Tag tag = Tag.builder().key("Name").value("worker").build();
        CreateTagsRequest tagRequest =
                CreateTagsRequest.builder().resources(instanceIds).tags(tag).build();
        ec2.createTags(tagRequest);
    }

    private synchronized void addWorkerAlarms(List<String> instanceIds) {
        List<Dimension> dimensions = instanceIds.stream().map(instanceId ->
                Dimension.builder().name("InstanceId").value(instanceId).build()).collect(Collectors.toList());

        PutMetricAlarmRequest metricAlarmRequest =
                PutMetricAlarmRequest.builder()
                        .alarmName(String.format("%s-recover-alarm-worker", MessageProtocol.BUCKET_PREFIX))
                        .alarmActions(
                            String.format(
                                    "arn:aws:swf:%s:%s:action/actions/AWS_EC2.InstanceId.Reboot/1.0", region, userId))
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
                        .dimensions(dimensions)
                        .build();

        cw.putMetricAlarm(metricAlarmRequest);
    }

    public synchronized void terminateWorkers() {
        TerminateInstancesRequest terminateInstancesRequest =
                TerminateInstancesRequest.builder().instanceIds(getActiveWorkerInstanceIds()).build();
        ec2.terminateInstances(terminateInstancesRequest);
        expectedWorkerCount.set(0);
    }

    private synchronized List<String> getActiveWorkerInstanceIds() {
        List<String> instanceIds = new ArrayList<>();

        DescribeInstancesRequest request = DescribeInstancesRequest.builder().build();
        DescribeInstancesResponse response = ec2.describeInstances(request);
        for (Reservation reservation : response.reservations()) {
            for (Instance instance : reservation.instances()) {
                if (instance.state().name().equals(InstanceStateName.RUNNING)
                        || instance.state().name().equals(InstanceStateName.PENDING))
                    for (Tag tag : instance.tags()) {
                        if (tag.value().equals("worker")) instanceIds.add(instance.instanceId());
                    }
            }
        }
        return instanceIds;
    }
}
