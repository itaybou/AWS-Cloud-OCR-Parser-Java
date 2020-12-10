# AWS OCR in the Cloud
## Created by:  
### Itay Bouganim : 305278384  
### Sahar Vaya : 205583453  
## 
## Table of contents
* [General info](#general-info)
* [Statistics](#Statistics)
* [Project workflow and summary](#project-workflow-and-summary)
* [Examples And Resources](#Examples-And-Resources)
* [Setup](#setup)
* [Instructions](#Instructions)
* [Mandatory Requirements](#Mandatory-Requirements)

## General info

Project GitHub repository link:
https://github.com/itaybou/AWS-Cloud-OCR-Parser-Java

EC2 instances used:
Manager & Workers - 
 * Used AMI - 
 	- Manager - ami-0885b1f6bd170450c
	- Worker - ami-0885b1f6bd170450c
 * Machine types - (64-bit x86) type: T2_MICRO

## Statistics:
Running results time in *seconds*:

- including running Manger (Cold start) :

| URLs no. |   URLs/Worker  |  Workers  | App 1 | App 2 | App 3 | App 4 |
|----------|----------------|-----------|-------|-------|-------|-------|
|    24    |       20       |     2     |  210  |   -   |   -   |   -   |
|    24    |       20       |     2     |  281  |  276  |   -   |   -   |
|    24    |       20       |     2     |  320  |  333  |  386  |  402  |
|    512   |       70       |     8     |  1203 |   -   |   -   |   -   |
|    512   |       70       |     8     |  1974 |  2109 |   -   |   -   |


- Manager already running : 

| URLs no. |   URLs/Worker  |  Workers  | App 1 | App 2 | App 3 | App 4 |
|----------|----------------|-----------|-------|-------|-------|-------|
|    24    |       20       |     2     |  130  |   -   |   -   |   -   |
|    24    |       20       |     2     |  197  |  224  |   -   |   -   |
|    24    |       20       |     2     |  269  |  271  |  318  |  321  |
|    512   |       70       |     8     |  1017 |   -   |   -   |   -   |
|    512   |       70       |     8     |  1643 |  1795 |   -   |   -   |


## Project workflow and summary

![Project workflow diagram](https://github.com/itaybou/AWS-Cloud-OCR-Parser-Java/blob/main/resources/design.png)

1. Local Application uploads the file with the list of images to S3
1. Local Application sends a message (queue) stating of the location of the images list on S3
1. Local Application does one of the two:
	1. Starts the manager
	1. Checks if a manager is active and if not, starts it
1. Manager downloads list of image URLs from S3
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

- NOTES:
	1. If manager recieves a termination message, the rest of the requests from other apps will be rejected and they will get a termination notification.
	1. Upon termination message, manager will finish processing current accepted pending requests before terminating.


## Examples And Resources
- Project JAR files (including the local.jar used to initiate the project locally) can be found in the projects target directory.
- Examples for input files and output file can be found in the resources directory of this project.
- Example for the "iam_arn.txt" text file needed to run the project (Will be elaborated later) can be found in the resources directory of the project.


## Setup
1. Install aws cli in your operating system, for more information click here :
https://aws.amazon.com/cli/

2. Configure your amazon aws credentials in your .aws directory, alternatively you can set your credentials by using aws cli : 
write in your cmd - "aws config".

3. Create a new IAM Role in aws console :
https://aws.amazon.com/iam/


4. Create a new policy with the following json format (notice that you need to change <Account-ID> to your role ID number) :
```
{
    "Version": "2012-10-17",
    "Statement": [
        {
            "Sid": "VisualEditor0",
            "Effect": "Allow",
            "Action": [
                "iam:*",
                "s3:*",
                "ec2:*",
                "sqs:*"
            ],
            "Resource": "*"
        },
        {
            "Sid": "VisualEditor1",
            "Effect": "Allow",
            "Action": "cloudwatch:*",
            "Resource": [
                "arn:aws:cloudwatch::<Account-ID>:dashboard/*",
                "arn:aws:cloudwatch:*:<Account-ID>:insight-rule/*",
                "arn:aws:cloudwatch:*:<Account-ID>:alarm:*"
            ]
        }
    ]
}
```

Alternatively, set FullAccess permissions for EC2, S3, SQS and CloudWatch in your policy created.

5. After creating the new policy, set the policy to your IAM Role created in step 3.


## Instructions

1. Inside the project directory compile the project using the command : ```mvn package```.

2. Create in the project target directory file named "iam_arn.txt".

3. Put your AWS account id and the Instance Profile ARN of the created role from the setup instructions inside the "iam_arn.txt" file in the following format:
```
<Account-ID>
<Role Instance Profile ARN>
```

4. Make sure your input text file located in the project target directory or in the same directory as the local-app jar file.

5. The application should be run as follows:  
	```java -jar local.jar <inputFileName> <outputFileName> <n>```  
if you want to terminate the manager:  
	```java -jar local.jar <inputFileName> <outputFileName> <n> terminate```  

while <inputFileName> is the name of the input file, <outputFileName> is the name of the output file and 
<n> is: workers - files ratio (how many image files per worker).


## Mandatory Requirements

### Security
 - The application uses AWS IAM role as a measure of security, as a result, specifc AWS credentials are not hard-coded nor passed in the code or jar files.
 - As mentioned in the Setup instructions, in order to use the application you must have a valid AWS account and it must contain the IAM role with the policy as mentioned above.
 - A text file named "iam_arn.txt" must be provided outside of the location the local-app jar file will be executed from containing both your AWS account Id and the Instance Profile ARN of the newly created role.
 
 ### Scalability
 - The application does not save any output nor message in memory or on disk therefore, alothough the manager instance uses only T2_MICRO instance, it is able to perform on a large amount of local applications concurrently.
 - The application stores only the IDs of the local applications with their corresponding response queue URL and remaining image URL count that are needed in order to return an answer to the application. To prevent memory issues, the application holds new tasks while the memory consumption is more than 80% present on the EC2 instance.
 - The application starts workers by need in order to operate on more demanding local application tasks.
 - The application terminates worker instances if no current tasks are present and no new tasks recieved in a 5 minutes range in order to not waste resources.
 - The application uses streams in order to parse messages and send responses and therfore does not store entire input/output in memory, disk is not used, instead all sotrage is done in the S3 cloud.
 
  ### Persistence
  - The application uses AWS CloudWatch alarms and alarm actions in order to persist the application in case of faliures.
  - When CloudWatch alarm detect multiple error or unexpected behaviour(Termination, Stopping) of worker instances, it will restart the worker instance.
  - Alarm will trigger recovery/reboot the following issues occur more than 5 times in a timespan of 10 minutes:
  	- Loss of network connectivity
	- Loss of system power
	- Software issues on the physical host
	- Hardware issues on the physical host that impact network reachability
 
  ### Concurrency
 - The manager node operates concurrently on both receiveing local tasks application and recieving worker node task responses (app listener thread, worker listener thread pool and worker task senders thread pool).
 - The manager nodes sends task messages to the workers SQS queue concurrently so that will start operate on those message before the manager finished sending all the requests (biggest impact on input files with more than 100 image URLS)
 
  ### Multiple Local App clients
 - The application is able to handle multiple local application in the same time without failing due to the utilization of the AWS cloud resources.
 - Termination messages from an application will not prevent already recieved requests from other local apps to recieve output summary response.
 
  ### Termination Process
1. Local app start with the ```terminate``` flag as an argument.
1. Local app send a task for the manager with the request to terminate
1. Manager parses the recieved message and starts termination process.
1. Manager stops accepting new local app tasks and notifies all the waiting apps that he did not process their task request yet that he is going to terminate so that they will be able to stop waiting.
1. All local apps that the manager did not process yet recieve manager termination message and stops listening for responses.
1. Rejected local apps delete their respective response queue and input file bucket.
1. Manager waits for all current accepted local app tasks to finish.
1. Manager send the summary response in the form of an S3 bucket to still waiting approved local apps.
1. Local apps recieves responses and delete their respective response queues and output summary bucket.
1. Manager stops all working threads and deletes worker tasks and responses queues and the local app tasks queue.
1. Manager self terminates the machine which stops the AWS EC2 instance as a result.
 
  ### Instances Tasks
- Local App 
	- Starts the manager node if not yet active.
	- Sends a task to the manager node task queue containing the location of the input file in S3
	- Recieves a response from the manager in the form of the output summary bucket in S3 or termination message
	- Generates HTML output file from the responses in the S3 bucket.
	
- Manager
	- Serves as a pipe between the applications and the workers
	- Recives tasks from local application, downloads the images URL input files
	- Starts workers nodes, send sub-tasks (1 image URL) to the workers task queue
	- Recieves responses from worker with OCR output applied to the image represented by the sent URL or an Exception message if occured while trying to apply OCR to the image or due to broken links.
	- Aggregates the worker responses to S3 and notfies local applications of finished tasks.
	
- Worker
	- Applies OCR by using the Tesseract Tess4j java library on the URLs recived from the messages in the worker task queue
	- Sends the output or exception messages if occured while trying to download the image as a stream or to apply the OCR on the image.

