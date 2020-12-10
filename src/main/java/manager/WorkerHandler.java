package manager;

import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.ec2.Ec2Client;
import software.amazon.awssdk.services.ec2.model.*;

import java.util.ArrayList;
import java.util.List;
import java.util.Timer;
import java.util.TimerTask;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.ReentrantLock;
import java.util.stream.Collectors;

public class WorkerHandler {

    private final Ec2Client ec2;
    private final ReentrantLock workerLock;
    private boolean startedWorkers;

    private static final int MAX_AWS_WORKERS = 20;
    private static final int WORKER_BALANCER_DELAY_MIN = 3;
    private final String workerAMI;
    private final String iamArn;
    private final String workerBashScript;

    private final AtomicInteger expectedWorkerCount;

    public WorkerHandler(Region region, String workerAMI, String iamArn, String workerBashScript) {
        ec2 = Ec2Client.builder().region(region).build();
        expectedWorkerCount = new AtomicInteger(0);
        this.workerBashScript = workerBashScript;
        this.workerAMI = workerAMI;
        this.iamArn = iamArn;
        this.workerLock = new ReentrantLock();
        startedWorkers = false;

        new Timer().schedule(
                new TimerTask() {
                    @Override
                    public void run() {
                        workerLock.lock();
                        synchronized (this) {
                            try {
                                if(startedWorkers) this.wait(1000 * 60);
                                int activeWorkerCount = getActiveWorkerInstanceIds().size();
                                int expectedWorkers = expectedWorkerCount.get();
                                if (activeWorkerCount < expectedWorkers
                                        && expectedWorkers < MAX_AWS_WORKERS) {
                                    initWorkers(expectedWorkers - activeWorkerCount);
                                }
                            } catch (InterruptedException e) {
                                e.printStackTrace();
                            }
                            workerLock.unlock();
                        }
                        startedWorkers = false;
                    }
                },
                1000 * 60 * WORKER_BALANCER_DELAY_MIN);
    }

    public synchronized void startWorkers(int workerCount) {
        workerLock.lock();
        List<String> activeWorkerIds = getActiveWorkerInstanceIds();
        int workersToCreate = workerCount - activeWorkerIds.size();
        if (activeWorkerIds.size() == MAX_AWS_WORKERS || workersToCreate <= 0) return;

        if (expectedWorkerCount.get() > activeWorkerIds.size()) {
            workersToCreate += expectedWorkerCount.get() - activeWorkerIds.size();
        }

        initWorkers(workersToCreate);
        startedWorkers = true;
        workerLock.unlock();
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

    public synchronized void terminateWorkers() {
        workerLock.lock();
        TerminateInstancesRequest terminateInstancesRequest =
                TerminateInstancesRequest.builder().instanceIds(getActiveWorkerInstanceIds()).build();
        ec2.terminateInstances(terminateInstancesRequest);
        expectedWorkerCount.set(0);
        workerLock.unlock();
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
