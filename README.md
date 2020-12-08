# OCR in the Cloud

================================================================================

Created by:
	Itay Bouganim : 305278384
	Sahar Vaya : 205583453
================================================================================

## Table of contents
* [General info](#general-info)
* [Statistics] (#Statistics)
* [Project workflow and summary] (#project-workflow)
* [Setup](#setup)
* [Instructions](#Instructions)

## General info

EC2 instances used:
Manager & Workers - ami-0885b1f6bd170450c (64-bit x86) type: T2_MICRO

##Statistics:

including running Manger (Cold start) :

+-------------------------------------------+
|                Results(sec)               |
+---------------+------+------+------+------+
| Num of images | App1 | App2 | App3 | App4 |
+---------------+------+------+------+------+
|       24      |  280 |   -  |   -  |   -  |
+---------------+------+------+------+------+
|       24      |  320 |  350 |      |      |
+---------------+------+------+------+------+
|       24      |  400 |  430 |  500 |  565 |
+---------------+------+------+------+------+
|      512      | 1203 |   -  |   -  |   -  |
+---------------+------+------+------+------+
|      512      | 2517 | 2921 |   -  |   -  |
+---------------+------+------+------+------+

Manager already running : 

+-------------------------------------------+
|                Results(sec)               |
+---------------+------+------+------+------+
| Num of images | App1 | App2 | App3 | App4 |
+---------------+------+------+------+------+
|       24      |  130 |   -  |   -  |   -  |
+---------------+------+------+------+------+
|       24      |  220 |  350 |      |      |
+---------------+------+------+------+------+
|       24      |  308 |  402 |  457 |  504 |
+---------------+------+------+------+------+
|      512      | 1058 |   -  |   -  |   -  |
+---------------+------+------+------+------+
|      512      | 1915 | 2269 |   -  |   -  |
+---------------+------+------+------+------+



## Project workflow and summary

![Project workflow diagram](https://github.com/itaybou/AWS-Cloud-OCR-Parser-Java/blob/main/design.png)

l. Local Application uploads the file with the list of images to S3
l. Local Application sends a message (queue) stating of the location of the images list on S3
l. Local Application does one of the two:
	l. Starts the manager
	l. Checks if a manager is active and if not, starts it
4.Manager downloads list of images files from S3
5.Manager creates an SQS message for each URL in the list of images
6.Manager calculates the needed worker count according to the lines per worker
parameter recieved from the local application
7.Worker gets an image URL from an SQS queue
8.Worker initiates a stream from the input URL.
9.Worker applies OCR on image using tesseract library.
10.Worker puts a message in :
	10.1. The temporary aggregation queue for the specific local app 
	10.2. The manager response queue in order to notify the manger that the URL has been processed.
11.Manager reads all the Workers' messages from the aggregation queue and creates one summary file
12.Manager uploads summary file to S3
13.Manager posts an SQS message about summary file
14.Local Application reads SQS message
15.Local Application downloads summary file from S3
16.Local Application creates html output files

## Setup
1.Install aws cli in your operating system, for more information click here :
https://aws.amazon.com/cli/

2.Configure your amazon aws credentials in your .aws directory, alternatively you can set your credentials by using aws cli : 
write in your cmd - "aws config".

3.Create a new IAM Role in aws console :
https://aws.amazon.com/iam/


4.Create a new policy with the following json format (notice that you need to change <@Your_Role_ID> to your role ID number) :
```
{
    "Version": "2012-10-17",
    "Statement": [
        {
            "Sid": "VisualEditor0",
            "Effect": "Allow",
            "Action": "cloudwatch:*",
            "Resource": [
                "arn:aws:cloudwatch::<@Your_Role_ID>:dashboard/*",
                "arn:aws:cloudwatch::<@Your_Role_ID>:insight-rule/",
                "arn:aws:cloudwatch::<@Your_Role_ID>:alarm:"
            ]
        },
        {
            "Sid": "VisualEditor1",
            "Effect": "Allow",
            "Action": [
                "cloudwatch:DescribeInsightRules",
                "cloudwatch:PutMetricData",
                "cloudwatch:GetMetricData",
                "s3:*",
                "sqs:*",
                "cloudwatch:GetMetricStatistics",
                "cloudwatch:DeleteAnomalyDetector",
                "cloudwatch:ListMetrics",
                "cloudwatch:DescribeAnomalyDetectors",
                "iam:*",
                "cloudwatch:DescribeAlarmsForMetric",
                "cloudwatch:ListDashboards",
                "ec2:*",
                "cloudwatch:PutAnomalyDetector",
                "cloudwatch:GetMetricWidgetImage"
            ],
            "Resource": "*"
        }
    ]
}
```

alternatively, set FullAccess permissions for EC2,S3,SQS and cloudwatch in your policy created .

5. After creating the new policy, set the policy to your IAM Role created in step 3.


##Instructions

1.Inside the project directory compile the project using the command : "mvn package".

2.Create in the project target directory file named "iam_arn.txt" .

3.Put your Instance Profile ARN of the created role from the setup instructions inside the "iam_arn.txt" file.

3.Make sure your input text file located in the project target directory.

4.The application should be run as follows:
	"java -jar local.jar <inputFileName> <outputFileName> <n>"
if you want to terminate the manager:
	"java -jar local.jar <inputFileName> <outputFileName> <n> terminate"

while inputFileName is the name of the input file, outputFileName is the name of the output file and 
n is: workers - files ratio (how many image files per worker).


