package adapters;

import software.amazon.awssdk.services.sqs.SqsClient;
import software.amazon.awssdk.services.sqs.model.*;

import java.util.List;
import java.util.concurrent.ExecutionException;

public class SQSAdapter {

  private SqsClient sqs;
  private final String queueURL;

  public SQSAdapter(SqsClient sqs, String queueName, boolean create) {
    this.sqs = sqs;
    if (create) {
      CreateQueueRequest createQueueRequest =
          CreateQueueRequest.builder().queueName(queueName).build();
      queueURL = sqs.createQueue(createQueueRequest).queueUrl();
    } else {
      GetQueueUrlRequest queueUrlRequest =
          GetQueueUrlRequest.builder().queueName(queueName).build();
      queueURL = sqs.getQueueUrl(queueUrlRequest).queueUrl();
    }
  }

  public SQSAdapter(SqsClient sqs, String queueURL) {
    this.sqs = sqs;
    this.queueURL = queueURL;
  }

  public void deleteMessage(Message message) throws ExecutionException, InterruptedException {
    DeleteMessageRequest deleteRequest =
        DeleteMessageRequest.builder()
            .receiptHandle(message.receiptHandle())
            .queueUrl(queueURL)
            .build();
    sqs.deleteMessage(deleteRequest);
  }

  public void receiveMessage(MessageReceivedCallback callback) {
    ReceiveMessageRequest messageRequest =
        ReceiveMessageRequest.builder()
            .queueUrl(queueURL)
            .maxNumberOfMessages(1)
            .waitTimeSeconds(20)
            .visibilityTimeout(45)
            .build();
    ReceiveMessageResponse response = sqs.receiveMessage(messageRequest);
    if (response.hasMessages()) {
      try {
        callback.onReceive(response.messages().get(0));
      } catch (Exception e) {
        e.printStackTrace();
      }
    }
  }

  public void receiveMessageBatch(MessageReceivedCallback callback) {
    try {
      ReceiveMessageRequest messageRequest =
          ReceiveMessageRequest.builder()
              .queueUrl(queueURL)
              .maxNumberOfMessages(10)
              .waitTimeSeconds(15)
              .visibilityTimeout(60 * 5)
              .build();

      List<Message> messages = sqs.receiveMessage(messageRequest).messages();
      for (Message message : messages) {
        callback.onReceive(message);
      }
    } catch (Exception e) {
      e.printStackTrace();
    }
  }

  public void sendMessage(final String message) {
    try {
      SendMessageRequest sendMessageRequest =
          SendMessageRequest.builder().queueUrl(queueURL).messageBody(message).build();
      sqs.sendMessage(sendMessageRequest);
    } catch (Exception e) {
      e.printStackTrace();
    }
  }

  public void deleteQueue() {
    DeleteQueueRequest deleteQueueRequest = DeleteQueueRequest.builder().queueUrl(queueURL).build();
    sqs.deleteQueue(deleteQueueRequest);
  }

  public String getQueueURL() {
    return queueURL;
  }
}
