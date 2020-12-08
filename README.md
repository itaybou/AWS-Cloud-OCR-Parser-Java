# AWS OCR in the Cloud

================================================================================

Created by:  
	Itay Bouganim : 305278384  
	Sahar Vaya : 205583453  
================================================================================

## Table of contents
* [General info](#general-info)
* [Statistics](#Statistics)
* [Project workflow and summary](#project-workflow)
* [Setup](#setup)
* [Instructions](#Instructions)

## General info

EC2 instances used:
Manager & Workers - 
 * Used AMI - ami-0885b1f6bd170450c
 * Machine type - (64-bit x86) type: T2_MICRO

## Statistics:
Running results time in seconds:

- including running Manger (Cold start) :

| URLs no. | App 1 | App 2 | App 3 | App 4 |
|----------|-------|-------|-------|-------|
|    24    |  280  |   -   |   -   |   -   |
|    24    |  320  |  350  |   -   |   -   |
|    24    |  401  |  432  |  517  |  565  |
|    512   |  1203 |   -   |   -   |   -   |
|    512   |  2517 |  2921 |   -   |   -   |

- Manager already running : 

| URLs no. | App 1 | App 2 | App 3 | App 4 |
|----------|-------|-------|-------|-------|
|    24    |  130  |   -   |   -   |   -   |
|    24    |  221  |  276  |   -   |   -   |
|    24    |  318  |  402  |  457  |  504  |
|    512   |  1058 |   -   |   -   |   -   |
|    512   |  1915 |  2269 |   -   |   -   |


## Project workflow and summary

![Project workflow diagram](https://github.com/itaybou/AWS-Cloud-OCR-Parser-Java/blob/main/design.png)

1. Local Application uploads the file with the list of images to S3
1. Local Application sends a message (queue) stating of the location of the images list on S3
1. Local Application does one of the two:
	1. Starts the manager
	1. Checks if a manager is active and if not, starts it
1. Manager downloads list of images files from S3
1. Manager creates an SQS message for each URL in the list of images
1. Manager calculates the needed worker count according to the lines per worker
parameter recieved from the local application
1. Worker gets an image URL from an SQS queue
1. Worker initiates a stream from the input URL.
1. Worker applies OCR on image using tesseract library.
1. Worker puts a message in :
	1. The temporary aggregation queue for the specific local app 
	1. The manager response queue in order to notify the manger that the URL has been processed.
1. Manager reads all the Workers' messages from the aggregation queue and creates one summary file
1. Manager uploads summary file to S3
1. Manager posts an SQS message about summary file
1. Local Application reads SQS message
1. Local Application downloads summary file from S3
1. Local Application creates html output files

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

Alternatively, set FullAccess permissions for EC2, S3, SQS and CloudWatch in your policy created.

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


